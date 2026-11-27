import dev.rprobedesk.*;
import java.awt.Frame;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.UUID;
import javax.swing.SwingUtilities;

/** Run the same real Logcat workload through each packaged workspace. */
public class WorkspaceLogBench {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("ADB PORT QA_PROFILE");
        if (!new File(args[2]).getCanonicalFile().equals(new File(System.getenv("LOCALAPPDATA")).getCanonicalFile()))
            throw new IllegalArgumentException("Isolated QA profile required");
        var session = SessionBench.<WindowsSession>await(c -> WindowsAdbKt.connectWindows(args[0],"127.0.0.1",Integer.parseInt(args[1]),c));
        var frame = new Frame();
        var host = new WindowsHost(frame);
        var prefs = new DeskPreferences(new File(args[2],"workspace"));
        WorkspaceModel[] work = new WorkspaceModel[1];
        try {
            var check = SessionBench.<ShellResult>await(c -> session.shell("getprop ro.kernel.qemu",null,c));
            if (!check.getStdout().trim().equals("1")) throw new IllegalStateException("Dedicated emulator required");
            for (var constructor : WorkspaceModel.class.getConstructors()) {
                if (constructor.getParameterCount() == 4) work[0]=(WorkspaceModel)constructor.newInstance(host,session,prefs,"bench");
                if (constructor.getParameterCount() == 5) work[0]=(WorkspaceModel)constructor.newInstance(host,session,prefs,"bench","bench");
            }
            if (work[0] == null) throw new IllegalStateException("No packaged workspace");
            var model=work[0];
            SwingUtilities.invokeAndWait(model::startLogs);
            var tag="RProbeBench";
            var marker=UUID.randomUUID().toString();
            long[] rows={0};
            boolean[] done={false};
            for (int i=0; i<20 && !done[0]; i++) {
                SessionBench.<ShellResult>await(c -> session.shell("log -t "+tag+" ready-"+marker,null,c));
                Thread.sleep(250);
                SwingUtilities.invokeAndWait(() -> {
                    done[0]=model.getLogs().stream().anyMatch(row -> row.getText().contains("ready-"+marker));
                    rows[0]=model.getDropped()+model.getLogs().size();
                });
            }
            if (!done[0]) throw new IllegalStateException("Logcat was not ready");
            var os=ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
            long cpu=os.getProcessCpuTime(), start=System.nanoTime();
            var reply=SessionBench.<ShellResult>await(c -> session.shell(
                    "awk 'BEGIN{for(i=0;i<24000;i++)print \"QA frame \"i}' | log -t "+tag+"; log -t "+tag+" end-"+marker,null,c));
            if (!Integer.valueOf(0).equals(reply.getExitCode())) throw new IllegalStateException("Log producer failed");
            long[] observed={0};
            do {
                Thread.sleep(40);
                SwingUtilities.invokeAndWait(() -> {
                    done[0]=model.getLogs().stream().anyMatch(row -> row.getText().contains("end-"+marker));
                    observed[0]=model.getDropped()+model.getLogs().size()-rows[0];
                });
            } while (!done[0] && System.nanoTime()-start < 30_000_000_000L);
            if (!done[0] || observed[0]<20000) throw new IllegalStateException("Incomplete workload rows="+observed[0]);
            long wall=System.nanoTime()-start, used=os.getProcessCpuTime()-cpu;
            System.out.printf("requested=24000 received_including_system=%d wall_ms=%d cpu_ms=%d one_core_percent=%.1f%n",
                    observed[0],wall/1_000_000,used/1_000_000,100.0*used/wall);
        } finally {
            if (work[0]!=null) SwingUtilities.invokeAndWait(work[0]::close);
            session.close();
            frame.dispose();
        }
    }
}
