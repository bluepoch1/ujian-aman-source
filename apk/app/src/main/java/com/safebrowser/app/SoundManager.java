package com.safebrowser.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;

import androidx.annotation.Nullable;
import androidx.annotation.RawRes;

/**
 * Pemutar suara tunggal untuk peringatan dan umpan balik.
 *
 * Catatan tentang volume: versi lama memaksa volume musik, dering, DAN alarm
 * ke maksimum pada setiap pemutaran — termasuk untuk bunyi bip pemindai QR.
 * Itu perilaku bermusuhan; peserta dengan sensitivitas suara akan terkejut,
 * dan setelan volume mereka tidak pernah dikembalikan. Sekarang hanya alarm
 * pelanggaran yang menaikkan volume, dan nilainya dipulihkan setelahnya.
 */
public final class SoundManager {

    private SoundManager() { }

    @Nullable private static MediaPlayer player;
    /** Volume musik sebelum alarm menaikkannya; -1 berarti tidak diubah. */
    private static int savedVolume = -1;
    /** True bila yang sedang diputar adalah alarm pelanggaran, bukan umpan balik. */
    private static boolean playingAlarm = false;

    public static void playAlarm(Context context) {
        raiseVolume(context);
        play(context, R.raw.alarm, true, null);
        playingAlarm = true;
    }

    public static void playBeep(Context context) {
        play(context, R.raw.zxing_beep, false, null);
    }

    /** Token diterima server: dua nada naik, pendek. */
    public static void playTokenOk(Context context) {
        play(context, R.raw.token_ok, false, null);
    }

    /**
     * Peserta menekan "Mulai Ujian". Nada ini sengaja lebih berbobot daripada
     * bunyi token karena menandai titik tak-bisa-kembali: setelah ini layar
     * terkunci dan keluar tercatat sebagai pelanggaran.
     */
    public static void playExamStart(Context context) {
        play(context, R.raw.exam_start, false, null);
    }

    /**
     * Memutar bunyi keluar lalu menjalankan {@code onDone}. Callback dijamin
     * berjalan tepat satu kali, baik saat pemutaran selesai, gagal, maupun
     * berhenti karena galat.
     */
    public static void playExitThenRun(Context context, Runnable onDone) {
        play(context, R.raw.exit, false, onDone);
    }

    private static synchronized void play(Context context, @RawRes int resId,
                                          boolean loop, @Nullable Runnable onDone) {
        stop();
        final boolean[] fired = {false};
        Runnable once = () -> {
            if (fired[0] || onDone == null) return;
            fired[0] = true;
            onDone.run();
        };

        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(loop ? AudioAttributes.USAGE_ALARM
                                   : AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());

            try (AssetFileDescriptor afd =
                         context.getResources().openRawResourceFd(resId)) {
                if (afd == null) {
                    once.run();
                    return;
                }
                mp.setDataSource(afd.getFileDescriptor(),
                        afd.getStartOffset(), afd.getLength());
            }

            mp.setLooping(loop);
            mp.setOnCompletionListener(m -> {
                releasePlayer();
                once.run();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                releasePlayer();
                once.run();
                return true;
            });
            mp.prepare();
            mp.start();
            player = mp;

        } catch (Exception e) {
            releasePlayer();
            once.run();
        }
    }

    public static synchronized void stop() {
        releasePlayer();
    }

    /**
     * Menghentikan hanya alarm pelanggaran, membiarkan nada umpan balik
     * selesai berbunyi.
     *
     * Dipakai oleh MainActivity.onResume(). Sebelumnya di sana dipanggil
     * stop() biasa, yang memotong nada "mulai ujian" dalam sepersekian
     * detik karena onResume() berjalan tepat setelah activity berpindah.
     */
    public static synchronized void stopAlarmOnly() {
        if (playingAlarm) releasePlayer();
    }

    private static void releasePlayer() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Exception ignored) {
                // pemutar dalam keadaan tidak valid
            }
            try {
                player.release();
            } catch (Exception ignored) {
                // sudah dilepas
            }
            player = null;
        }
        playingAlarm = false;
    }

    private static void raiseVolume(Context context) {
        try {
            AudioManager am =
                    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (savedVolume < 0) {
                savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            }
            am.setStreamVolume(AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception ignored) {
            // beberapa perangkat melarang perubahan volume
        }
    }

    /** Mengembalikan volume alarm ke nilai peserta sebelumnya. */
    public static void restoreVolume(Context context) {
        if (savedVolume < 0) return;
        try {
            AudioManager am =
                    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
            }
        } catch (Exception ignored) {
            // tidak dapat memulihkan
        }
        savedVolume = -1;
    }
}
