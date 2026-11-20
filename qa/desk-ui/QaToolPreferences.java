import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/** Process-only Java preferences. Expose two test-owned files so the driver can verify the selected tools. */
public class QaToolPreferences implements PreferencesFactory {
    private static final Path dir = Path.of(System.getenv("RPROBE_QA_PREFS"));
    private final Root root = new Root(null, "");
    public QaToolPreferences() throws IOException {
        Files.writeString(dir.resolve("factory-pid"), String.valueOf(ProcessHandle.current().pid()));
    }
    public Preferences userRoot() { return root; }
    public Preferences systemRoot() { return root; }

    private static class Root extends AbstractPreferences {
        private final Map<String, String> memory = new HashMap<>();
        Root(Root parent, String name) { super(parent, name); }
        private Path fixture(String key) {
            return absolutePath().equals("/dev/rprobedesk/tools") && (key.equals("adb") || key.equals("scrcpy"))
                    ? dir.resolve(key + ".txt") : null;
        }
        protected String getSpi(String key) {
            Path entry = fixture(key);
            try { return entry != null && Files.exists(entry) ? Files.readString(entry) : memory.get(key); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }
        protected void putSpi(String key, String val) {
            Path entry = fixture(key);
            try { if (entry != null) Files.writeString(entry, val); else memory.put(key, val); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }
        protected void removeSpi(String key) { memory.remove(key); }
        protected void removeNodeSpi() {}
        protected Root childSpi(String name) { return new Root(this, name); }
        protected String[] keysSpi() { return memory.keySet().toArray(String[]::new); }
        protected String[] childrenNamesSpi() { return new String[0]; }
        protected void syncSpi() {}
        protected void flushSpi() {}
    }
}
