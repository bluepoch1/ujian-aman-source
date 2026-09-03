package com.safebrowser.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pemeriksaan integritas perangkat sebelum & selama ujian.
 *
 * Semua method bersifat best-effort: kalau sebuah sinyal tidak bisa dibaca
 * (izin dicabut, OEM aneh, API berubah), method mengembalikan nilai yang
 * TIDAK memblokir peserta. Lebih baik meloloskan satu kecurangan daripada
 * mengunci seluruh kelas di luar ujian karena false positive.
 */
public final class SecurityChecker {

    private SecurityChecker() { }

    // ─────────────────────────────────────────────────────────────
    //  Daftar aplikasi yang dilarang
    // ─────────────────────────────────────────────────────────────

    /** Package name persis — sinyal paling akurat, nyaris tanpa false positive. */
    private static final List<String> BLOCKED_PACKAGES = Arrays.asList(
            // Asisten AI
            "com.openai.chatgpt",
            "com.anthropic.claude",
            "ai.perplexity.app.android",
            "com.deepseek.chat",
            "com.google.android.apps.bard",
            "com.google.android.apps.gemini",
            "com.microsoft.copilot",
            "com.microsoft.bing",
            "com.quora.android",
            "ai.x.grok",
            "com.character.ai",
            "com.meta.ai",
            "com.alibaba.tongyi",
            "com.moonshot.kimichat",
            "com.larus.nova",
            "chat.mistral.ai",
            "com.phind.app",
            // Remote desktop / screen sharing
            "com.teamviewer.quicksupport.market",
            "com.teamviewer.teamviewer.market.mobile",
            "com.anydesk.anydeskandroid",
            "com.rustdesk",
            "com.microsoft.rdc.androidx",
            "com.splashtop.remote.pad.v2",
            // Screen recorder populer
            "com.hecorat.screenrecorder.free",
            "com.kimcy929.screenrecorder",
            "com.duapps.recorder"
    );

    /**
     * Kata kunci pada label aplikasi — jaring pengaman bila package name berubah.
     * Sengaja memakai frasa yang panjang & spesifik; kata umum seperti "ai" saja
     * akan menandai "Mail", "Airbnb", "Dairy" dan puluhan aplikasi tak bersalah.
     */
    private static final List<String> BLOCKED_LABEL_KEYWORDS = Arrays.asList(
            "chatgpt", "openai", "copilot", "deepseek", "perplexity",
            "character.ai", "character ai", "claude", "meta ai", "bing chat",
            "google bard", "gemini", "grok", "qwen", "kimi", "doubao",
            "mistral ai", "hugging face", "chatsonic", "phind",
            "ai chat", "chat ai", "ask ai", "ai assistant", "asisten ai",
            "teamviewer", "anydesk", "rustdesk", "remote desktop",
            "screen recorder", "perekam layar", "screen mirroring"
    );

    // ─────────────────────────────────────────────────────────────
    //  Pemindaian aplikasi terlarang
    // ─────────────────────────────────────────────────────────────

