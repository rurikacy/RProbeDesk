import dev.rprobedesk.NativeCore;
import java.util.Arrays;

/** Synchronize process-memory sampling around a fully populated 32-capture workload. */
public class LogFootprintBench {
    public static void main(String[] args) throws Exception {
        long[] ids = new long[32];
        byte[] chunk = new byte[16 * 4096];
        Arrays.fill(chunk, (byte)'x');
        for (int i = 4095; i < chunk.length; i += 4096) chunk[i] = '\n';
        for (int i = 0; i < ids.length; i++) ids[i] = NativeCore.openLog();
        try {
            // Warm JNI and the sampler before measuring. No JVM log rows are retained.
            NativeCore.appendLog(ids[0], chunk, chunk.length);
            System.out.println("empty"); System.out.flush(); System.in.read();
            for (long id : ids)
                for (int batch = 0; batch < 94; batch++) NativeCore.appendLog(id, chunk, chunk.length);
            System.out.println("filled"); System.out.flush(); System.in.read();
        } finally {
            for (long id : ids) NativeCore.closeLog(id);
        }
    }
}
