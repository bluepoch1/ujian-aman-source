package com.safebrowser.app;

import androidx.appcompat.app.AppCompatActivity;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Splash singkat, hanya untuk identitas merek. */
public class SplashScreenActivity extends AppCompatActivity {

    private static final long SPLASH_MS = 1_200L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::openGate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        // 2,5 detik terasa lama saat seluruh kelas menunggu untuk mulai.
        handler.postDelayed(advance, SPLASH_MS);
    }

    private void openGate() {
        if (isFinishing() || isDestroyed()) return;
        startActivity(new Intent(this, InputAddressActivity.class));
        finish();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        // Tanpa ini, callback tertunda bisa menjalankan activity yang sudah mati.
        handler.removeCallbacks(advance);
        super.onDestroy();
    }

    /**

     * Splash tidak boleh dibatalkan.

     */

    /** Splash tidak boleh dibatalkan. */
    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        // Splash tidak boleh dibatalkan.
    }
}
