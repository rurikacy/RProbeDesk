package dev.rprobedesk.catalog;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Read the selected user's localized resources on the target, including adaptive and split APK icons. */
public final class AppCatalog {
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activity = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(activity);
        UserHandle user = UserHandle.getUserHandleForUid(Integer.parseInt(args[0]) * 100000);
        Context context = (Context) Context.class.getMethod("createPackageContextAsUser", String.class, int.class, UserHandle.class)
                .invoke(system, "android", 0, user);
        PackageManager pm = context.getPackageManager();
        java.util.List<ApplicationInfo> apps;
        if (args.length == 1) {
            apps = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
        } else {
            apps = new java.util.ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                try { apps.add(pm.getApplicationInfo(args[i], PackageManager.MATCH_DISABLED_COMPONENTS)); }
                catch (PackageManager.NameNotFoundException ignored) { /* Recently removed. */ }
            }
        }
        for (ApplicationInfo app : apps) {
            try {
                String label = app.loadLabel(pm).toString();
                String icon = "";
                try {
                    Drawable drawable = app.loadIcon(pm);
                    Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
                    drawable.setBounds(0, 0, 48, 48);
                    drawable.draw(new Canvas(bitmap));
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
                    icon = Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
                    bitmap.recycle();
                } catch (RuntimeException ignored) { /* Keep the label if a vendor icon cannot be rendered. */ }
                System.out.println(app.packageName + "\t" + Base64.encodeToString(label.getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP) + "\t" + icon);
            } catch (RuntimeException ignored) { /* The package may disappear during enumeration. */ }
        }
    }
}
