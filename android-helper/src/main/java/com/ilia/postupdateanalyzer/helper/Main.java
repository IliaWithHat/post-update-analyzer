package com.ilia.postupdateanalyzer.helper;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runs on the phone via {@code CLASSPATH=/data/local/tmp/pua-helper.jar app_process /system/bin <this class>}.
 * <p>
 * Prints one line per package: {@code PUA|<base64 of UTF-8 JSON>}, framed by {@code PUA-BEGIN|<count>} and {@code PUA-END}.
 * Base64 keeps the output safe from console encodings and CR/LF translation on old adb versions.
 * <p>
 * Arguments: {@code --user <id>} (default 0), {@code --icon-size <px>} (default 96), {@code --no-icons}.
 */
public final class Main {

    private static final int MATCH_DISABLED_COMPONENTS = 0x00000200;
    private static final int MATCH_UNINSTALLED_PACKAGES = 0x00002000;
    private static final int MATCH_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;

    private static final int FLAG_SYSTEM = 0x00000001;
    private static final int FLAG_UPDATED_SYSTEM_APP = 0x00000080;
    private static final int FLAG_INSTALLED = 0x00800000;

    private static final int MAX_LABEL_LENGTH = 200;

    private Main() {
    }

    public static void main(String[] args) {
        int userId = 0;
        int iconSize = 96;
        boolean icons = true;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--user".equals(arg) && i + 1 < args.length) {
                userId = Integer.parseInt(args[++i]);
            } else if ("--icon-size".equals(arg) && i + 1 < args.length) {
                iconSize = Math.max(16, Math.min(512, Integer.parseInt(args[++i])));
            } else if ("--no-icons".equals(arg)) {
                icons = false;
            }
        }

        PrintStream out = System.out;
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
            // Some drawables want a looper; it is fine if one already exists or cannot be created.
        }

        List<?> packages;
        try {
            packages = installedPackages(userId);
        } catch (Throwable t) {
            out.println("PUA-FATAL|" + encode(String.valueOf(t)));
            out.flush();
            System.exit(2);
            return;
        }

        out.println("PUA-BEGIN|" + packages.size());
        for (Object item : packages) {
            PackageInfo info = (PackageInfo) item;
            JSONObject json;
            try {
                json = describe(info, icons, iconSize);
            } catch (Throwable t) {
                json = new JSONObject();
                try {
                    json.put("packageName", info.packageName);
                    json.put("error", String.valueOf(t));
                } catch (Throwable ignored) {
                    continue;
                }
            }
            out.println("PUA|" + encode(json.toString()));
        }
        out.println("PUA-END");
        out.flush();
        System.exit(0);
    }

    private static List<?> installedPackages(int userId) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class).invoke(null, "package");
        Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
        Object packageManager = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);

        int flags = MATCH_UNINSTALLED_PACKAGES | MATCH_DISABLED_COMPONENTS | MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        Object slice;
        try {
            // Android 13+
            Method method = packageManager.getClass().getMethod("getInstalledPackages", long.class, int.class);
            slice = method.invoke(packageManager, (long) flags, userId);
        } catch (NoSuchMethodException e) {
            Method method = packageManager.getClass().getMethod("getInstalledPackages", int.class, int.class);
            slice = method.invoke(packageManager, flags, userId);
        }
        return (List<?>) slice.getClass().getMethod("getList").invoke(slice);
    }

    private static JSONObject describe(PackageInfo info, boolean icons, int iconSize) throws Exception {
        ApplicationInfo app = info.applicationInfo;
        JSONObject json = new JSONObject();
        json.put("packageName", info.packageName);
        json.put("versionCode", versionCode(info));
        if (info.versionName != null) {
            json.put("versionName", info.versionName);
        }
        json.put("firstInstallTime", info.firstInstallTime);
        json.put("lastUpdateTime", info.lastUpdateTime);
        if (app == null) {
            json.put("label", info.packageName);
            return json;
        }
        json.put("system", (app.flags & FLAG_SYSTEM) != 0);
        json.put("updatedSystem", (app.flags & FLAG_UPDATED_SYSTEM_APP) != 0);
        json.put("installed", (app.flags & FLAG_INSTALLED) != 0);
        json.put("enabled", app.enabled);
        if (app.sourceDir != null) {
            json.put("apkPath", app.sourceDir);
        }

        AssetManager assets = null;
        try {
            assets = assetsFor(app);
            Resources resources = assets == null ? null : new Resources(
                    assets,
                    Resources.getSystem().getDisplayMetrics(),
                    Resources.getSystem().getConfiguration());
            json.put("label", label(app, resources));
            if (icons) {
                String icon = icon(app, resources, iconSize);
                if (icon != null) {
                    json.put("icon", icon);
                }
            }
        } finally {
            if (assets != null) {
                assets.close();
            }
        }
        return json;
    }

    private static long versionCode(PackageInfo info) {
        try {
            // Android 9+
            return (Long) PackageInfo.class.getMethod("getLongVersionCode").invoke(info);
        } catch (Throwable t) {
            return info.versionCode;
        }
    }

    private static AssetManager assetsFor(ApplicationInfo app) {
        if (app.sourceDir == null) {
            return null;
        }
        try {
            Constructor<AssetManager> constructor = AssetManager.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            AssetManager assets = constructor.newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            addAssetPath.invoke(assets, app.sourceDir);
            // Icons can live in density splits (split_config.xxhdpi.apk), so add them too.
            String[] splits = splitSourceDirs(app);
            if (splits != null) {
                for (String split : splits) {
                    addAssetPath.invoke(assets, split);
                }
            }
            return assets;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String[] splitSourceDirs(ApplicationInfo app) {
        try {
            return (String[]) ApplicationInfo.class.getField("splitSourceDirs").get(app);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String label(ApplicationInfo app, Resources resources) {
        CharSequence label = null;
        if (app.nonLocalizedLabel != null) {
            label = app.nonLocalizedLabel;
        } else if (app.labelRes != 0 && resources != null) {
            try {
                label = resources.getText(app.labelRes);
            } catch (Throwable ignored) {
                // fall through to package name
            }
        }
        String text = label == null ? "" : label.toString().trim();
        if (text.isEmpty()) {
            return app.packageName;
        }
        return text.length() > MAX_LABEL_LENGTH ? text.substring(0, MAX_LABEL_LENGTH) : text;
    }

    private static String icon(ApplicationInfo app, Resources resources, int size) {
        Drawable drawable = null;
        if (app.icon != 0 && resources != null) {
            try {
                drawable = resources.getDrawable(app.icon);
            } catch (Throwable ignored) {
                // use default icon below
            }
        }
        if (drawable == null) {
            try {
                drawable = Resources.getSystem().getDrawable(android.R.drawable.sym_def_app_icon);
            } catch (Throwable t) {
                return null;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
            return Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP);
        } finally {
            bitmap.recycle();
        }
    }

    private static String encode(String text) {
        return Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
}
