package com.localdrop.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.room.Room
import com.localdrop.core.AppLogger
import com.localdrop.discovery.DeviceInfo
import com.localdrop.discovery.LanDiscovery
import com.localdrop.protocol.Protocol
import com.localdrop.security.AndroidKeyStoreDeviceIdentityStore
import com.localdrop.security.AppSecurityLogger
import com.localdrop.storage.AppDatabase
import com.localdrop.storage.CheckpointDao
import com.localdrop.transfer.ReceiveCoordinator
import com.localdrop.transfer.SendCoordinator
import com.localdrop.transfer.TransferOutcome
import com.localdrop.transfer.TransferProgress
import com.localdrop.transfer.TransferSource
import com.localdrop.widget.TransferWidgetSnapshot
import com.localdrop.widget.WidgetTransferEntry
import java.io.File

// M13 bidirectional audit: service-owned transfer graph (BOTH directions).
//
// OWNERSHIP: this Service (not any Activity/ViewModel) owns the full
// transfer stack — incoming (control server + data engine + receive
// discovery + checkpoint DAO) AND outgoing (send discovery +
// SendCoordinator + tracked OutgoingTransfers executions). Audit finding
// that forced this: outgoing transfers were ViewModel-owned (AppStack built
// SendCoordinator + LanDiscovery; ViewModel started/stopped them; UI close
// actively cancelled in-flight sends). Now the Activity binds only to
// observe snapshots and to submit sends; rotation/config-change recreates
// the Activity only, which re-binds to this same instance — no duplicate
// engines, listeners, discovery loops, or workers, and no teardown or
// cancellation of live transfers by UI destruction.
//
// START/BIND/UNBIND/STOP (exact):
// - onCreate(): builds the graph ONCE (device identity, Room DAO, receive
//   coordinator, send discovery + coordinator + outgoing tracker). A Room
//   failure is RECORDED on databaseFailure and the graph runs WITHOUT
//   resume (transfers still work at offset 0); the failure is surfaced to
//   bound UIs via databaseFailure() — never silently swallowed.
// - onStartCommand(): idempotent graph.start(); promotes to foreground with
//   the summary notification; START_STICKY (system may recreate us; onCreate
//   rebuilds exactly one graph). Repeated starts are no-ops (TransferGraph).
// - onBind(): returns the binder for observation + send submission ONLY;
//   binding never starts/stops anything.
// - onUnbind(): no-op for the graph (observers detach; engines and tracked
//   sends keep running).
// - onDestroy(): outgoing.shutdown() (cancel-all + join) then graph.stop()
//   exactly once (idempotent); workers joined by the coordinator/engine
//   stop paths.
// - Zero active transfers: stopForeground (drop foreground status, keep the
//   service + listeners alive for new requests) and cancel per-transfer
//   notifications; the graph is NOT stopped (idle != stopped).
//
// PLATFORM (targetSdk 36, minSdk 26):
// - Foreground-service type "dataSync" (Android 10+/API 29 feature) declared
//   in the manifest with FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC
//   permissions. dataSync is the type whose documented purpose is transfer
//   of data; no other type fits a LAN file transfer.
// - Android 14+ (API 34): the type + permission are MANDATORY, else the
//   startForeground call throws. Android 13+ (API 33): POST_NOTIFICATIONS
//   runtime permission gates showing notifications (requested by the UI).
// - NO unlimited background execution is claimed: the service must call
//   startForeground within the system timeout after startForegroundService;
//   background starts are restricted (exemptions: user-visible action);
//   Doze/App Standby defer discovery/network; the user or system may stop
//   the service at any time. Foreground status reduces — never removes —
//   killability.
class TransferService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): TransferService = this@TransferService
    }

    private val binder = LocalBinder()
    private val notificationIds = NotificationIds()

    var graph: TransferGraph? = null
        private set
    var receiveCoordinator: ReceiveCoordinator? = null
        private set
    var sendCoordinator: SendCoordinator? = null
        private set
    var outgoing: OutgoingTransfers? = null
        private set
    var device: DeviceInfo? = null
        private set
    var checkpointDao: CheckpointDao? = null
        private set
    // Non-null when Room init failed: resume disabled BUT visibly reported.
    var databaseFailure: Exception? = null
        private set
    private var widgetGeneration: Long = 0L

    override fun onCreate() {
        super.onCreate()
        if (graph != null) return // survive framework double-create, if any
        val store = AndroidKeyStoreDeviceIdentityStore()
        val appLogger = object : AppLogger {
            override fun d(tag: String, message: String) = Unit
            override fun i(tag: String, message: String) = Unit
            override fun w(tag: String, message: String) = Unit
            override fun e(tag: String, message: String, throwable: Throwable?) = Unit
        }
        val logger = AppSecurityLogger(appLogger)
        val rawName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
        val dev: DeviceInfo
        try {
            dev = DeviceInfo(store.identity().deviceId, rawName.take(Protocol.MAX_DEVICE_NAME), "android")
        } catch (e: Exception) {
            // Identity failure fails service creation loudly (no half graph).
            throw IllegalStateException("Device identity unavailable.", e)
        }
        device = dev
        val dao: CheckpointDao? = try {
            val db = Room.databaseBuilder(
                applicationContext, AppDatabase::class.java, "localdrop.db").build()
            db.checkpointDao()
        } catch (e: Exception) {
            databaseFailure = e
            null
        }
        checkpointDao = dao
        val destDir = File(filesDir, "LocalDrop").apply { mkdirs() }
        val recv = ReceiveCoordinator(dev, store, logger, destDir, checkpoints = dao)
        receiveCoordinator = recv
        val sendDiscovery = LanDiscovery(dev)
        val send = SendCoordinator(dev, store, logger, sendDiscovery)
        sendCoordinator = send
        val out = OutgoingTransfers(
            sender = { peerId, source, code, isCancelled, onProgress ->
                send.sendFile(peerId, source, code, isCancelled, onProgress)
            })
        out.onProgress = { _, p -> onSendProgress(p) }
        out.onOutcome = { _, o -> onSendOutcome(o) }
        outgoing = out
        graph = TransferGraph(
            components = listOf(
                object : TransferGraph.LifecycleComponent {
                    override fun start() = recv.server.start()
                    override fun stop() = try { recv.server.stop() } catch (e: Exception) { }
                },
                object : TransferGraph.LifecycleComponent {
                    override fun start() = recv.engine.start()
                    override fun stop() = try { recv.engine.stop() } catch (e: Exception) { }
                },
                object : TransferGraph.LifecycleComponent {
                    override fun start() {
                        recv.discovery.controlPort = recv.server.localPort().toLong()
                        recv.discovery.start()
                    }
                    override fun stop() = try { recv.discovery.stop() } catch (e: Exception) { }
                },
                object : TransferGraph.LifecycleComponent {
                    override fun start() = sendDiscovery.start()
                    override fun stop() = try { sendDiscovery.stop() } catch (e: Exception) { }
                }
            ),
            activeCount = {
                val inbound = try { recv.engine.liveTransferCount() } catch (e: Exception) { 0 }
                inbound + out.activeCount()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val g = graph ?: return START_NOT_STICKY
        try {
            g.start() // idempotent: no duplicate engines/listeners/loops
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSummary()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean = false // observers detach; graph keeps running

    override fun onDestroy() {
        // Outgoing first (cooperative cancel + join), then listeners/engines.
        try { outgoing?.shutdown() } catch (e: Exception) { }
        try { graph?.stop() } catch (e: Exception) { }
        super.onDestroy()
    }

    // Called when the authoritative engine reports zero live transfers:
    // drop foreground status but KEEP listening (idle != stopped).
    fun onTransfersIdle() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) { }
    }

    fun notifyTransfer(transferId: String, title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm)
        val id = notificationIds.idFor(transferId)
        nm.notify(id, buildNotification(title, text))
    }

    // Exact per-transfer routing: cancels ONLY the ID issued for this
    // transferId; unknown IDs cancel nothing.
    fun cancelTransferNotification(transferId: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val id = notificationIds.release(transferId) ?: return
        nm.cancel(id)
    }

    // Canonical widget snapshot: derived from the authoritative engine
    // state at call time (transition-driven by the caller), never cached
    // active rows across process recreation (generation resets onCreate).
    // Merges inbound (receiver engine) and outbound (tracked sends) rows.
    fun widgetSnapshot(nowMs: Long): TransferWidgetSnapshot {
        val rows = mutableListOf<WidgetTransferEntry>()
        val info = try { receiveCoordinator?.engine?.activeTransferInfo() } catch (e: Exception) { null }
        if (info != null) {
            val progress = try {
                receiveCoordinator?.engine?.activeReceivedBytes(info.transferId)
            } catch (e: Exception) { null }
            val pct = if (progress != null && progress >= 0 && info.fileSize > 0)
                (progress.toDouble() / info.fileSize.toDouble() * 100.0).coerceIn(0.0, 100.0) else 0.0
            rows.add(WidgetTransferEntry(info.transferId, info.fileName, pct, "transferring"))
        }
        try {
            for (s in outgoing?.snapshotSends() ?: emptyList()) {
                val p = s.progress
                rows.add(WidgetTransferEntry(
                    p?.transferId ?: "send-${s.handleId}",
                    s.fileName,
                    p?.percent ?: 0.0,
                    p?.state?.wire ?: "connecting"))
            }
        } catch (e: Exception) { }
        if (rows.isEmpty()) return TransferWidgetSnapshot.idle(nowMs, widgetGeneration)
        return TransferWidgetSnapshot(nowMs, widgetGeneration, rows)
    }

    fun nextWidgetGeneration(): Long = ++widgetGeneration

    // Outgoing transition feeds (wired to OutgoingTransfers in onCreate):
    // progress drives per-transfer notifications keyed by the authoritative
    // transferId; terminal outcomes cancel exactly that transfer's
    // notification (collision-safe via NotificationIds).
    private var lastSendNotifyMs = 0L

    private fun onSendProgress(p: TransferProgress) {
        val now = System.currentTimeMillis()
        if (now - lastSendNotifyMs < 1000L) return // battery-conscious throttle
        lastSendNotifyMs = now
        try {
            notifyTransfer(p.transferId, "Sending file",
                "${"%.1f".format(p.percent)}% — ${p.transferredBytes} of ${p.totalBytes} bytes")
        } catch (e: Exception) { }
    }

    private fun onSendOutcome(o: TransferOutcome) {
        try {
            when (o) {
                is TransferOutcome.TransportComplete -> cancelTransferNotification(o.transferId)
                is TransferOutcome.Cancelled -> o.transferId?.let { cancelTransferNotification(it) }
                is TransferOutcome.Failed -> o.transferId?.let { cancelTransferNotification(it) }
            }
        } catch (e: Exception) { }
    }

    private fun startForegroundSummary() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null) ensureChannel(nm)
        val n = buildNotification("LocalDrop", "Ready to receive files on this network.")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NotificationIds.FOREGROUND_SUMMARY_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NotificationIds.FOREGROUND_SUMMARY_ID, n)
            }
        } catch (e: Exception) {
            // Foreground promotion failed (e.g. missing permission on API 34+):
            // fail loudly — caller already ran graph.start; shut it down.
            try { graph?.stop() } catch (ignored: Exception) { }
            throw e
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "localdrop_transfers"
    }
}
