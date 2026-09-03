package com.safebrowser.app;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import android.Manifest;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_QR_TEXT = "qrText";
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_SISA_DETIK = "sisaDetik";
    public static final String EXTRA_NAMA_KELAS = "namaKelas";

    private static final long FOCUS_GRACE_MS = 15_000L;
    private static final long CLOCK_INTERVAL_MS = 1_000L;
    private static final long SPEED_INTERVAL_MS = 2_000L;
    private static final long NET_CHECK_INTERVAL_MS = 3_000L;

    private WebView webView;
    private ProgressBar progressBar;

    private TextView chronometer;
    private TextView tvTime, tvBattery, tvWifi, tvSignal, tvSpeed, tvErrorCode;
    private View btnBack, btnForward;
    private View layoutOffline, layoutError;

    @Nullable private PowerManager.WakeLock wakeLock;
    @Nullable private BroadcastReceiver headsetReceiver;
    @Nullable private BroadcastReceiver screenshotReceiver;
    @Nullable private AlertDialog focusDialog;
    @Nullable private AlertDialog exitDialog;
    @Nullable private FaceDetectionManager faceDetectionManager;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private String examUrl = "";
    private boolean isOffline;
    private boolean isExiting;
    private boolean isOwnDialogVisible;
    private boolean lockTaskRequested;
    private boolean timerStarted;

    @Nullable private SessionManager session;
    private String sessionId = "";
    private boolean warned5, warned1;
    private boolean warnedVibrate5, warnedVibrate1;
    private long focusLostAt;

    private long lastRxBytes;
    private long lastTxBytes;
    private long lastSpeedSampleAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        bindViews();
        applyImmersiveMode();
        blockClipboard();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafeBrowser:exam");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(4 * 60 * 60 * 1000L);
        }

        registerHeadsetReceiver();
        registerScreenshotReceiver();
        setupWebView();
        setupControls();
        startClock();
        startSpeedMonitor();
        startNetworkWatch();

        examUrl = resolveExamUrl(getIntent());
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        if (sessionId == null) sessionId = "";

        long sisaAwal = getIntent().getLongExtra(EXTRA_SISA_DETIK, 0);
        if (!sessionId.isEmpty()) {
            session = new SessionManager(this, sessionId, sisaAwal);
            session.setListener(sessionListener);
            session.mulai();

            // Mulai deteksi wajah
            mulaiDeteksiWajah();
        }

        if (examUrl.isEmpty()) {
            showError(getString(R.string.exam_url_invalid));
        } else {
            loadExam();
        }
    }

    private void bindViews() {
        webView       = findViewById(R.id.webview);
        progressBar   = findViewById(R.id.progress_bar);
        chronometer   = findViewById(R.id.chronometer);
        tvTime        = findViewById(R.id.tv_time);
        tvBattery     = findViewById(R.id.tv_battery);
        tvWifi        = findViewById(R.id.tv_wifi);
        tvSignal      = findViewById(R.id.tv_signal);
        tvSpeed       = findViewById(R.id.tv_speed);
        tvErrorCode   = findViewById(R.id.tv_error_code);
        btnBack       = findViewById(R.id.btn_back);
        btnForward    = findViewById(R.id.btn_forward);
        layoutOffline = findViewById(R.id.layout_offline);
        layoutError   = findViewById(R.id.layout_error);
    }

    private String resolveExamUrl(Intent intent) {
        String url = intent.getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) {
            url = intent.getStringExtra(EXTRA_QR_TEXT);
        }
        if (url == null || url.isEmpty()) return "";
        String normalized = SupabaseClient.normalizeUrl(url);
        return SupabaseClient.isSafeExamUrl(normalized) ? normalized : "";
    }

    @Override
    protected void onResume() {
        super.onResume();
        isOwnDialogVisible = false;
        cancelFocusCountdown();
        SoundManager.stopAlarmOnly();
        applyImmersiveMode();
        if (!sessionId.isEmpty()) ViolationReporter.kirimAntrean(this);
        
        // Check for root during exam
        if (SecurityChecker.isRooted(this)) {
            showRootDialog();
        }

        // Check VPN during exam
        if (!sessionId.isEmpty() && SecurityChecker.isVpnActive(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("VPN Terdeteksi")
                    .setMessage("Putuskan koneksi VPN untuk melanjutkan ujian.")
                    .setCancelable(false)
                    .setPositiveButton("Keluar", (d, w) -> exitApp())
                    .show();
        }

        // Check split screen
        if (!sessionId.isEmpty() && SecurityChecker.isInMultiWindowMode(this)) {
            ViolationReporter.laporkan(this, sessionId,
                    ViolationReporter.KELUAR_APLIKASI,
                    "Split screen terdeteksi", 0);
        }
    }

    @Override
    protected void onDestroy() {
        if (session != null) {
            session.setListener(null);
            session.berhenti();
        }
        ui.removeCallbacksAndMessages(null);
        if (headsetReceiver != null) {
            try { unregisterReceiver(headsetReceiver); } catch (Exception ignored) {}
            headsetReceiver = null;
        }
        if (screenshotReceiver != null) {
            try { unregisterReceiver(screenshotReceiver); } catch (Exception ignored) {}
            screenshotReceiver = null;
        }
        releaseWakeLock();
        if (faceDetectionManager != null) {
            faceDetectionManager.berhenti();
        }
        if (webView != null) {
            webView.setWebChromeClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }


    private void showRootDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Perangkat ter-root")
                .setMessage("Perangkat yang telah di-root tidak diizinkan mengikuti ujian. Sesi akan dihentikan.")
                .setCancelable(false)
                .setPositiveButton("Keluar", (d, w) -> finish())
                .show();
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
        }

        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " SafeBrowser/"
                + BuildConfig.VERSION_NAME);

        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        webView.setHapticFeedbackEnabled(false);
        webView.setVerticalScrollBarEnabled(true);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int progress) {
                progressBar.setProgress(progress);
                progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }

            @android.annotation.TargetApi(21)
            @Override
            public boolean onShowFileChooser(WebView webView,
                                           ValueCallback<Uri[]> filePathCallback,
                                           android.webkit.WebChromeClient.FileChooserParams fileChooserParams) {
                filePathCallback.onReceiveValue(null);
                return true;
            }
        });

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                    WebResourceRequest request) {
                Uri target = request.getUrl();
                String scheme = target.getScheme();
                if (scheme == null) return true;
                if (!"http".equals(scheme) && !"https".equals(scheme)) return true;
                if (!isSameHostAsExam(target)) {
                    toast(getString(R.string.exam_link_blocked));
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                progressBar.setVisibility(View.VISIBLE);
                showContent();
                updateNavButtons();
                startExamTimerOnce();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                progressBar.setVisibility(View.GONE);
                updateNavButtons();
                isOffline = false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (!request.isForMainFrame()) return;
                progressBar.setVisibility(View.GONE);
                if (!isConnected()) {
                    showOffline();
                } else {
                    showError(error != null ? String.valueOf(error.getDescription())
                            : "Koneksi bermasalah");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError error) {
                handler.cancel();
                showError("Sertifikat SSL tidak valid");
            }
        });
    }

    private void blockClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.addPrimaryClipChangedListener(() -> {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
                toast("Salin tidak diizinkan selama ujian");
            });
        }
    }

    private void registerScreenshotReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            IntentFilter filter = new IntentFilter("android.intent.action.SCREENSHOT");
            screenshotReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!sessionId.isEmpty()) {
                        ViolationReporter.laporkan(context, sessionId,
                                ViolationReporter.PERCOBAAN_SCREENSHOT,
                                "Screenshot terdeteksi", 0);
                    }
                }
            };
            registerReceiver(screenshotReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
    }

    private boolean isSameHostAsExam(Uri target) {
        try {
            String examHost = Uri.parse(examUrl).getHost();
            String targetHost = target.getHost();
            if (examHost == null || targetHost == null) return false;
            examHost = examHost.toLowerCase(Locale.ROOT);
            targetHost = targetHost.toLowerCase(Locale.ROOT);
            return targetHost.equals(examHost) || targetHost.endsWith("." + examHost);
        } catch (Exception e) {
            return false;
        }
    }

    private void loadExam() {
        showContent();
        webView.loadUrl(examUrl);
    }

    private void reload() {
        showContent();
        if (webView.getUrl() == null) {
            loadExam();
        } else {
            webView.reload();
        }
    }

    private void startExamTimerOnce() {
        if (timerStarted) return;
        timerStarted = true;
        if (session == null) {
            chronometer.setVisibility(View.GONE);
            return;
        }
        chronometer.setVisibility(View.VISIBLE);
        tampilkanSisa(session.sisaDetik());
    }

    private void tampilkanSisa(long detik) {
        long jam = detik / 3600;
        long menit = (detik % 3600) / 60;
        long detikSisa = detik % 60;

        String teks = jam > 0
                ? String.format(Locale.US, "%d:%02d:%02d", jam, menit, detikSisa)
                : String.format(Locale.US, "%02d:%02d", menit, detikSisa);
        chronometer.setText(teks);

        chronometer.setTextColor(getColor(
                detik <= 300 ? R.color.danger : R.color.chrome_ink_muted));
    }

    private void vibrate(int durationMs) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    vm.getDefaultVibrator()
                        .vibrate(VibrationEffect.createOneShot(durationMs,
                                VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) {
                    v.vibrate(VibrationEffect.createOneShot(durationMs,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                }
            }
        } catch (Exception ignored) {}
    }

    private final SessionManager.Listener sessionListener = new SessionManager.Listener() {
        @Override
        public void onTick(long sisaDetik) {
            tampilkanSisa(sisaDetik);

            if (!warned5 && sisaDetik <= 300 && sisaDetik > 60) {
                warned5 = true;
                toast(getString(R.string.exam_warning_5min));
            }
            if (!warned1 && sisaDetik <= 60) {
                warned1 = true;
                toast(getString(R.string.exam_warning_1min));
            }

            if (!warnedVibrate5 && sisaDetik == 300) {
                warnedVibrate5 = true;
                vibrate(1000);
            }
            if (!warnedVibrate1 && sisaDetik == 60) {
                warnedVibrate1 = true;
                vibrate(2000);
            }
        }

        @Override
        public void onSesiBerakhir(String alasan, String pesan) {
            tampilkanSesiBerakhir(alasan, pesan);
        }

        @Override
        public void onKoneksi(boolean daring) {
            tvWifi.setText(daring ? R.string.net_wifi : R.string.exam_offline_badge);
            tvWifi.setTextColor(getColor(daring ? R.color.chrome_ink_muted : R.color.danger));
        }
    };

    private void tampilkanSesiBerakhir(String alasan, String pesan) {
        if (isExiting) return;
        isExiting = true;

        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.setVisibility(View.GONE);
        }

        boolean dihentikan = "dihentikan".equalsIgnoreCase(alasan);

        // Jika dihentikan pengawas, langsung keluar tanpa dialog
        if (dihentikan) {
            SessionManager.hapusTersimpan(this);
            if (session != null) {
                session.setListener(null);
                session.berhenti();
            }
            ui.removeCallbacksAndMessages(null);
            cancelFocusCountdown();
            releaseWakeLock();
            try { stopLockTask(); } catch (Exception ignored) {}
            toast("Sesi dihentikan oleh pengawas");
            finishAndRemoveTask();
            return;
        }

        String judul = getString(R.string.exam_ended_title);
        String isi = (pesan == null || pesan.isEmpty())
                ? getString(R.string.exam_time_up) : pesan;

        SessionManager.hapusTersimpan(this);

        isOwnDialogVisible = true;
        new AlertDialog.Builder(this)
                .setTitle(judul)
                .setMessage(isi)
                .setCancelable(false)
                .setPositiveButton(R.string.exam_close, (d, w) -> exitApp())
                .show();
    }

    private void showContent() {
        webView.setVisibility(View.VISIBLE);
        layoutOffline.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
    }

    private void showOffline() {
        isOffline = true;
        layoutOffline.setVisibility(View.VISIBLE);
        layoutError.setVisibility(View.GONE);
    }

    private void showError(String description) {
        layoutError.setVisibility(View.VISIBLE);
        layoutOffline.setVisibility(View.GONE);
        tvErrorCode.setText(description);
    }

    private void setupControls() {
        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        findViewById(R.id.btn_refresh).setOnClickListener(v -> reload());
        findViewById(R.id.btn_retry).setOnClickListener(v -> reload());
        findViewById(R.id.btn_retry_error).setOnClickListener(v -> reload());
        findViewById(R.id.btn_exit).setOnClickListener(v -> showExitDialog());
        updateNavButtons();
        updateBattery();
        updateConnectionLabels();
    }

    private void updateNavButtons() {
        boolean back = webView.canGoBack();
        boolean forward = webView.canGoForward();
        btnBack.setEnabled(back);
        btnBack.setAlpha(back ? 1f : 0.3f);
        btnForward.setEnabled(forward);
        btnForward.setAlpha(forward ? 1f : 0.3f);
    }

    private void startClock() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                tvTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(new Date()));
                updateBattery();
                ui.postDelayed(this, CLOCK_INTERVAL_MS);
            }
        });
    }

    private void updateBattery() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm == null) return;
            int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (level < 0) return;
            tvBattery.setText(getString(R.string.battery_percent, level));
            tvBattery.setTextColor(getColor(
                    level <= 15 ? R.color.danger : R.color.chrome_ink_muted));
        } catch (Exception ignored) {}
    }

    private void startSpeedMonitor() {
        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();
        lastSpeedSampleAt = SystemClock.elapsedRealtime();

        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    long rx = TrafficStats.getTotalRxBytes();
                    long tx = TrafficStats.getTotalTxBytes();
                    long now = SystemClock.elapsedRealtime();
                    long elapsed = Math.max(1, now - lastSpeedSampleAt);

                    if (rx != TrafficStats.UNSUPPORTED && tx != TrafficStats.UNSUPPORTED) {
                        long bytesPerSecond =
                                ((rx - lastRxBytes) + (tx - lastTxBytes)) * 1000 / elapsed;
                        tvSpeed.setText(formatSpeed(Math.max(0, bytesPerSecond)));
                        lastRxBytes = rx;
                        lastTxBytes = tx;
                    }
                    lastSpeedSampleAt = now;
                } catch (Exception ignored) {}
                ui.postDelayed(this, SPEED_INTERVAL_MS);
            }
        });
    }

    private String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1024 * 1024) return (bytesPerSecond / 1024) + " KB/s";
        return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024f * 1024f));
    }

    private void startNetworkWatch() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                updateConnectionLabels();
                if (isOffline && isConnected()) {
                    isOffline = false;
                    reload();
                }
                ui.postDelayed(this, NET_CHECK_INTERVAL_MS);
            }
        });
    }

    private void updateConnectionLabels() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        try {
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null) {
                tvWifi.setText(R.string.net_offline);
                tvWifi.setTextColor(getColor(R.color.danger));
                tvSignal.setVisibility(View.GONE);
                return;
            }

            tvWifi.setTextColor(getColor(R.color.chrome_ink_muted));

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                tvWifi.setText(describeWifiStrength());
                tvSignal.setVisibility(View.GONE);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                tvWifi.setText(R.string.net_cellular);
                tvSignal.setVisibility(View.GONE);
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                tvWifi.setText(R.string.net_ethernet);
                tvSignal.setVisibility(View.GONE);
            } else {
                tvWifi.setText(R.string.net_generic);
                tvSignal.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private String describeWifiStrength() {
        try {
            WifiManager wm = (WifiManager)
                    getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "WiFi";
            int rssi = wm.getConnectionInfo().getRssi();
            int bars = WifiManager.calculateSignalLevel(rssi, 4);
            switch (bars) {
                case 0:  return getString(R.string.net_wifi_weak);
                default: return getString(R.string.net_wifi);
            }
        } catch (Exception e) {
            return getString(R.string.net_wifi);
        }
    }

    private boolean isConnected() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            if (focusLostAt > 0 && !sessionId.isEmpty()) {
                int detik = (int) ((SystemClock.elapsedRealtime() - focusLostAt) / 1000);
                if (detik >= 2) {
                    ViolationReporter.laporkan(this, sessionId,
                            ViolationReporter.KELUAR_APLIKASI,
                            "Kembali setelah " + detik + " detik di luar aplikasi",
                            detik);
                }
                focusLostAt = 0;
            }
            cancelFocusCountdown();
            applyImmersiveMode();
            requestLockTaskOnce();
            return;
        }

        if (isExiting) return;
        if (isOwnDialogVisible) return;
        if (isKeyboardVisible()) return;
        if (isScreenOff()) return;
        if (SecurityChecker.isLockTaskPinned(this)) return;

        focusLostAt = SystemClock.elapsedRealtime();
        if (!sessionId.isEmpty()) {
            ViolationReporter.laporkan(this, sessionId,
                    ViolationReporter.KELUAR_APLIKASI,
                    "Aplikasi kehilangan fokus", 0);
        }

        startFocusCountdown();
    }

    private boolean isScreenOff() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && !pm.isInteractive();
    }

    private boolean isKeyboardVisible() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        return imm != null && imm.isAcceptingText();
    }

    private void requestLockTaskOnce() {
        if (lockTaskRequested) return;
        if (SecurityChecker.isInLockTask(this)) return;
        lockTaskRequested = true;
        try {
            startLockTask();
        } catch (Exception e) {
            lockTaskRequested = false;
        }
    }

    private void startFocusCountdown() {
        if (focusDialog != null && focusDialog.isShowing()) return;

        SoundManager.playAlarm(this);

        TextView message = new TextView(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        message.setPadding(pad, pad, pad, pad);
        message.setTextColor(getColor(R.color.ink_muted));
        message.setTextSize(15f);

        focusDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.focus_title)
                .setView(message)
                .setCancelable(false)
                .create();
        focusDialog.show();

        ui.post(new Runnable() {
            long remaining = FOCUS_GRACE_MS / 1000;

            @Override
            public void run() {
                if (focusDialog == null || !focusDialog.isShowing()) return;
                if (remaining <= 0) {
                    exitApp();
                    return;
                }
                message.setText(getResources().getQuantityString(R.plurals.focus_body,
                        (int) remaining, (int) remaining));
                remaining--;
                ui.postDelayed(this, 1000L);
            }
        });
    }

    private void cancelFocusCountdown() {
        if (focusDialog != null) {
            try {
                if (focusDialog.isShowing()) focusDialog.dismiss();
            } catch (Exception ignored) {}
            focusDialog = null;
        }
        SoundManager.stop();
    }

    private void applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            @SuppressWarnings("deprecation")
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void showExitDialog() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        isOwnDialogVisible = true;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_exit, null);
        exitDialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .create();
        if (exitDialog.getWindow() != null) {
            exitDialog.getWindow()
                    .setBackgroundDrawableResource(android.R.color.transparent);
        }

        view.findViewById(R.id.btn_confirm_exit).setOnClickListener(v -> {
            exitDialog.dismiss();
            exitApp();
        });
        view.findViewById(R.id.btn_exit_temp).setOnClickListener(v -> {
            exitDialog.dismiss();
            exitTemporarily();
        });
        view.findViewById(R.id.btn_cancel_exit).setOnClickListener(v -> {
            exitDialog.dismiss();
            isOwnDialogVisible = false;
        });

        exitDialog.show();
    }

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        showExitDialog();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_CAMERA:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_SEARCH:
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private void exitApp() {
        if (isExiting) return;
        isExiting = true;

        if (!sessionId.isEmpty()) {
            SupabaseClient.akhiriSesi(this, sessionId, "keluar", null);
            SessionManager.hapusTersimpan(this);
        }
        if (session != null) {
            session.setListener(null);
            session.berhenti();
        }

        ui.removeCallbacksAndMessages(null);
        cancelFocusCountdown();
        releaseWakeLock();
        clearBrowsingData();

        try { stopLockTask(); } catch (Exception ignored) {}

        SoundManager.playExitThenRun(this, this::finishAndRemoveTask);
        ui.postDelayed(() -> {
            SoundManager.stop();
            finishAndRemoveTask();
        }, 2500L);
    }

    private void clearBrowsingData() {
        try {
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
            webView.clearSslPreferences();
            webView.loadUrl("about:blank");

            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();

            deleteRecursively(getCacheDir());
            deleteRecursively(getExternalCacheDir());
        } catch (Exception ignored) {}
    }

    private static void deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        wakeLock = null;
    }

    private void registerHeadsetReceiver() {
        headsetReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) return;
                if (intent.getIntExtra("state", -1) == 1) {
                    toast(getString(R.string.headset_blocked));
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(headsetReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(headsetReceiver, filter);
        }
    }

    private void toast(@NonNull String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final int REQ_CAMERA = 2001;

    private void mulaiDeteksiWajah() {
        if (sessionId.isEmpty()) return;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }

        faceDetectionManager = new FaceDetectionManager(this, sessionId);
        faceDetectionManager.setCallback(new FaceDetectionManager.Callback() {
            @Override
            public void onFaceDetected(boolean adaWajah, int jumlah) {
                // Wajah terdeteksi, tidak perlu aksi
            }

            @Override
            public void onError(String pesan) {
                // Error detection, log saja
            }
        });
        faceDetectionManager.mulai();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                mulaiDeteksiWajah();
            } else {
                // Kamera ditolak - keluar dari ujian
                toast("Izin kamera wajib untuk mengikuti ujian");
                ui.postDelayed(this::exitApp, 2000);
            }
        }
    }

    private void exitTemporarily() {
        if (isExiting) return;
        isExiting = true;

        if (!sessionId.isEmpty()) {
            ViolationReporter.laporkan(this, sessionId,
                    ViolationReporter.KELUAR_APLIKASI,
                    "Keluar sementara dari ujian", 0);

            SupabaseClient.catatPelanggaran(this, sessionId,
                    "masuk_ulang", "Siswa keluar sementara", 0, b -> {});
        }

        if (session != null) {
            session.setListener(null);
            session.berhenti();
        }

        ui.removeCallbacksAndMessages(null);
        cancelFocusCountdown();
        releaseWakeLock();
        clearBrowsingData();

        try { stopLockTask(); } catch (Exception ignored) {}

        new android.app.AlertDialog.Builder(this)
                .setTitle("Sesi Dijeda")
                .setMessage("Sesi ujian Anda dijeda. Masukkan token lagi untuk melanjutkan.")
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> {
                    SoundManager.playExitThenRun(this, this::finishAndRemoveTask);
                    ui.postDelayed(() -> {
                        SoundManager.stop();
                        finishAndRemoveTask();
                    }, 2500L);
                })
                .show();
    }

}
