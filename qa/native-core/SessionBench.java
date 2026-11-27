import dev.rprobedesk.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.UUID;
import kotlin.Unit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import kotlin.ResultKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;



/** Compare the packaged adapters with identical inputs on two explicitly selected QA devices. */
public class SessionBench {
    interface Call<T> { Object invoke(Continuation<? super T> completion); }
    @SuppressWarnings("unchecked")
    static <T> T await(Call<T> action) throws Exception {
        var reply = new CompletableFuture<T>();
        var value = action.invoke(new Continuation<T>() {
            public CoroutineContext getContext() { return EmptyCoroutineContext.INSTANCE; }
            public void resumeWith(Object result) {
                try { ResultKt.throwOnFailure(result); reply.complete((T)result); }
                catch (Throwable e) { reply.completeExceptionally(e); }
            }
        });
        return value == IntrinsicsKt.getCOROUTINE_SUSPENDED() ? reply.get(30, TimeUnit.SECONDS) : (T)value;
    }

    static long ms(long start) { return (System.nanoTime() - start) / 1_000_000; }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("ADB IP PORT SECOND_IP SECOND_PORT");
        String adb = args[0];
        int port = Integer.parseInt(args[2]);
        WindowsSession first = SessionBench.await(c -> WindowsAdbKt.connectWindows(adb, args[1], port, c));
        String file = "/data/local/tmp/rprobe-bench-" + UUID.randomUUID();
        try {
            WindowsSession second = SessionBench.await(c -> WindowsAdbKt.connectWindows(adb, args[3], Integer.parseInt(args[4]), c));
            try {
                for (var session : new WindowsSession[]{first, second}) {
                    ShellResult probe = SessionBench.await(c -> session.shell("getprop ro.product.model", null, c));
                    if (probe.getStdout().isBlank()) throw new IllegalStateException("Missing model");
                }
                System.out.println("two_devices_ready");
                var threads = ManagementFactory.getThreadMXBean();
                System.out.println("two_device_java_threads=" + threads.getThreadCount());
                threads.resetPeakThreadCount();
                var callers = java.util.concurrent.Executors.newFixedThreadPool(6);
                try {
                    var futures = new java.util.ArrayList<java.util.concurrent.Future<ShellResult>>();
                    for (int lane = 0; lane < 6; lane++) {
                        var device = (lane % 2 == 0) ? first : second;
                        futures.add(callers.submit(() -> SessionBench.<ShellResult>await(c -> device.shell("sleep 1; echo ready", null, c))));
                    }
                    for (var future : futures)
                        if (!future.get(10, TimeUnit.SECONDS).getStdout().trim().equals("ready"))
                            throw new IllegalStateException("Incomplete concurrent shell");
                    System.out.println("six_shell_peak_java_threads=" + threads.getPeakThreadCount());
                } finally { callers.shutdownNow(); }
                var frame = new java.awt.Frame();
                try {
                    var host = new WindowsHost(frame);
                    long begin = System.nanoTime();
                    var firstSeen = new java.util.concurrent.atomic.AtomicLong(-1);
                    var seen = SessionBench.<java.util.List<DiscoveredDevice>>await(c -> host.discover(rows -> {
                        if (!rows.isEmpty()) firstSeen.compareAndSet(-1, ms(begin));
                        return Unit.INSTANCE;
                    }, c));
                    System.out.printf("discovery_first_ms=%d discovery_complete_ms=%d candidates=%d%n", firstSeen.get(), ms(begin), seen.size());
                } finally { frame.dispose(); }
                byte[] bytes = new byte[4 * 1024 * 1024];
                new java.util.Random(42).nextBytes(bytes);
                for (int trial = 1; trial <= 3; trial++) {
                    long start = System.nanoTime();
                    SessionBench.<Unit>await(c -> first.push(file, new ByteArrayInputStream(bytes), (long)bytes.length, n -> Unit.INSTANCE, c));
                    long pushMs = ms(start);
                    var out = new ByteArrayOutputStream(bytes.length);
                    start = System.nanoTime();
                    SessionBench.<Unit>await(c -> first.pull(file, out, n -> Unit.INSTANCE, c));
                    long pullMs = ms(start);
                    if (!Arrays.equals(bytes, out.toByteArray())) throw new IllegalStateException("Transfer mismatch");
                    System.out.printf("trial=%d bytes=%d push_ms=%d pull_ms=%d%n", trial, bytes.length, pushMs, pullMs);
                }
                int[] parsed = {0};
                var bean = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
                long cpu = bean.getProcessCpuTime();
                long start = System.nanoTime();
                var result = SessionBench.<ShellResult>await(c -> first.shell(
                    "awk 'BEGIN{for(i=0;i<24000;i++)print \"11-27 12:30:00.001  123  456 I RProbeBench: row \"i}'", (line, err) -> {
                        if (!err && "RProbeBench".equals(WorkspacesDataKt.parseLog(line, null).getTag())) parsed[0]++;
                        return Unit.INSTANCE;
                    }, c));
                if (!Integer.valueOf(0).equals(result.getExitCode()) || parsed[0] != 24000)
                    throw new IllegalStateException("Incomplete log workload " + parsed[0]);
                System.out.printf("log_rows=%d elapsed_ms=%d process_cpu_ms=%d%n", parsed[0], ms(start),
                    (bean.getProcessCpuTime()-cpu)/1_000_000);
            } finally { second.close(); }
        } finally {
            try { SessionBench.<ShellResult>await(c -> first.shell("rm -f " + file, null, c)); }
            finally { first.close(); }
        }
        long start = System.nanoTime();
        var fresh = SessionBench.<WindowsSession>await(c -> WindowsAdbKt.connectWindows(adb, args[1], port, c));
        try {
            if (!SessionBench.<ShellResult>await(c -> fresh.shell("echo verified",null,c)).getStdout().trim().equals("verified"))
                throw new IllegalStateException("Reconnect failed");
            System.out.printf("manual_reopen_ms=%d%n", ms(start));
        } finally { fresh.close(); }
    }
}
