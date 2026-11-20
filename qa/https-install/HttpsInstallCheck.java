import com.sun.net.httpserver.*;
import dev.rprobedesk.*;
import java.awt.Frame;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.ssl.*;
import javax.swing.SwingUtilities;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.flow.StateFlowKt;

/** Exercises the packaged app's TLS download, manifest, question and package-manager result on a disposable emulator. */
public class HttpsInstallCheck {
    static <T> T await(Function1<Continuation<? super T>, Object> action) throws InterruptedException {
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, c) -> action.invoke(c));
    }

    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static void until(String name, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out: " + name);
            Thread.sleep(30);
        }
    }

    static String shell(WindowsSession session, String command) throws Exception {
        ShellResult result = await(c -> session.shell(command, null, c));
        check(Integer.valueOf(0).equals(result.getExitCode()), result.getStderr());
        return result.getStdout().trim();
    }

    static String version(WindowsSession session, int user, String pkg) throws Exception {
        return shell(session, "pm list packages --show-versioncode --user " + user + " " + pkg);
    }

    static HttpsServer server(Path dir, char[] password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(dir.resolve("tls.p12"))) { store.load(in, password); }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        for (String name : new String[]{"v1", "v2", "wrong"}) {
            server.createContext("/" + name + ".apk", raw -> {
                try (HttpsExchange e = (HttpsExchange) raw) {
                    Path apk = dir.resolve(name + ".apk");
                    e.getResponseHeaders().set("Content-Type", "application/vnd.android.package-archive");
                    e.sendResponseHeaders(200, Files.size(apk));
                    Files.copy(apk, e.getResponseBody());
                    System.out.println(e.getSSLSession().getProtocol() + " GET " + name + ".apk " + Files.size(apk));
                }
            });
        }
        server.createContext("/downgrade", raw -> {
            try (HttpsExchange e = (HttpsExchange)raw) {
                e.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1.apk");
                e.sendResponseHeaders(302, -1);
            }
        });
        server.start();
        return server;
    }

    static void submit(WorkspaceModel m, String url) throws Exception {
        SwingUtilities.invokeAndWait(() -> m.installApk(url));
        until("APK response", () -> m.getQuestion() != null || !m.getBusy());
    }

    static void decide(WorkspaceModel m, String pkg, int version, boolean accept) throws Exception {
        WorkQuestion question = m.getQuestion();
        check(question != null && question.getDetail().contains(pkg) && question.getDetail().contains("(" + version + ")"),
            "Missing package/version question: " + m.getStatus());
        SwingUtilities.invokeAndWait(() -> m.respond(accept));
        until("install completion", () -> !m.getBusy());
    }

    static void invalidDownloads(Path dir, String origin, String shortBytes) throws Exception {
        Path settings = Files.createDirectory(dir.resolve("settings"));
        Path cache = Files.createDirectory(dir.resolve("cache"));
        var foreground = StateFlowKt.MutableStateFlow(true);
        var inactive = StateFlowKt.MutableStateFlow(false);
        HostBridge host = (HostBridge) Proxy.newProxyInstance(HostBridge.class.getClassLoader(), new Class<?>[]{HostBridge.class},
            (ignored, method, values) -> switch (method.getName()) {
                case "getDesktop" -> true;
                case "getSettingsDir" -> settings.toFile();
                case "getCacheDir" -> cache.toFile();
                case "getForeground" -> foreground;
                case "getMirrorActive", "getReducedMotion" -> inactive;
                default -> throw new AssertionError("Unexpected host operation: " + method);
            });
        AtomicInteger deviceCalls = new AtomicInteger();
        DeviceSession session = (DeviceSession) Proxy.newProxyInstance(DeviceSession.class.getClassLoader(), new Class<?>[]{DeviceSession.class},
            (ignored, method, values) -> {
                if (method.getName().equals("getSerial")) return "Q05-local-check";
                deviceCalls.incrementAndGet();
                throw new AssertionError("Invalid download reached device: " + method);
            });
        WorkspaceModel model = new WorkspaceModel(host, session, new DeskPreferences(settings.toFile()), "Q05");
        try {
            for (String name : new String[]{"html", "truncated"}) {
                submit(model, origin + "/" + name + ".apk");
                WorkQuestion consent = model.getQuestion();
                check(consent != null && consent.getTitle().equals("允许明文 HTTP 下载？") &&
                    consent.getAction().equals("继续下载") && consent.getDetail().contains(origin.substring("http://".length())),
                    "Expected only the HTTP consent before download: " + model.getStatus());
                try (var files = Files.list(cache)) {
                    check(files.anyMatch(p -> p.getFileName().toString().endsWith(".lease")), "No active staging lease");
                }
                SwingUtilities.invokeAndWait(() -> model.respond(true));
                until(name + " error or unexpected confirmation", () -> !model.getBusy() ||
                    (model.getQuestion() != null && model.getQuestion() != consent));
                check(model.getQuestion() == null && !model.getBusy() && model.getProblem(), "Reached APK confirmation or reported success");
                String expected = name.equals("html") ? "不是有效的 APK" : shortBytes + " / 4096";
                check(model.getStatus().contains(expected), "Unexpected " + name + " error: " + model.getStatus());
                check(deviceCalls.get() == 0, "Device operation count: " + deviceCalls);
                try (var files = Files.list(cache)) {
                    check(files.allMatch(p -> p.getFileName().toString().equals(".rprobe-tasks.lock")), "Staging data or lease remains");
                }
                System.out.println(name + ": " + model.getStatus() + " | install prompts=0, device calls=0, staging leftovers=0");
            }
        } finally { SwingUtilities.invokeAndWait(model::close); }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        if (args[1].equals("invalid-downloads")) {
            invalidDownloads(dir, args[2], args[3]);
            return;
        }
        String pkg = args[2];
        check(pkg.matches("dev\\.rprobedesk\\.qa\\.tlsfixture\\.x[a-f0-9]{12}"), "Unexpected QA package");
        char[] password = System.getenv("RPROBE_QA_PASS").toCharArray();
        System.setProperty("javax.net.ssl.trustStore", dir.resolve("trust.p12").toString());
        System.setProperty("javax.net.ssl.trustStorePassword", new String(password));
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        HttpsServer server = server(dir, password);
        Frame frame = new Frame();
        WindowsHost host = new WindowsHost(frame);
        check(host.getSettingsDir().toPath().toAbsolutePath().startsWith(dir), "Isolated LOCALAPPDATA required");
        WindowsSession session = null;
        WorkspaceModel m = null;
        int originalUser = -1;
        try {
            session = await(c -> host.connect("127.0.0.1", Integer.parseInt(args[1]), c));
            check("1".equals(shell(session, "getprop ro.kernel.qemu")), "Disposable emulator required");
            int user = Integer.parseInt(shell(session, "am get-current-user"));
            check(version(session, user, pkg).isEmpty(), "Test package already installed");
            originalUser = user;
            m = new WorkspaceModel(host, session, new DeskPreferences(host.getSettingsDir()), "TLS QA emulator");
            String base = "https://127.0.0.1:" + server.getAddress().getPort();

            submit(m, "https://localhost:" + server.getAddress().getPort() + "/v1.apk");
            check(m.getQuestion() == null && m.getProblem() && m.getStatus().contains("SSL"), "Hostname mismatch was not rejected: " + m.getStatus());
            System.out.println("Hostname mismatch rejected: " + m.getStatus());
            submit(m, base + "/downgrade");
            check(m.getQuestion() == null && m.getProblem() && m.getStatus().contains("HTTP"), "HTTPS downgrade was not rejected");
            System.out.println("HTTPS -> HTTP refused");

            submit(m, base + "/v1.apk");
            decide(m, pkg, 1, false);
            check(version(session, user, pkg).isEmpty(), "Cancelled installation modified the emulator");
            System.out.println("Cancel preserved absent package");
            submit(m, base + "/v1.apk");
            decide(m, pkg, 1, true);
            check(!m.getProblem() && version(session, user, pkg).endsWith("versionCode:1"), "v1 failed: " + m.getStatus());
            submit(m, base + "/v2.apk");
            check(m.getQuestion() != null && "更新".equals(m.getQuestion().getAction()), "Update not identified");
            decide(m, pkg, 2, true);
            check(!m.getProblem() && version(session, user, pkg).endsWith("versionCode:2"), "Same-signature update failed: " + m.getStatus());
            System.out.println("Same signer: device version 1 -> 2");

            submit(m, base + "/wrong.apk");
            decide(m, pkg, 3, true);
            check(m.getProblem() && m.getStatus().contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"), "Missing signature rejection: " + m.getStatus());
            check(version(session, user, pkg).endsWith("versionCode:2"), "Failed update replaced the original");
            System.out.println("Different signer: INSTALL_FAILED_UPDATE_INCOMPATIBLE; device version remains 2");
        } finally {
            if (m != null) m.close();
            if (session != null) {
                try {
                    if (originalUser >= 0) {
                        if (!version(session, originalUser, pkg).isEmpty()) shell(session, "pm uninstall --user " + originalUser + " " + pkg);
                        check(version(session, originalUser, pkg).isEmpty(), "Temporary fixture remains installed");
                    }
                } finally { session.close(); }
            }
            server.stop(0);
            frame.dispose();
        }
    }
}

