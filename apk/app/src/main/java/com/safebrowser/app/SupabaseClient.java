package com.safebrowser.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class SupabaseClient {

    private SupabaseClient() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final int MAX_TOKEN_ATTEMPTS = 10;
    private static long lastAttemptReset = 0;
    private static int tokenAttemptCount = 0;

    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_WINDOW_MS = 5 * 60 * 1000L;
    private static long loginWindowStart = 0;
    private static int loginAttemptCount = 0;

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("^[A-Za-z0-9\\-_.]{6,}$");

    public static final class Sesi {
        public String sessionId = "";
        public String url = "";
        public String namaKelas = "";
        public String mataPelajaran = "";
        public String namaPeserta = "";
        public int durasiMenit;
        public long sisaDetik;
        public boolean masukUlang;
        @Nullable public Date batasWaktu;
    }

    public static final class Gagal {
        public final String kode;
        public final String pesan;
        @Nullable public final Date mulaiAt;

        public Gagal(String kode, String pesan, @Nullable Date mulaiAt) {
            this.kode = kode;
            this.pesan = pesan;
            this.mulaiAt = mulaiAt;
        }
    }

    public interface SesiCallback {
        void onBerhasil(Sesi sesi);
        void onGagal(Gagal gagal);
    }

    public static final class Detak {
        public long sisaDetik;
        public String status = "aktif";
        public boolean sesiHilang;
        public String pesan = "";
    }

    public interface DetakCallback {
        void onDetak(Detak detak);
        void onOffline();
    }

    public interface SimpleCallback {
        void onSelesai(boolean berhasil);
    }

    public static void klaimSesi(Context context, String token, String nama,
                                 String nomorPeserta, @NonNull SesiCallback callback) {
        long now = System.currentTimeMillis();
        if (now - lastAttemptReset > 60_000L) {
            lastAttemptReset = now;
            tokenAttemptCount = 0;
        }
        tokenAttemptCount++;
        if (tokenAttemptCount > MAX_TOKEN_ATTEMPTS) {
            main(() -> callback.onGagal(new Gagal("RATE_LIMITED",
                    "Terlalu banyak percobaan. Coba lagi dalam 1 menit.", null)));
            return;
        }

        if (now - loginWindowStart > LOGIN_WINDOW_MS) {
            loginWindowStart = now;
            loginAttemptCount = 0;
        }
        loginAttemptCount++;
        if (loginAttemptCount > MAX_LOGIN_ATTEMPTS) {
            main(() -> callback.onGagal(new Gagal("LOGIN_RATE_LIMITED",
                    "Terlalu banyak percobaan login. Tunggu 5 menit.", null)));
            return;
        }

        if (token == null || token.trim().isEmpty()) {
            main(() -> callback.onGagal(gagalLokal(context, R.string.err_token_parse)));
            return;
        }
        if (nama == null || nama.trim().length() < 2) {
            main(() -> callback.onGagal(gagalLokal(context, R.string.err_token_parse)));
            return;
        }

        if (!TOKEN_PATTERN.matcher(token.trim()).matches()) {
            main(() -> callback.onGagal(new Gagal("INVALID_TOKEN_FORMAT",
                    "Format token tidak valid.", null)));
            return;
        }

        JSONObject args = new JSONObject();
        try {
            args.put("p_token", token);
            args.put("p_nama", nama);
            args.put("p_device_hash", DeviceIdentity.hash(context));
            args.put("p_nomor_peserta", nomorPeserta == null ? "" : nomorPeserta);
            args.put("p_device_model", DeviceIdentity.model());
            args.put("p_app_version", BuildConfig.VERSION_NAME);
        } catch (Exception e) {
            main(() -> callback.onGagal(gagalLokal(context, R.string.err_token_parse)));
            return;
        }

        rpc(context, "klaim_sesi", args, new RawCallback() {
            @Override
            public void onJson(JSONObject json) {
                if (!json.optBoolean("ok", false)) {
                    String kode = json.optString("kode", "TOKEN_TIDAK_ADA");
                    String pesan = json.optString("pesan",
                            context.getString(R.string.err_token_unknown));
                    Date mulai = parseTimestamp(json.optString("mulai_at", ""));
                    main(() -> callback.onGagal(new Gagal(kode, pesan, mulai)));
                    return;
                }

                Sesi sesi = new Sesi();
                sesi.sessionId     = json.optString("session_id", "");
                sesi.url           = json.optString("url", "");
                sesi.namaKelas     = json.optString("nama_kelas", "Ujian");
                sesi.mataPelajaran = json.optString("mata_pelajaran", "");
                sesi.namaPeserta   = json.optString("nama_peserta", nama);
                sesi.durasiMenit   = json.optInt("durasi_menit", 0);
                sesi.sisaDetik     = json.optLong("sisa_detik", 0);
                sesi.masukUlang    = json.optBoolean("masuk_ulang", false);
                sesi.batasWaktu    = parseTimestamp(json.optString("batas_waktu_at", ""));

                String normalized = normalizeUrl(sesi.url);
                if (!isSafeExamUrl(normalized)) {
                    main(() -> callback.onGagal(
                            gagalLokal(context, R.string.err_token_parse)));
                    return;
                }
                sesi.url = normalized;

                loginAttemptCount = 0;
                tokenAttemptCount = 0;

                main(() -> callback.onBerhasil(sesi));
            }

            @Override
            public void onError(String kode) {
                main(() -> callback.onGagal(petaError(context, kode)));
            }
        });
    }

    public static void heartbeat(Context context, String sessionId,
                                 @NonNull DetakCallback callback) {
        JSONObject args = new JSONObject();
        try {
            args.put("p_session_id", sessionId);
        } catch (Exception ignored) {
        }

        rpc(context, "heartbeat", args, new RawCallback() {
            @Override
            public void onJson(JSONObject json) {
                Detak detak = new Detak();
                if (!json.optBoolean("ok", false)) {
                    String kode = json.optString("kode", "");
                    detak.sesiHilang = true;
                    detak.status = "SESI_TIDAK_ADA".equals(kode) ? "hilang"
                            : kode.toLowerCase(Locale.ROOT);
                    detak.pesan = json.optString("pesan", "");
                    main(() -> callback.onDetak(detak));
                    return;
                }
                detak.sisaDetik = json.optLong("sisa_detik", 0);
                detak.status = json.optString("status", "aktif");
                main(() -> callback.onDetak(detak));
            }

            @Override
            public void onError(String kode) {
                main(callback::onOffline);
            }
        });
    }

    public static void catatPelanggaran(Context context, String sessionId,
                                        String jenis, String detail,
                                        int durasiDetik,
                                        @Nullable SimpleCallback callback) {
        JSONObject args = new JSONObject();
        try {
            args.put("p_session_id", sessionId);
            args.put("p_jenis", jenis);
            args.put("p_detail", detail == null ? "" : detail);
            if (durasiDetik > 0) args.put("p_durasi_detik", durasiDetik);
            args.put("p_waktu_perangkat", isoNow());
        } catch (Exception ignored) {
        }

        rpc(context, "catat_pelanggaran", args, new RawCallback() {
            @Override
            public void onJson(JSONObject json) {
                boolean ok = json.optBoolean("ok", false);
                if (callback != null) main(() -> callback.onSelesai(ok));
            }

            @Override
            public void onError(String kode) {
                if (callback != null) main(() -> callback.onSelesai(false));
            }
        });
    }

    public static void akhiriSesi(Context context, String sessionId, String alasan,
                                  @Nullable SimpleCallback callback) {
        JSONObject args = new JSONObject();
        try {
            args.put("p_session_id", sessionId);
            args.put("p_alasan", alasan);
        } catch (Exception ignored) {
        }

        rpc(context, "akhiri_sesi", args, new RawCallback() {
            @Override
            public void onJson(JSONObject json) {
                if (callback != null) {
                    main(() -> callback.onSelesai(json.optBoolean("ok", false)));
                }
            }

            @Override
            public void onError(String kode) {
                if (callback != null) main(() -> callback.onSelesai(false));
            }
        });
    }

    private interface RawCallback {
        void onJson(JSONObject json);
        void onError(String kode);
    }

    private static void rpc(Context context, String fungsi, JSONObject args,
                            RawCallback callback) {
        if (BuildConfig.SUPABASE_URL.isEmpty()
                || BuildConfig.SUPABASE_ANON_KEY.isEmpty()) {
            callback.onError("config");
            return;
        }

        AuthManager.withToken(context, new AuthManager.TokenCallback() {
            @Override
            public void onToken(String accessToken) {
                Request request = new Request.Builder()
                        .url(BuildConfig.SUPABASE_URL + "/rest/v1/rpc/" + fungsi)
                        .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("x-device-hash", DeviceIdentity.hash(context))
                        .post(RequestBody.create(args.toString(), AuthManager.JSON))
                        .build();

                AuthManager.HTTP.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        callback.onError("network");
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (ResponseBody body = response.body()) {
                            if (body == null) {
                                callback.onError("network");
                                return;
                            }
                            String raw = body.string();
                            if (!response.isSuccessful()) {
                                callback.onError("server");
                                return;
                            }
                            callback.onJson(new JSONObject(raw));
                        } catch (Exception e) {
                            callback.onError("parse");
                        }
                    }
                });
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    private static Gagal petaError(Context c, String kode) {
        int res;
        switch (kode) {
            case "anon_disabled": res = R.string.err_anon_disabled; break;
            case "config":        res = R.string.err_config; break;
            case "parse":         res = R.string.err_token_parse; break;
            default:              res = R.string.err_network; break;
        }
        return new Gagal(kode.toUpperCase(Locale.ROOT), c.getString(res), null);
    }

    private static Gagal gagalLokal(Context c, int res) {
        return new Gagal("LOKAL", c.getString(res), null);
    }

    private static String isoNow() {
        SimpleDateFormat sdf =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
        return sdf.format(new Date());
    }

    public static boolean isSafeExamUrl(String raw) {
        if (raw == null) return false;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 2048) return false;

        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(trimmed);
        if (parsed == null) return false;

        String scheme = parsed.scheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) return false;

        String host = parsed.host();
        if (host == null || host.isEmpty()) return false;

        if (isPrivateOrLocalhost(host)) return false;

        return true;
    }

    private static boolean isPrivateOrLocalhost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);

        if ("localhost".equals(lower) || "127.0.0.1".equals(lower)
                || "::1".equals(lower) || "0.0.0.0".equals(lower)) {
            return true;
        }

        if (lower.startsWith("10.") || lower.startsWith("192.168.")
                || lower.startsWith("172.16.") || lower.startsWith("172.17.")
                || lower.startsWith("172.18.") || lower.startsWith("172.19.")
                || lower.startsWith("172.20.") || lower.startsWith("172.21.")
                || lower.startsWith("172.22.") || lower.startsWith("172.23.")
                || lower.startsWith("172.24.") || lower.startsWith("172.25.")
                || lower.startsWith("172.26.") || lower.startsWith("172.27.")
                || lower.startsWith("172.28.") || lower.startsWith("172.29.")
                || lower.startsWith("172.30.") || lower.startsWith("172.31.")
                || lower.startsWith("169.254.") || lower.equals("0.0.0.0")) {
            return true;
        }

        if (lower.startsWith("fc") || lower.startsWith("fd")
                || lower.startsWith("fe80:")) {
            return true;
        }

        return false;
    }

    public static String normalizeUrl(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    @Nullable
    public static Date parseTimestamp(String value) {
        if (value == null || value.isEmpty()) return null;

        String normalized = value.trim().replace("Z", "+0000");
        int lastColon = normalized.lastIndexOf(':');
        if (normalized.length() > 6 && lastColon > normalized.length() - 4) {
            char sign = normalized.charAt(normalized.length() - 6);
            if (sign == '+' || sign == '-') {
                normalized = normalized.substring(0, lastColon)
                        + normalized.substring(lastColon + 1);
            }
        }

        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ",
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
        };

        for (String pattern : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
                sdf.setLenient(false);
                if (!pattern.endsWith("Z")) {
                    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                }
                return sdf.parse(normalized);
            } catch (ParseException ignored) {
            }
        }
        return null;
    }

    private static void main(Runnable action) {
        MAIN.post(action);
    }

    @Nullable
    public static String validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            return "Password minimal 8 karakter";
        }
        if (password.length() > 128) {
            return "Password maksimal 128 karakter";
        }
        if (!password.matches(".*[A-Z].*")) {
            return "Password harus mengandung huruf besar";
        }
        if (!password.matches(".*[a-z].*")) {
            return "Password harus mengandung huruf kecil";
        }
        if (!password.matches(".*[0-9].*")) {
            return "Password harus mengandung angka";
        }

        boolean hasSymbol = false;
        for (char c : password.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                hasSymbol = true;
                break;
            }
        }
        if (!hasSymbol) {
            return "Password harus mengandung simbol";
        }

        return null;
    }

    public static String getSecurityReport() {
        return String.format(Locale.US,
                "Rate Limit Status:\n" +
                "- Token attempts: %d/%d\n" +
                "- Login attempts: %d/%d\n" +
                "- Last token reset: %d ms ago\n" +
                "- Last login reset: %d ms ago",
                tokenAttemptCount, MAX_TOKEN_ATTEMPTS,
                loginAttemptCount, MAX_LOGIN_ATTEMPTS,
                System.currentTimeMillis() - lastAttemptReset,
                System.currentTimeMillis() - loginWindowStart);
    }
}
