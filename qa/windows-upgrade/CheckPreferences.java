import dev.rprobedesk.DeskPreferences;
import java.io.File;

/** Run against the installed application's JARs before and after an MSI upgrade. */
public final class CheckPreferences {
    public static void main(String[] args) {
        File isolatedData = new File(args[1], "RProbeDesk");
        DeskPreferences preferences = new DeskPreferences(isolatedData);
        if (args[0].equals("seed")) {
            if (!preferences.getDevices().isEmpty() || !preferences.getCommands().isEmpty()) {
                throw new IllegalStateException("Use an empty QA profile");
            }
            preferences.saveDevice(null, "MSI 升级测试", "127.0.0.1", 5591);
            preferences.saveCommand(null, "读取型号", "getprop ro.product.model");
        }
        if (preferences.getDevices().size() != 1 ||
            !preferences.getDevices().get(0).getName().equals("MSI 升级测试") ||
            preferences.getCommands().size() != 1 ||
            !preferences.getCommands().get(0).getCommand().equals("getprop ro.product.model")) {
            throw new AssertionError("Saved device or favorite did not survive");
        }
        System.out.println("Verified the device record and favorite using installed RProbeDesk classes");
    }
}
