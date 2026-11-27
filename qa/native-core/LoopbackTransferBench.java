import dev.rprobedesk.*;
import kotlin.Unit;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/** Run unchanged against both packaged adapters and the same marked loopback emulator. */
public final class LoopbackTransferBench {
    private static final class Progress {
        final long start = System.nanoTime();
        int calls;
        long first, last, longestGap;

        Unit record(long bytes) {
            calls++;
            if (bytes > 0) {
                long now = System.nanoTime();
                if (first == 0) first = now;
                if (last != 0) longestGap = Math.max(longestGap, now - last);
                last = now;
            }
            return Unit.INSTANCE;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Expected bundled adb path and optional trial count");
        int trials = args.length == 2 ? Integer.parseInt(args[1]) : 5;
        if (trials < 1 || trials > 5) throw new IllegalArgumentException("Use 1 to 5 trials");
        var session = SessionBench.<WindowsSession>await(c -> WindowsAdbKt.connectWindows(args[0], "127.0.0.1", 5591, c));
        String path = "/data/local/tmp/rprobe-transfer-qa-" + UUID.randomUUID();
        try {
            var marker = SessionBench.<ShellResult>await(c -> session.shell("cat /data/local/tmp/rprobedesk-qa-target", null, c));
            if (!marker.getStdout().trim().equals("RProbeDesk-qa-target-5590")) throw new IllegalStateException("Unexpected target");
            byte[] data = new byte[16 * 1024 * 1024];
            new Random(42).nextBytes(data);
            for (int trial = 0; trial <= trials; trial++) {
                var source = new ByteArrayInputStream(data);
                var output = new ByteArrayOutputStream(data.length);
                var send = new Progress();
                SessionBench.<Unit>await(c -> session.push(path, source, (long)data.length, send::record, c));
                var receive = new Progress();
                SessionBench.<Unit>await(c -> session.pull(path, output, receive::record, c));
                long finished = System.nanoTime();
                if (!Arrays.equals(data, output.toByteArray())) throw new AssertionError("Round trip mismatch");
                if (trial > 0) System.out.printf("trial=%d bytes=%d push_ms=%.3f pull_ms=%.3f push_progress=%d pull_progress=%d first_push_ms=%.3f first_pull_ms=%.3f max_push_gap_ms=%.3f max_pull_gap_ms=%.3f%n",
                    trial, data.length, (receive.start-send.start)/1e6, (finished-receive.start)/1e6, send.calls, receive.calls,
                    (send.first-send.start)/1e6, (receive.first-receive.start)/1e6, send.longestGap/1e6, receive.longestGap/1e6);
            }
        } finally {
            try { SessionBench.<ShellResult>await(c -> session.shell("rm -f " + path, null, c)); }
            finally { session.close(); }
        }
    }
}