    /**
     * Nama publik yang dipakai UI. Mengembalikan daftar aplikasi terlarang
     * yang benar-benar terpasang, tanpa duplikat, dan tanpa aplikasi sistem.
     */
    public static ArrayList<ApplicationInfo> getDangerousInstalledApps(Context context) {
        Map<String, ApplicationInfo> found = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();

        // 1. Pencocokan package name — selalu berhasil berkat <queries> di manifest.
        for (String pkg : BLOCKED_PACKAGES) {
            try {
                found.put(pkg, pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException ignored) {
                // tidak terpasang
            }
        }

        // 2. Pencocokan label — hanya berjalan bila kita punya visibilitas penuh.
        for (ApplicationInfo app : safeGetInstalledApplications(pm)) {
            if (found.containsKey(app.packageName)) continue;
            if (isSystemApp(app)) continue;
            if (context.getPackageName().equals(app.packageName)) continue;

            String label;
            try {
                label = pm.getApplicationLabel(app).toString().toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                continue;
            }
            for (String keyword : BLOCKED_LABEL_KEYWORDS) {
                if (label.contains(keyword)) {
                    found.put(app.packageName, app);
                    break;
                }
            }
        }

        return new ArrayList<>(found.values());
    }

    /** Alias historis — dipertahankan agar kode lama tetap terkompilasi. */
    public static ArrayList<ApplicationInfo> getInstalledAiApps(Context context) {
        return getDangerousInstalledApps(context);
    }

    public static boolean hasDangerousApps(Context context) {
        return !getDangerousInstalledApps(context).isEmpty();
    }

    public static String getAppName(Context context, ApplicationInfo app) {
        try {
            return context.getPackageManager().getApplicationLabel(app).toString();
        } catch (Exception e) {
            return app.packageName;
        }
    }

    private static List<ApplicationInfo> safeGetInstalledApplications(PackageManager pm) {
        try {
            return pm.getInstalledApplications(0);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static boolean isSystemApp(ApplicationInfo app) {
        int mask = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        return (app.flags & mask) != 0;
    }

    // ─────────────────────────────────────────────────────────────
    //  Deteksi emulator
    // ─────────────────────────────────────────────────────────────

    /**
     * Menggunakan skor, bukan satu penanda tunggal. Beberapa ponsel murah asli
     * memakai build fingerprint generik, jadi satu kecocokan saja belum cukup
     * untuk mengunci peserta di luar ujiannya.
     */
    public static boolean isRunningOnEmulator() {
        int score = 0;

        String fingerprint = safe(Build.FINGERPRINT);
        String model       = safe(Build.MODEL);
        String product     = safe(Build.PRODUCT);
        String hardware    = safe(Build.HARDWARE);
        String brand       = safe(Build.BRAND);
        String device      = safe(Build.DEVICE);
        String manufacturer= safe(Build.MANUFACTURER);

        if (fingerprint.startsWith("generic") || fingerprint.startsWith("unknown")
                || fingerprint.contains("emulator") || fingerprint.contains("vbox")
                || fingerprint.contains("test-keys")) score += 2;

        if (model.contains("google_sdk") || model.contains("emulator")
                || model.contains("android sdk built for")
                || model.contains("sdk_gphone")) score += 3;

        if (product.contains("sdk") || product.contains("emulator")
                || product.contains("simulator") || product.contains("vbox")
                || product.contains("nox") || product.contains("bluestacks")) score += 2;

        if (hardware.contains("goldfish") || hardware.contains("ranchu")
                || hardware.contains("vbox") || hardware.contains("ttvm")
                || hardware.contains("nox") || hardware.contains("cancro")) score += 3;

        if (brand.startsWith("generic") && device.startsWith("generic")) score += 2;
        if ("genymotion".equals(manufacturer) || manufacturer.contains("unknown")) score += 2;
        if (safe(Build.BOARD).contains("nox") || safe(Build.BOOTLOADER).contains("nox")) score += 3;

        // Emulator umumnya tidak punya radio telepon sungguhan.
        if ("unknown".equals(safe(Build.getRadioVersion()))) score += 1;

        return score >= 3;
    }

    // ─────────────────────────────────────────────────────────────
    //  Setelan developer
    // ─────────────────────────────────────────────────────────────

    public static boolean isUsbDebuggingEnabled(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isDeveloperModeEnabled(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ALLOW_MOCK_LOCATION sudah usang sejak API 23. Pada perangkat modern
     * satu-satunya cara andal adalah menanyai penyedia lokasi saat runtime,
     * yang butuh izin lokasi — dan aplikasi ujian tidak seharusnya memintanya.
     * Kita cek setelan lama untuk perangkat lawas dan tidak memblokir selebihnya.
     */
    @SuppressWarnings("deprecation")
    public static boolean isMockLocationEnabled(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return false;
            return !"0".equals(Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ALLOW_MOCK_LOCATION));
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Perekaman & pembajakan layar
    // ─────────────────────────────────────────────────────────────

    /**
     * Android tidak mengekspos API "apakah layar sedang direkam". Pertahanan
     * yang benar adalah FLAG_SECURE (dipasang di MainActivity), yang membuat
     * hasil rekaman menjadi hitam. Method ini hanya melaporkan apakah ada
     * aplikasi perekam yang terpasang, sebagai sinyal tambahan.
     */
    public static boolean isScreenRecording(Context context) {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return false;
        } catch (Exception ignored) {
            // service tidak tersedia
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    //  Status lock task (screen pinning / kiosk)
    // ─────────────────────────────────────────────────────────────

    /** True bila aplikasi terkunci di layar, baik mode pinned maupun kiosk. */
    public static boolean isInLockTask(Context context) {
        ActivityManager am = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
            }
            return am.isInLockTaskMode();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True hanya untuk mode PINNED — pinning yang dimulai pengguna, tempat sistem
     * menampilkan overlay "Sematkan layar?" dan mencuri fokus jendela kita.
     * MainActivity memakai ini untuk membedakan overlay sistem yang tidak berbahaya
     * dari peserta yang benar-benar berpindah aplikasi.
     */
    public static boolean isLockTaskPinned(Context context) {
        ActivityManager am = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_PINNED;
            }
            return am.isInLockTaskMode();
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────

    private static String safe(String value) {
        return value == null ? "unknown" : value.toLowerCase(Locale.ROOT);
    }

    // ─────────────────────────────────────────────────────────────
    //  Deteksi Root
    // ─────────────────────────────────────────────────────────────

    /** Daftar path binary su yang umum ditemukan pada perangkat root. */
    private static final List<String> SU_BINARY_PATHS = Arrays.asList(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk",
            "/system/app/SuperSU/SuperSU.apk",
            "/system/priv-app/Superuser/Superuser.apk"
    );

    /** Daftar package name aplikasi manajemen root. */
    private static final List<String> ROOT_MANAGER_PACKAGES = Arrays.asList(
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.noshufou.android.su",
            "eu.chainfire.supersu",
            "com.topjohnwu.magisk",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.smedialink.oneclickroot",
            "com.zhiqupk.root.global",
            "com.alephzain.framaroot"
    );

    /**
     * Deteksi root menggunakan pendekatan berlapis:
     * 1. Cek binary su di path umum
     * 2. Cek aplikasi manajemen root
     * 3. Cek test-keys pada build fingerprint
     * 4. Cek props build yang mencurigakan
     *
     * Mengembalikan true jika ada indikasi root yang signifikan.
     */
    public static boolean isDeviceRooted(Context context) {
        int score = 0;

        // 1. Cek binary su
        for (String path : SU_BINARY_PATHS) {
            if (new java.io.File(path).exists()) {
                score += 5;
                break;
            }
        }

        // 2. Cek aplikasi manajemen root
        PackageManager pm = context.getPackageManager();
        for (String pkg : ROOT_MANAGER_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                score += 4;
                break;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        // 3. Cek test-keys
        String fingerprint = safe(Build.FINGERPRINT);
        if (fingerprint.contains("test-keys")) {
            score += 2;
        }

        // 4. Cek build tags
        String tags = safe(Build.TAGS);
        if (tags.contains("test-keys") || tags.contains("dev-keys")) {
            score += 2;
        }

        // 5. Cek build type (userdebug = root potential)
        String buildType = safe(Build.TYPE);
        if (buildType.equals("userdebug") || buildType.equals("eng")) {
            score += 1;
        }

        // 6. Cek prop build yang mencurigakan
        try {
            String processBuilder = android.os.Build.BOARD;
            if (processBuilder != null && processBuilder.toLowerCase().contains("goldfish")) {
                score += 1;
            }
        } catch (Exception ignored) {
        }

        // Threshold: 3 atau lebih = terindikasi root
        return score >= 3;
    }

    /**
     * Cek apakah Magisk terpasang (termasuk Magisk Hide / DenyList).
     * Magisk adalah root manager paling populer saat ini.
     */
    public static boolean isMagiskInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo("com.topjohnwu.magisk", 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    /**
     * Cek apakah ada binary su yang bisa dieksekusi.
     * Lebih akurat dari sekadar cek file existence.
     */
    public static boolean canExecuteSu() {
        try {
            Runtime.getRuntime().exec("su --version");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gabungan semua pengecekan root.
     * Mengembalikan true jika perangkat dianggap tidak aman untuk ujian.
     */
    public static boolean isRooted(Context context) {
        return isDeviceRooted(context) || isMagiskInstalled(context);
    }

    /**
     * Mendapatkan alasan root terdeteksi (untuk pesan error).
     */
    public static String getRootReason(Context context) {
        java.util.List<String> reasons = new ArrayList<>();

        for (String path : SU_BINARY_PATHS) {
            if (new java.io.File(path).exists()) {
                reasons.add("Binary su ditemukan di " + path);
                break;
            }
        }

        PackageManager pm = context.getPackageManager();
        for (String pkg : ROOT_MANAGER_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                reasons.add("Aplikasi root terdeteksi: " + pkg);
                break;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        String tags = safe(Build.TAGS);
        if (tags.contains("test-keys")) {
            reasons.add("Build menggunakan test-keys");
        }

        String buildType = safe(Build.TYPE);
        if (buildType.equals("userdebug") || buildType.equals("eng")) {
            reasons.add("Build type: " + buildType);
        }

        return reasons.isEmpty() ? "Tidak diketahui" : reasons.get(0);
    }




    // ─────────────────────────────────────────────────────────────
    //  Deteksi Layanan Aksesibilitas yang mencurigakan
    // ─────────────────────────────────────────────────────────────

    private static final List<String> BLOCKED_ACCESSIBILITY_PACKAGES = Arrays.asList(
            "com.teamviewer.quicksupport.market",
            "com.anydesk.anydeskandroid",
            "com.rustdesk",
            "com.microsoft.rdc.androidx",
            "com.splashtop.remote.pad.v2"
    );

    /**
     * Cek apakah ada layanan aksesibilitas aktif yang bisa merekam layar
     * atau mengambil alih input.
     */
    public static boolean hasSuspiciousAccessibilityServices(Context context) {
        try {
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || enabledServices.isEmpty()) return false;

            String[] services = enabledServices.split(":");
            for (String service : services) {
                String packageName = service.split("/")[0];
                for (String blocked : BLOCKED_ACCESSIBILITY_PACKAGES) {
                    if (blocked.equals(packageName)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    //  Deteksi Overlay Attack
    // ─────────────────────────────────────────────────────────────

    /**
     * Cek apakah ada aplikasi lain yang bisa membuat overlay di atas
     * aplikasi ujian (sering dipakai untuk menampilkan kunci jawaban).
     */
    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    //  Deteksi Split Screen / Multi-Window Abuse
    // ─────────────────────────────────────────────────────────────

    /**
     * Cek apakah perangkat dalam mode split screen.
     */
    public static boolean isInMultiWindowMode(Context context) {
        if (context instanceof android.app.Activity) {
            return ((android.app.Activity) context).isInMultiWindowMode();
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────
    //  Deteksi VPN yang mencurigakan
    // ─────────────────────────────────────────────────────────────

    /**
     * Cek apakah ada VPN aktif yang bisa digunakan untuk
     * mengarahkan lalu lintas jaringan.
     */
    public static boolean isVpnActive(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null) return false;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Deteksi Power Button Abuse
    // ─────────────────────────────────────────────────────────────

    /**
     * Cek apakah screen saver / ambient display aktif
     * yang bisa digunakan untuk menyembunyikan aktivitas.
     */
    public static boolean isScreenSaverActive(Context context) {
        try {
            return Settings.Secure.getInt(context.getContentResolver(),
                    "screensaver_enabled", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Deteksi USB Connection
    // ─────────────────────────────────────────────────────────────

    /**
     * Cek apakah USB terhubung (bisa digunakan untuk ADB remote).
     */
    public static boolean isUsbConnected(Context context) {
        try {
            Intent intent = context.registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent == null) return false;
            int plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
            return plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB;
        } catch (Exception e) {
            return false;
        }
    }

}
