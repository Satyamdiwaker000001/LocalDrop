package com.localdrop

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localdrop.discovery.DeviceInfo
import com.localdrop.transfer.ContentUriTransferSource
import com.localdrop.transfer.ReceiveCoordinator
import com.localdrop.transfer.ReceivePhase
import com.localdrop.transfer.ReceiveUiController
import com.localdrop.transfer.SendCoordinator
import com.localdrop.transfer.SendPhase
import com.localdrop.transfer.SendUiController
import com.localdrop.transfer.TransferOutcome
import com.localdrop.transfer.TransferProgress
import com.localdrop.transfer.TransferSource

// M13 bidirectional audit: thin Compose screens over framework-free
// controllers bound to the service-owned graph. Screens render state and
// dispatch user actions ONLY: no sockets, no file I/O, no crypto, no
// orchestration here (UI/UX Spec §18). All transfer work runs service-side
// (OutgoingTransfers worker threads + coordinators); progress and outcomes
// are real engine events. Success is shown only for receiver-verified
// completion (VERIFY_RESULT match + finalization); anything else lands in
// Failed/Cancelled — never presented as success.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = androidx.lifecycle.ViewModelProvider(this)[AppStackViewModel::class.java]
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val ready by viewModel.uiReady
                    val error by viewModel.uiError
                    when {
                        error != null -> Column(modifier = Modifier.padding(24.dp)) {
                            Text("LocalDrop", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(8.dp))
                            Text(error!!)
                        }
                        ready -> {
                            val stack = viewModel.stack
                            if (stack != null) LocalDropApp(stack)
                            else Text("Starting LocalDrop…", modifier = Modifier.padding(24.dp))
                        }
                        else -> Text("Starting LocalDrop…", modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }

    // Production object graph — SERVICE-BOUND (M13 bidirectional audit).
    // Owns NO engines, NO discovery, NO sockets: SendCoordinator,
    // ReceiveCoordinator, OutgoingTransfers, and the Room DAO all live in
    // the bound TransferService. The UI controllers only render state and
    // submit work service-side. Constructed after bind; torn down with
    // release() (detach-only), never by stopping the service graph.
    //
    // M10 resume + M05 auth + M11 SHA-256 + M12 isolation stay connected:
    // this holder changes no auth/session/verify/concurrency logic; it only
    // routes through the service-owned persistent checkpoint implementation
    // (getCheckpointAndMigrate / saveCheckpoint / clearCheckpoint).
    class AppStack(
        resolver: ContentResolver,
        val service: com.localdrop.service.TransferService
    ) {
        val contentResolver: ContentResolver = resolver
        val device: DeviceInfo = service.device
            ?: throw IllegalStateException("Service has no device identity.")
        val sendCoordinator: SendCoordinator = service.sendCoordinator
            ?: throw IllegalStateException("Service has no send coordinator.")
        val receiveCoordinator: ReceiveCoordinator = service.receiveCoordinator
            ?: throw IllegalStateException("Service has no receive coordinator.")
        val outgoing: com.localdrop.service.OutgoingTransfers = service.outgoing
            ?: throw IllegalStateException("Service has no outgoing tracker.")
        val send: SendUiController
        val receive: ReceiveUiController
        // Non-null when the persistent checkpoint store failed to open:
        // resume is then DISABLED and the UI must say so (never silent).
        val databaseFailure: Exception? = service.databaseFailure

        init {
            val facade = object : SendUiController.SendCoordinatorFacade {
                override fun nearbyPeers() = sendCoordinator.nearbyPeers()
                override fun sendFile(
                    peerDeviceId: String, source: TransferSource, code: String,
                    isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit
                ): TransferOutcome {
                    // Service-owned execution: tracked under a handle (exact
                    // per-send cancel + notifications + widget rows). The UI
                    // cancel flag is wired as externalCancel so the on-screen
                    // Cancel button aborts through the same cooperative path.
                    // UI destruction does NOT cancel: release() detaches the
                    // observer while the service-side send runs to completion.
                    val handle = outgoing.startSend(
                        peerDeviceId, source, code,
                        onProgress = { p ->
                            try { onProgress(p) } catch (e: Exception) { }
                        },
                        externalCancel = isCancelled)
                        ?: return TransferOutcome.Failed(
                            null, com.localdrop.protocol.ErrorCode.TRANSFER_REJECTED,
                            "Too many concurrent sends. Wait for one to finish and try again.")
                    return outgoing.awaitOutcome(handle.id, SEND_AWAIT_TIMEOUT_MS)
                        ?: TransferOutcome.Failed(
                            null, com.localdrop.protocol.ErrorCode.TIMEOUT,
                            "Send did not finish in time.")
                }
            }
            send = SendUiController(facade)
            receive = ReceiveUiController(receiveCoordinator)
        }

        // Visible resume-status note: null when resume is armed, non-null
        // text explaining the disabled-resume state when Room init failed.
        fun databaseFailureNote(): String? =
            com.localdrop.service.CheckpointStore.failureNote(databaseFailure)

        companion object {
            // Upper bound for one UI-awaited send (sends are cooperative and
            // cancellable; this only bounds a stuck wait, never a transfer).
            const val SEND_AWAIT_TIMEOUT_MS: Long = 6L * 3600L * 1000L
        }
    }

    class AppStackViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
        // M13 bidirectional audit: the ViewModel NEVER owns engines,
        // discovery, or transfers. It starts + binds the service (sole
        // owner), builds UI-only controllers over the bound coordinators,
        // and on teardown DETACHES (release()) without stopping or
        // cancelling anything service-side. In-flight sends and receives
        // survive ViewModel/Activity destruction; only the service's
        // onDestroy stops the graph.
        val uiReady = androidx.compose.runtime.mutableStateOf(false)
        val uiError = androidx.compose.runtime.mutableStateOf<String?>(null)
        var stack: AppStack? = null
            private set
        private var poller: Thread? = null
        private var connection: android.content.ServiceConnection? = null
        @Volatile private var bound = false
        @Volatile private var tornDown = false

        init {
            val app = getApplication<android.app.Application>()
            try {
                app.startService(android.content.Intent(app, com.localdrop.service.TransferService::class.java))
            } catch (e: Exception) {
                uiError.value = "Could not start the background service: ${e.message}"
            }
            if (uiError.value == null) {
                val conn = object : android.content.ServiceConnection {
                    override fun onServiceConnected(
                        name: android.content.ComponentName?,
                        binder: android.os.IBinder?
                    ) {
                        try {
                            val service = (binder as com.localdrop.service.TransferService.LocalBinder).service()
                            val created = AppStack(app.contentResolver, service)
                            synchronized(this@AppStackViewModel) {
                                if (tornDown) return
                                stack = created
                            }
                            created.receive.start()
                            created.receive.attachProgress()
                            startPoller(created)
                            uiReady.value = true
                        } catch (e: Exception) {
                            uiError.value = "Could not open this device's identity. Reinstalling the app resets it."
                        }
                    }

                    override fun onServiceDisconnected(name: android.content.ComponentName?) {
                        // Framework rebind path only; the service process is
                        // the owner and decides its own fate.
                    }
                }
                connection = conn
                val ok = try {
                    app.bindService(
                        android.content.Intent(app, com.localdrop.service.TransferService::class.java),
                        conn, android.content.Context.BIND_AUTO_CREATE)
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    uiError.value = "Could not connect to the background service."
                } else {
                    bound = true
                    Thread({
                        try { Thread.sleep(SERVICE_BIND_TIMEOUT_MS) } catch (e: InterruptedException) { return@Thread }
                        synchronized(this@AppStackViewModel) {
                            if (stack == null && uiError.value == null) {
                                uiError.value = "Could not connect to the background service. " +
                                    "If this persists, reinstalling the app resets its identity."
                            }
                        }
                    }, "localdrop-bind-watchdog").apply { isDaemon = true; start() }
                }
            }
        }

        private fun startPoller(created: AppStack) {
            val t = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    try { created.send.refreshPeers() } catch (e: Exception) { }
                    try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
                }
            }, "localdrop-ui-poll").apply { isDaemon = true; start() }
            synchronized(this) { poller = t }
        }

        override fun onCleared() {
            super.onCleared()
            val s: AppStack?
            val p: Thread?
            synchronized(this) {
                tornDown = true
                s = stack
                stack = null
                p = poller
                poller = null
            }
            try { p?.interrupt() } catch (e: Exception) { }
            // Detach ONLY: never stop coordinators, never cancel transfers,
            // never stop the service. Live sends/receives continue
            // service-side (notifications + widgets stay authoritative).
            try { s?.send?.release() } catch (e: Exception) { }
            try { s?.receive?.release() } catch (e: Exception) { }
            try { p?.join(3000) } catch (e: Exception) { }
            try {
                val conn = connection
                if (bound && conn != null) {
                    try { getApplication<android.app.Application>().unbindService(conn) } catch (e: Exception) { }
                }
            } catch (e: Exception) { }
            // NOTE: no stopService here by design — the foreground service
            // persists (discoverable receiver + tracked sends) until the
            // user or system stops it. JVM-testable invariant: after this
            // teardown the service graph is still running (see UiDestructionTest).
        }

        companion object {
            const val SERVICE_BIND_TIMEOUT_MS = 15000L
        }
    }
}

