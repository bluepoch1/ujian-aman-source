package com.safebrowser.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Identitas perangkat untuk aturan "1 token = 1 perangkat".
 *
 * ANDROID_ID mentah tidak pernah dikirim ke server. Yang dikirim adalah
 * SHA-256 dari ANDROID_ID digabung salt aplikasi, sehingga basis data ujian
 * tidak menyimpan pengenal perangkat yang dapat dipakai melacak siswa di
 * luar konteks ujian.
 *
 * Sejak Android 8, ANDROID_ID sudah unik per (aplikasi, pengguna, perangkat)
 * dan berubah saat aplikasi dipasang ulang. Itu justru menguntungkan: peserta
 * yang menghapus lalu memasang ulang aplikasi untuk mengakali kunci perangkat
 * akan tetap dikenali lewat sesi yang sudah tercatat di server.
 */
public final class DeviceIdentity {

    private DeviceIdentity() { }

    private static final String PREFS = "device_identity";
    private static final String KEY_FALLBACK = "fallback_id";

    /** Salt tetap; membuat hash tidak bisa dicocokkan dengan basis data lain. */
    private static final String SALT = "safebrowser.v3.device";

    private static String cached;

    @SuppressLint("HardwareIds")
    public static synchronized String hash(Context context) {
        if (cached != null) return cached;

        String raw = null;
        try {
            raw = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
            // penyedia setelan tidak tersedia
        }

        // Beberapa emulator dan ROM modifikasi mengembalikan null, string
        // kosong, atau nilai buggy lama "9774d56d682e549c". Semua itu tidak
        // membedakan perangkat, jadi kita pakai UUID tersimpan sebagai ganti.
        if (raw == null || raw.length() < 8 || "9774d56d682e549c".equals(raw)) {
            raw = fallbackId(context);
        }

        cached = sha256(SALT + "|" + raw);
        return cached;
    }

    private static String fallbackId(Context context) {
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_FALLBACK, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_FALLBACK, id).apply();
        }
        return id;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // Tidak akan terjadi: SHA-256 wajib ada di setiap JVM Android.
            return Integer.toHexString(input.hashCode())
                    + Integer.toHexString(SALT.hashCode());
        }
    }

    /** Label perangkat untuk dashboard pengawas, misalnya "Xiaomi Redmi Note 12". */
    public static String model() {
        String brand = safe(Build.MANUFACTURER);
        String model = safe(Build.MODEL);
        if (model.toLowerCase().startsWith(brand.toLowerCase())) {
            return capitalize(model);
        }
        return (capitalize(brand) + " " + model).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
