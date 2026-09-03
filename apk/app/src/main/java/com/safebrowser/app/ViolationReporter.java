package com.safebrowser.app;

import android.content.Context;

/**
 * Pelaporan pelanggaran langsung ke server.
 *
 * Versi 3.4+: Tidak ada antrean lokal. Pelanggaran dikirim langsung ke server.
 * Jika offline, pelanggaran dicatat sebagai "koneksi_putus" dan
 * server akan mencatat bahwa perangkat kehilangan koneksi.
 *
 * Keamanan: Tidak ada penyimpanan lokal yang bisa dimanipulasi.
 */
public final class ViolationReporter {

    private ViolationReporter() { }

    public static final String KELUAR_APLIKASI      = "keluar_aplikasi";
    public static final String PERCOBAAN_SCREENSHOT = "percobaan_screenshot";
    public static final String KONEKSI_PUTUS        = "koneksi_putus";
    public static final String APLIKASI_TERLARANG   = "aplikasi_terlarang";
    public static final String MASUK_ULANG          = "masuk_ulang";
    public static final String PERANGKAT_TIDAK_AMAN = "perangkat_tidak_aman";
    public static final String LAINNYA              = "lainnya";

    /**
     * Catat pelanggaran langsung ke server.
     * Tidak ada penyimpanan lokal - semua data langsung dikirim.
     */
    public static void laporkan(Context context, String sessionId,
                                String jenis, String detail,
                                int durasiDetik) {
        if (sessionId == null || sessionId.isEmpty()) return;

        SupabaseClient.catatPelanggaran(
                context,
                sessionId,
                jenis,
                detail == null ? "" : detail,
                durasiDetik,
                berhasil -> {
                    if (!berhasil) {
                        // Jika gagal kirim (offline), catat di server
                        // bahwa perangkat kehilangan koneksi
                        SupabaseClient.catatPelanggaran(
                                context,
                                sessionId,
                                KONEKSI_PUTUS,
                                "Gagal mengirim pelanggaran: " + jenis,
                                0,
                                ignored -> { });
                    }
                });
    }

    /**
     * Kirim ulang pelanggaran yang gagal.
     * Tidak ada yang perlu dikirim karena tidak ada antrean lokal.
     */
    public static void kirimAntrean(Context context) {
        // Tidak ada antrean lokal - semua sudah dikirim langsung
    }

    /**
     * Jumlah pelanggaran tertunda.
     * Selalu 0 karena tidak ada antrean lokal.
     */
    public static int jumlahTertunda(Context context) {
        return 0;
    }

    /**
     * Bersihkan data pelanggaran.
     * Tidak ada yang perlu dibersihkan karena tidak ada antrean lokal.
     */
    public static void bersihkan(Context context) {
        // Tidak ada antrean lokal - tidak perlu dibersihkan
    }
}