@Composable
private fun LocalDropApp(stack: MainActivity.AppStack) {
    var tab by remember { mutableIntStateOf(0) }
    var sendUi by remember { mutableStateOf(stack.send.state) }
    var receiveUi by remember { mutableStateOf(stack.receive.state) }

    DisposableEffect(stack) {
        stack.send.onChange = { sendUi = stack.send.state }
        stack.receive.onChange = { receiveUi = stack.receive.state }
        onDispose {
            stack.send.onChange = {}
            stack.receive.onChange = {}
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Send") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Receive") })
        }
        if (tab == 0) {
            SendScreen(stack, sendUi)
        } else {
            ReceiveScreen(stack, receiveUi)
        }
    }
}

@Composable
private fun SendScreen(stack: MainActivity.AppStack, ui: com.localdrop.transfer.SendUiState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // user cancelled: keep state
        try {
            stack.send.setFile(resolvePickedFile(stack.contentResolver, uri))
        } catch (e: Exception) {
            stack.send.showMessage("Could not open that document: ${e.message}")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
        Text(
            "Devices running LocalDrop on this Wi-Fi network appear automatically. " +
                "Select a device — never type an address.",
            style = MaterialTheme.typography.bodySmall)
        if (ui.peers.isEmpty()) {
            Text("No nearby devices yet. Ask the other device to open LocalDrop on the same Wi-Fi or hotspot.")
        } else {
            ui.peers.forEach { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = ui.selectedPeerId == peer.deviceId,
                            enabled = !ui.busy,
                            onClick = { stack.send.selectPeer(peer.deviceId) }
                        )
                        .padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(peer.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${peer.deviceType} · ${peer.status}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (ui.selectedPeerId == peer.deviceId) "✓" else "")
                }
            }
        }
        Text("File", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ui.busy) {
                Text("Choose file…")
            }
            OutlinedButton(onClick = { stack.send.setFile(null) }, enabled = !ui.busy) {
                Text("Clear")
            }
        }
        Text(if (ui.fileName.isEmpty()) "No file chosen."
        else "${ui.fileName} (${SendUiController.formatBytes(ui.fileSizeBytes)})")
        Text("Receiver code", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ask the receiver to open LocalDrop and read out the 6-digit code, then type it here. " +
                "The code is single-use and expires.",
            style = MaterialTheme.typography.bodySmall)
        TextField(
            value = ui.code,
            onValueChange = { stack.send.setCode(it) },
            label = { Text("6-digit code") },
            singleLine = true,
            enabled = !ui.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { stack.send.startSend() },
                enabled = ui.canSend) {
                Text("Send")
            }
            OutlinedButton(
                onClick = { stack.send.cancelSend() },
                enabled = ui.busy) {
                Text("Cancel")
            }
        }
        Text("Transfer", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(
            progress = { (ui.progressPercent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Transfer progress" })
        Text(ui.progressText.ifEmpty { "—" })
        Text("${ui.speedText.ifEmpty { "—" }}   ${ui.etaText.ifEmpty { "ETA —" }}")
        Text(ui.statusText, style = MaterialTheme.typography.bodyMedium)
        if (ui.resultText.isNotEmpty()) Text(ui.resultText)
        if (ui.phase == SendPhase.Succeeded) {
            // Accurate: TransportComplete reaches Succeeded ONLY after the
            // receiver confirms SHA-256 verification + finalization. Failed
            // or unconfirmed transfers never land here (Failed/Cancelled).
            Text("Verified by the receiver (SHA-256 match).",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReceiveScreen(stack: MainActivity.AppStack, ui: com.localdrop.transfer.ReceiveUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Receive", style = MaterialTheme.typography.titleMedium)
        Text(
            if (ui.running) "Receiving is on. Nearby senders can find this device."
            else "Starting…",
            style = MaterialTheme.typography.bodySmall)
        Text("Your code", style = MaterialTheme.typography.titleMedium)
        Text(
            "Read this code out to the sender. It is single-use and expires — " +
                "nothing is authorized until the sender types it and this device accepts it.",
            style = MaterialTheme.typography.bodySmall)
        Text(
            ui.codeDisplay.ifEmpty { "—" },
            style = MaterialTheme.typography.headlineMedium)
        Text(ui.codeNote, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { stack.receive.newCode() }) {
            Text("New code")
        }
        Text("Incoming transfer", style = MaterialTheme.typography.titleMedium)
        if (ui.incomingName.isEmpty()) {
            Text("No incoming transfer.")
        } else {
            Text("${ui.incomingName} (${SendUiController.formatBytes(ui.incomingSizeBytes)})")
            Text("From: ${ui.incomingPeer}", style = MaterialTheme.typography.bodySmall)
            val p = ui.progress
            if (p != null) {
                LinearProgressIndicator(
                    progress = { (p.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Incoming transfer progress" })
                Text("${SendUiController.formatBytes(p.transferredBytes)} of " +
                    "${SendUiController.formatBytes(p.totalBytes)} (${"%.1f".format(p.percent)}%)")
            }
            OutlinedButton(
                onClick = { stack.receive.cancelIncoming() },
                enabled = ui.phase == ReceivePhase.Receiving) {
                Text("Cancel transfer")
            }
        }
        Text(ui.statusText, style = MaterialTheme.typography.bodyMedium)
        if (ui.resultText.isNotEmpty()) Text(ui.resultText)
        Text("Files are saved to app storage on this device.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// Resolves a picker Uri to a TransferSource. Metadata is read here (UI
// thread, one small query); byte streaming happens later on a worker thread.
private fun resolvePickedFile(resolver: ContentResolver, uri: Uri): TransferSource {
    var name: String? = null
    var size: Long = -1L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIdx >= 0) name = cursor.getString(nameIdx)
            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
        }
    }
    return ContentUriTransferSource(
        resolver, uri,
        displayName = name?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("The selected document has no file name."),
        sizeBytes = size)
}
