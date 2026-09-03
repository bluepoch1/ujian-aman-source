package com.safebrowser.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

/**
 * Menjaga sesi ujian tetap hidup dan tersinkron dengan jam server.
 *
 * Dua hal yang ditangani di sini:
 *
 * 1. Heartbeat tiap 15 detik. Pengawas menandai peserta offline setelah 45
 *    detik tanpa detak, jadi satu atau dua kegagalan jaringan tidak langsung
 *    memicu alarm palsu.
 *
 * 2. Timer berbasis waktu server. Sisa waktu dihitung dari elapsedRealtime()
 *    yang tidak terpengaruh perubahan jam pengguna, lalu dikoreksi tiap kali
 *    server mengirim angka barunya. Mengubah jam HP tidak menambah waktu ujian.
 */
public final class SessionManager {

    /** Server menganggap offline setelah 45 detik; kita detak 3x lebih sering. */
    private static final long HEARTBEAT_MS = 15_000L;

    /** Perbarui tampilan hitung mundur tiap detik. */
    private static final long TICK_MS = 1_000L;

    private static final String PREFS = "exam_session";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_URL = "url";
    private static final String KEY_KELAS = "kelas";
    private static final String KEY_NAMA = "nama";

    public interface Listener {
        /** Dipanggil tiap detik dengan sisa waktu terkini. */
        void onTick(long sisaDetik);

        /** Waktu habis, atau pengawas menghentikan/menyelesaikan sesi. */
        void onSesiBerakhir(String alasan, String pesan);

        /** Status koneksi ke server berubah. */
        void onKoneksi(boolean daring);
    }

    private final Context context;
    private final String sessionId;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Nullable private Listener listener;

    /**
     * Titik acuan: sisa detik pada saat elapsedRealtime() bernilai
     * acuanElapsed. Keduanya selalu diperbarui bersamaan.
     */
    private long sisaDetikAcuan;
    private long acuanElapsed;

    private boolean berjalan;
    private boolean daring = true;
    private int gagalBerturut;

    public SessionManager(Context context, String sessionId, long sisaDetikAwal) {
        this.context = context.getApplicationContext();
        this.sessionId = sessionId;
        setAcuan(sisaDetikAwal);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public String sessionId() {
        return sessionId;
    }

    // ─────────────────────────────────────────────────────────────

    public void mulai() {
        if (berjalan) return;
        berjalan = true;
        ui.post(tick);
        ui.post(heartbeat);
        ViolationReporter.kirimAntrean(context);
    }

    public void berhenti() {
        berjalan = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(heartbeat);
    }

    /** Sisa waktu saat ini, dihitung dari jam monotonik perangkat. */
    public long sisaDetik() {
        long berlalu = (SystemClock.elapsedRealtime() - acuanElapsed) / 1000L;
        return Math.max(0, sisaDetikAcuan - berlalu);
    }

    private void setAcuan(long sisaDetik) {
        this.sisaDetikAcuan = Math.max(0, sisaDetik);
        this.acuanElapsed = SystemClock.elapsedRealtime();
    }

    // ─────────────────────────────────────────────────────────────

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!berjalan) return;

            long sisa = sisaDetik();
            if (listener != null) listener.onTick(sisa);

            if (sisa <= 0) {
                berhenti();
                if (listener != null) {
                    listener.onSesiBerakhir("waktu_habis",
                            context.getString(R.string.exam_time_up));
                }
                return;
            }
            ui.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!berjalan) return;

            SupabaseClient.heartbeat(context, sessionId,
                    new SupabaseClient.DetakCallback() {
                        @Override
                        public void onDetak(SupabaseClient.Detak detak) {
                            gagalBerturut = 0;
                            setDaring(true);

                            if (detak.sesiHilang) {
                                berhenti();
                                if (listener != null) {
                                    listener.onSesiBerakhir(detak.status, detak.pesan);
                                }
                                return;
                            }

                            // Jam server adalah kebenaran. Koreksi ini juga
                            // yang membatalkan efek mengubah jam perangkat.
                            setAcuan(detak.sisaDetik);

                            // Momen bagus untuk menguras antrean: koneksi
                            // terbukti hidup barusan.
                            ViolationReporter.kirimAntrean(context);
                        }

                        @Override
                        public void onOffline() {
                            gagalBerturut++;
                            // Satu kegagalan bisa jadi gangguan sesaat.
                            // Dua kali berturut-turut baru dianggap putus.
                            if (gagalBerturut >= 2) setDaring(false);
                        }
                    });

            if (berjalan) ui.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private void setDaring(boolean nilai) {
        if (daring == nilai) return;
        daring = nilai;

        if (!nilai) {
            ViolationReporter.laporkan(context, sessionId,
                    ViolationReporter.KONEKSI_PUTUS,
                    "Perangkat kehilangan koneksi ke server", 0);
        }
        if (listener != null) listener.onKoneksi(nilai);
    }

    public boolean daring() {
        return daring;
    }

    // ─────────────────────────────────────────────────────────────
    //  Sesi tersimpan — memungkinkan pemulihan setelah aplikasi ditutup
    // ─────────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void simpan(Context c, SupabaseClient.Sesi sesi) {
        prefs(c).edit()
                .putString(KEY_SESSION_ID, sesi.sessionId)
                .putString(KEY_URL, sesi.url)
                .putString(KEY_KELAS, sesi.namaKelas)
                .putString(KEY_NAMA, sesi.namaPeserta)
                .apply();
    }

    @Nullable
    public static String sesiTersimpan(Context c) {
        return prefs(c).getString(KEY_SESSION_ID, null);
    }

    public static void hapusTersimpan(Context c) {
        prefs(c).edit().clear().apply();
    }
}
