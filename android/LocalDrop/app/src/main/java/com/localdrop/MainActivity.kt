package com.localdrop

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
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
import com.localdrop.core.AppLogger
import com.localdrop.discovery.DeviceInfo
import com.localdrop.discovery.LanDiscovery
import com.localdrop.protocol.Protocol
import com.localdrop.security.AndroidKeyStoreDeviceIdentityStore
import com.localdrop.security.AppSecurityLogger
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
import java.io.File

// M07 UI closure: thin Compose screens over framework-free controllers.
// Screens render state and dispatch user actions ONLY: no sockets, no file
// I/O, no crypto, no orchestration here (UI/UX Spec §18). All transfer work
// runs on controller/coordinator worker threads; progress and outcomes are
// real engine events. No foreground service/widgets (M13) and no fake
// Verified state (M11 owns integrity verification).
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = androidx.lifecycle.ViewModelProvider(this)[AppStackViewModel::class.java]
        val stack: AppStack = try {
            viewModel.stack
        } catch (e: Exception) {
            setContent {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text("LocalDrop", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(8.dp))
                            Text("Could not open this device's identity. Reinstalling the app resets it.")
                        }
                    }
                }
            }
            return
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LocalDropApp(stack)
                }
            }
        }
    }

    // Production object graph. Plain holder (no ViewModel dependency);
    // the composable owns its lifetime via DisposableEffect and closes it
    // on dispose. Takes only ContentResolver + files dir (testable seam:
    // no Activity reference retained).
    class AppStack(resolver: ContentResolver, filesDir: File) {
        val contentResolver: ContentResolver = resolver
        private val appLogger = object : AppLogger {
            override fun d(tag: String, message: String) = Unit
            override fun i(tag: String, message: String) = Unit
            override fun w(tag: String, message: String) = Unit
            override fun e(tag: String, message: String, throwable: Throwable?) = Unit
        }
        private val logger = AppSecurityLogger(appLogger)
        private val store = AndroidKeyStoreDeviceIdentityStore()
        val device: DeviceInfo
        val destDir: File
        val sendDiscovery: LanDiscovery
        val sendCoordinator: SendCoordinator
        val send: SendUiController
        val receiveCoordinator: ReceiveCoordinator
        val receive: ReceiveUiController

        init {
            val rawName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android device"
            val name = rawName.take(Protocol.MAX_DEVICE_NAME)
            device = DeviceInfo(store.identity().deviceId, name, "android")
            destDir = File(filesDir, "LocalDrop").apply { mkdirs() }
            sendDiscovery = LanDiscovery(device)
            sendCoordinator = SendCoordinator(device, store, logger, sendDiscovery)
            val facade = object : SendUiController.SendCoordinatorFacade {
                override fun nearbyPeers() = sendCoordinator.nearbyPeers()
                override fun sendFile(
                    peerDeviceId: String, source: TransferSource, code: String,
                    isCancelled: () -> Boolean, onProgress: (TransferProgress) -> Unit
                ): TransferOutcome =
                    sendCoordinator.sendFile(peerDeviceId, source, code, isCancelled, onProgress)
            }
            send = SendUiController(facade)
            receiveCoordinator = ReceiveCoordinator(device, store, logger, destDir)
            receive = ReceiveUiController(receiveCoordinator)
        }
    }

    class AppStackViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
        val stack: AppStack by lazy { AppStack(application.contentResolver, application.filesDir) }
        private val starter: Thread
        private val poller: Thread

        init {
            starter = Thread({
                try { stack.sendCoordinator.start() } catch (e: Exception) { }
                try { stack.receive.start() } catch (e: Exception) { }
                stack.receive.attachProgress()
            }, "localdrop-ui-start").apply { isDaemon = true; start() }
            poller = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    try { stack.send.refreshPeers() } catch (e: Exception) { }
                    try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
                }
            }, "localdrop-ui-poll").apply { isDaemon = true; start() }
        }

        override fun onCleared() {
            super.onCleared()
            try { poller.interrupt() } catch (e: Exception) { }
            try { stack.send.close() } catch (e: Exception) { }
            try { stack.receive.close() } catch (e: Exception) { }
            try { stack.sendCoordinator.stop() } catch (e: Exception) { }
            try { starter.join(3000) } catch (e: Exception) { }
            try { poller.join(3000) } catch (e: Exception) { }
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
            Text("Integrity verification arrives in a later update (M11).",
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
