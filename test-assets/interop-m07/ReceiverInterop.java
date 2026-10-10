// M07 temp interop driver (NOT repo code): hosts the production Android
// (JVM) receiver stack — ControlServer + AuthManager + TransactionManager +
// TransactionAuthorizer + ReceiverEngine — over loopback. The C#
// SenderInteropTests drives mutual auth, submits the transaction code, then
// streams via the production Windows SenderEngine. This driver awaits
// transport completion and stream-compares the received file against the
// expected source file. Loopback only, no Internet.
import com.localdrop.discovery.DeviceInfo;
import com.localdrop.discovery.SystemDiscoveryClock;
import com.localdrop.network.ControlChannelListener;
import com.localdrop.network.ControlServer;
import com.localdrop.security.AuthManager;
import com.localdrop.security.AuthorizedSessionStore;
import com.localdrop.security.ReceiverTransaction;
import com.localdrop.security.RecordingSecurityLogger;
import com.localdrop.security.SoftwareDeviceIdentityStore;
import com.localdrop.security.TransactionAuthorizer;
import com.localdrop.security.TransactionManager;
import com.localdrop.transfer.ReceiverEngine;
import com.localdrop.transfer.TransferMetrics;
import com.localdrop.transfer.TransferOutcome;
import com.localdrop.transfer.TransferRequestHandler;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import kotlin.jvm.functions.Function0;

public class ReceiverInterop {
    public static void main(String[] args) throws Exception {
        // args: <expectedFile> <destDir> <infoFile>
        String expectedFile = args[0];
        File destDir = new File(args[1]);
        Path infoFile = Path.of(args[2]);
        destDir.mkdirs();

        SoftwareDeviceIdentityStore store = new SoftwareDeviceIdentityStore();
        DeviceInfo device = new DeviceInfo(
            store.identity().getDeviceId(), "JvmReceiver", "android", Collections.emptyList());
        RecordingSecurityLogger log = new RecordingSecurityLogger();
        AuthManager serverAuth = new AuthManager(
            device, store, log, 10000L, SystemDiscoveryClock.INSTANCE, null, null);
        TransactionManager txns = new TransactionManager(
            SystemDiscoveryClock.INSTANCE, 300000L, log);
        AuthorizedSessionStore sessions = new AuthorizedSessionStore(SystemDiscoveryClock.INSTANCE);
        ReceiverEngine engine = new ReceiverEngine(
            device, serverAuth, sessions, destDir, log, null, 0, 30000L, 500L, 30000L);
        TransferRequestHandler handler = engine.createHandler();
        TransactionAuthorizer authorizer = new TransactionAuthorizer(
            serverAuth, txns, sessions, log, device.getDeviceId(), handler);
        serverAuth.setInner(authorizer);
        final AuthManager authRef = serverAuth;
        ControlServer server = new ControlServer(device, 0, 5000L,
            new Function0<ControlChannelListener>() {
                @Override public ControlChannelListener invoke() { return authRef; }
            });
        server.start();
        engine.start();
        int exit = 1;
        try {
            ReceiverTransaction txn = txns.createTransaction();
            String info = server.localPort() + "\n" + engine.localPort() + "\n"
                + device.getDeviceId() + "\n" + txn.getCode() + "\n";
            Files.writeString(infoFile, info);
            System.out.println("[driver] controlPort=" + server.localPort()
                + " dataPort=" + engine.localPort() + " device=" + device.getDeviceId());
            System.out.flush();
            TransferOutcome outcome = engine.waitForAnyOutcome(300000L);
            if (outcome instanceof TransferOutcome.TransportComplete done) {
                File[] files = destDir.listFiles();
                File received = null;
                if (files != null) {
                    for (File f : files) {
                        if (!f.getName().endsWith(".localdrop-part")) { received = f; break; }
                    }
                }
                if (received == null) {
                    System.out.println("[driver] FAIL: no final file");
                } else if (!streamEqual(new File(expectedFile), received)) {
                    System.out.println("[driver] FAIL: byte MISMATCH");
                } else {
                    TransferMetrics m = done.getMetrics();
                    Double avg = done.getAverageBytesPerSec();
                    double mbps = avg != null ? avg / (1024.0 * 1024.0) : Double.NaN;
                    System.out.println("[driver] MATCH bytes=" + done.getBytesTransferred()
                        + " elapsedMs=" + m.getElapsedMs()
                        + " avgMBs=" + String.format("%.1f", mbps)
                        + " finalBlock=" + m.getFinalBlockSizeBytes()
                        + " adapts=" + m.getAdaptations().size()
                        + " stalls=" + m.getStallCount());
                    exit = 0;
                }
            } else {
                System.out.println("[driver] FAIL: outcome=" + outcome);
            }
        } finally {
            try { engine.stop(); } catch (Exception ignored) { }
            try { server.stop(); } catch (Exception ignored) { }
        }
        System.exit(exit);
    }

    private static boolean streamEqual(File a, File b) throws Exception {
        if (a.length() != b.length()) return false;
        byte[] ba = new byte[1 << 20];
        byte[] bb = new byte[1 << 20];
        try (InputStream fa = new FileInputStream(a); InputStream fb = new FileInputStream(b)) {
            while (true) {
                int na = fa.read(ba);
                int nb = fb.read(bb);
                if (na != nb) return false;
                if (na < 0) return true;
                for (int i = 0; i < na; i++) {
                    if (ba[i] != bb[i]) return false;
                }
            }
        }
    }
}
