import dev.rprobedesk.NativeCore;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import com.sun.management.OperatingSystemMXBean;

/** Replay the same threadtime records through the packaged v1.2.1 and v1.2.2 JNI interfaces. */
public class LogCaptureBench {
    private static final int ROWS = 24000;
    public static void main(String[] args) throws Exception {
        String[] rows = new String[ROWS];
        for (int i = 0; i < ROWS; i++)
            rows[i] = "11-27 13:13:13.123  123  456 I 中文: 💻 record " + i;
        Method append = null;
        try { append = NativeCore.class.getMethod("appendLog", long.class, byte[].class, int.class); }
        catch (NoSuchMethodException baseline) { /* v1.2.1 accepts batches of UTF-16 strings. */ }
        Method open = null, read = null, close = null;
        boolean paged = false;
        if (append != null) {
            open = NativeCore.class.getMethod("openLog");
            try { read = NativeCore.class.getMethod("readLogPage", long.class, long.class); paged = true; }
            catch (NoSuchMethodException baseline) { read = NativeCore.class.getMethod("readLog", long.class, long.class); }
            close = NativeCore.class.getMethod("closeLog", long.class);
        }
        var os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        byte[] input = (String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8);
        for (int trial = 0; trial < 4; trial++) {
            long start = System.nanoTime(), cpu = os.getProcessCpuTime();
            int calls = 0, count = 0, maxPacket = 0;
            long heapPeak = 0;
            if (append == null) {
                for (int pos = 0; pos < ROWS; pos += 32) {
                    var batch = Arrays.copyOfRange(rows, pos, Math.min(pos + 32, ROWS));
                    int[] fields = NativeCore.logFields(batch);
                    calls++;
                    for (int j = 0; j < batch.length; j++) {
                        if (fields[j*5] != 'I' || !batch[j].substring(fields[j*5+3],fields[j*5+4]).equals("中文"))
                            throw new IllegalStateException("Invalid fields");
                        count++;
                    }
                }
            } else {
                long id = (long)open.invoke(null), cursor = 0;
                calls++;
                try {
                    for (int pos=0; pos<input.length; pos+=32768) {
                        var chunk = Arrays.copyOfRange(input,pos,Math.min(pos+32768,input.length));
                        append.invoke(null,id,chunk,chunk.length);
                        calls++;
                        long newest;
                        do {
                            var packet = ByteBuffer.wrap((byte[])read.invoke(null,id,cursor));
                            calls++;
                            maxPacket = Math.max(maxPacket, packet.remaining());
                            if (!packet.hasRemaining()) break;
                            cursor = packet.getLong();
                            packet.getLong();
                            newest = paged ? packet.getLong() : cursor;
                            while (packet.hasRemaining()) {
                                int size=packet.getInt(), level=packet.getInt();
                                packet.getInt(); packet.getInt();
                                int tagStart=packet.getInt(), tagEnd=packet.getInt();
                                packet.position(packet.position()+16);
                                String line=new String(packet.array(),packet.position(),size,StandardCharsets.UTF_8);
                                packet.position(packet.position()+size);
                                if (level != 'I' || !line.substring(tagStart,tagEnd).equals("中文"))
                                    throw new IllegalStateException("Invalid fields");
                                count++;
                            }
                            heapPeak = Math.max(heapPeak, Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
                        } while (cursor < newest);
                    }
                } finally { close.invoke(null,id); calls++; }
            }
            if (count != ROWS) throw new IllegalStateException("Lost records: " + count);
            if (trial>0) System.out.printf("trial=%d rows=%d jni=%d elapsed_us=%d cpu_us=%d max_packet=%d sampled_heap=%d%n",
                trial,count,calls,(System.nanoTime()-start)/1000,(os.getProcessCpuTime()-cpu)/1000,maxPacket,heapPeak);
        }
    }
}
