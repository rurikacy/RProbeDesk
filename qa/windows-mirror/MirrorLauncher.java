import dev.rprobedesk.ShellResult;
import dev.rprobedesk.WindowsHost;
import dev.rprobedesk.WindowsSession;
import java.awt.Frame;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/** Runs the packaged application's actual connection and mirror adapters for the opt-in Windows QA script. */
public class MirrorLauncher {
    interface Call<T> { Object invoke(Continuation<? super T> completion); }

    @SuppressWarnings("unchecked")
    static <T> T await(Call<T> call) throws Exception {
        var future = new CompletableFuture<T>();
        var immediate = call.invoke(new Continuation<T>() {
            public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            public void resumeWith(Object value) {
                try { ResultKt.throwOnFailure(value); future.complete((T) value); }
                catch (Throwable error) { future.completeExceptionally(error); }
            }
        });
        return immediate == IntrinsicsKt.getCOROUTINE_SUSPENDED() ? future.get(45, TimeUnit.SECONDS) : (T) immediate;
    }

    static long open(WindowsHost host, WindowsSession session, Path intended) throws Exception {
        MirrorLauncher.<Unit>await(c -> host.startMirror(session, c));
        return ProcessHandle.current().children().filter(p -> p.info().command()
            .map(exe -> Path.of(exe).toAbsolutePath().equals(intended)).orElse(false)).findFirst().orElseThrow().pid();
    }

    static void retained(WindowsHost host, WindowsSession session) throws Exception {
        for (int i = 0; i < 50 && host.getMirrorActive().getValue(); i++) Thread.sleep(100);
        if (host.getMirrorActive().getValue()) throw new IllegalStateException("Mirror stayed active after closing window");
        ShellResult result = await(c -> session.shell("echo retained-session", null, c));
        if (!"retained-session".equals(result.getStdout().trim())) throw new IllegalStateException("ADB session lost");
        System.out.println("RETAINED_SESSION=yes");
    }

    static void interact(WindowsHost host, WindowsSession session, Path intended, Path evidence) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("FIXTURE_SESSION=ready");
        for (String line; (line = input.readLine()) != null;) {
            switch (line) {
                case "open" -> {
                    System.out.println("START_MILLIS=" + System.currentTimeMillis());
                    System.out.println("SCRCPY_PID=" + open(host, session, intended));
                }
                case "closed" -> retained(host, session);
                case "portrait", "landscape" -> {
                    try (OutputStream out = Files.newOutputStream(evidence.resolve("target-" + line + ".png"))) {
                        MirrorLauncher.<Unit>await(c -> session.screenshot(out, c));
                    }
                    System.out.println("PNG=" + line);
                }
                case "done" -> { return; }
                default -> throw new IllegalArgumentException("Unexpected fixture command");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Frame frame = new Frame();
        WindowsHost host = new WindowsHost(frame);
        var intended = Path.of(args[0]).toRealPath();
        if (!Files.isSameFile(intended, Path.of(host.toolPath("scrcpy")))) throw new IllegalStateException("Unexpected scrcpy path");
        if (!Files.isSameFile(Path.of(args[1]), Path.of(host.toolPath("adb")))) throw new IllegalStateException("Unexpected adb path");
        int port = Integer.parseInt(args[2]);
        WindowsSession session = await(c -> host.connect("127.0.0.1", port, c));
        try {
            ShellResult emulator = await(c -> session.shell("getprop ro.kernel.qemu", null, c));
            if (!"1".equals(emulator.getStdout().trim())) throw new IllegalStateException("Disposable emulator required");
            if (args.length == 4) {
                interact(host, session, intended, Path.of(args[3]));
                return;
            }
            long pid = open(host, session, intended);
            System.out.println("SCRCPY_PID=" + pid);
            System.out.flush();
            if (System.in.read() < 0) return;
            retained(host, session);
        } finally { host.stopMirror(); session.close(); frame.dispose(); }
    }
}

