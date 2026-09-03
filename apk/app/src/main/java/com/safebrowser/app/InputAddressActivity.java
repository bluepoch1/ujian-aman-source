package com.safebrowser.app;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Animation;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gerbang masuk: pemeriksaan perangkat, penukaran token, dan pemindaian QR.
 */
public class InputAddressActivity extends AppCompatActivity {

    private static final int REQ_CAMERA_SCAN = 100;
    private static final int REQ_GALLERY = 101;
    private static final int REQ_CAMERA_PERMISSION = 200;

    private AppCompatEditText etToken;
    private AppCompatEditText etNama;
    private AppCompatEditText etNomor;
    private AppCompatButton btnOpen;
    private ProgressBar progressBar;
    private TextView tvFieldError;
    private TextView badgeText;
    private View badgeDevice;

    @Nullable private AlertDialog activeCheckDialog;
    private boolean isCheckingToken;

    private final Handler securityHandler = new Handler(Looper.getMainLooper());
    private final Runnable securityRunnable = new Runnable() {
        @Override
        public void run() {
            blockStatusBar();
            securityHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_input_address);

        // Immersive mode — sembunyikan status bar & navigation bar
        hideSystemUI();

        etToken      = findViewById(R.id.et_url);
        etNama       = findViewById(R.id.et_nama);
        etNomor      = findViewById(R.id.et_nomor);
        btnOpen      = findViewById(R.id.btn_open);
        progressBar  = findViewById(R.id.progress_bar);
        tvFieldError = findViewById(R.id.tv_field_error);
        badgeDevice  = findViewById(R.id.badge_device);
        badgeText    = findViewById(R.id.badge_text);

        btnOpen.setOnClickListener(v -> submitToken());
        findViewById(R.id.btn_scan_qr_inline).setOnClickListener(v -> showScanOptions());

        etToken.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                clearFieldError();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        playEntryAnimations();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        runSecurityCheck();
        securityHandler.postDelayed(securityRunnable, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        securityHandler.removeCallbacks(securityRunnable);
    }

    @Override
    protected void onDestroy() {
        securityHandler.removeCallbacks(securityRunnable);
        dismissCheckDialog();
        super.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────
    //  Animations
    // ─────────────────────────────────────────────────────────────

    private void playEntryAnimations() {
        View logo = findViewById(R.id.logo_container);
        View badge = findViewById(R.id.badge_device);
        View tokenLabel = etToken;
        View tokenRow = findViewById(R.id.input_row);
        View nameInput = etNama;
        View nomorInput = etNomor;
        View submitBtn = btnOpen;

        if (logo != null) {
            Animation logoAnim = AnimationUtils.loadAnimation(this, R.anim.logo_scale_in);
            logo.startAnimation(logoAnim);
        }

        if (badge != null) {
            Animation badgeAnim = AnimationUtils.loadAnimation(this, R.anim.badge_pop);
            badge.startAnimation(badgeAnim);
        }

        if (tokenLabel != null) {
            Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade);
            tokenLabel.startAnimation(slideUp);
        }

        if (tokenRow != null) {
            Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_delayed);
            tokenRow.startAnimation(slideUp);
        }

        if (nameInput != null) {
            Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_delayed);
            nameInput.startAnimation(slideUp);
        }

        if (nomorInput != null) {
            Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_delayed2);
            nomorInput.startAnimation(slideUp);
        }

        if (submitBtn != null) {
            Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_delayed2);
            submitBtn.startAnimation(slideUp);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Security: Block system UI
    // ─────────────────────────────────────────────────────────────

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_IMMERSIVE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void blockStatusBar() {
        try {
            Intent closeStatusBar = new Intent("android.intent.action.CLOSE_SYSTEM_DIALOGS");
            sendBroadcast(closeStatusBar);
        } catch (Exception ignored) { }
    }

    // ─────────────────────────────────────────────────────────────
    //  Pemeriksaan perangkat
    // ─────────────────────────────────────────────────────────────

    private void runSecurityCheck() {
        if (isFinishing() || isDestroyed()) return;

        if (SecurityChecker.isRunningOnEmulator()) {
            showBlockDialog(R.string.check_emulator_title, R.string.check_emulator_body, false);
            return;
        }
        if (SecurityChecker.isRooted(this)) {
            showBlockDialog(R.string.check_root_title, R.string.check_root_body, false);
            return;
        }
        if (SecurityChecker.isUsbDebuggingEnabled(this)) {
            showBlockDialog(R.string.check_adb_title, R.string.check_adb_body, true);
            return;
        }
        if (SecurityChecker.isMockLocationEnabled(this)) {
            showBlockDialog(R.string.check_mock_title, R.string.check_mock_body, true);
            return;
        }

        List<ApplicationInfo> blocked = SecurityChecker.getDangerousInstalledApps(this);
        if (!blocked.isEmpty()) {
            showBlockedAppsDialog(blocked);
            return;
        }

        if (SecurityChecker.hasSuspiciousAccessibilityServices(this)) {
            showBlockDialog(R.string.check_a11y_title, R.string.check_a11y_body, true);
            return;
        }

        if (SecurityChecker.isVpnActive(this)) {
            showBlockDialog(R.string.check_vpn_title, R.string.check_vpn_body, true);
            return;
        }

        dismissCheckDialog();
        setDeviceBadge(true, blocked.size());
    }

    private void setDeviceBadge(boolean safe, int issueCount) {
        if (badgeDevice == null) return;
        if (safe) {
            badgeDevice.setBackgroundResource(R.drawable.chip_success);
            View dot = findViewById(R.id.dot_status); dot.setBackgroundResource(R.drawable.dot_green);
            
            badgeText.setTextColor(getColor(R.color.success));
            badgeText.setText(R.string.badge_safe);
        } else {
            badgeDevice.setBackgroundResource(R.drawable.chip_danger);
            View dot = findViewById(R.id.dot_status); dot.setBackgroundResource(R.drawable.dot_red);
            
            badgeText.setTextColor(getColor(R.color.danger));
            badgeText.setText(issueCount > 0
                    ? getString(R.string.badge_issues, issueCount)
                    : getString(R.string.badge_blocked));
        }
    }

    private void dismissCheckDialog() {
        if (activeCheckDialog != null) {
            try {
                if (activeCheckDialog.isShowing()) activeCheckDialog.dismiss();
            } catch (Exception ignored) { }
            activeCheckDialog = null;
        }
    }

    private void showBlockDialog(@StringRes int titleRes, @StringRes int bodyRes,
                                 boolean canOpenSettings) {
        setDeviceBadge(false, 0);
        if (activeCheckDialog != null && activeCheckDialog.isShowing()) return;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_block, null);
        AlertDialog dialog = buildSheet(view);

        ((TextView) view.findViewById(R.id.tv_block_title)).setText(titleRes);
        ((TextView) view.findViewById(R.id.tv_block_message)).setText(bodyRes);

        View btnSettings = view.findViewById(R.id.btn_buka_settings);
        btnSettings.setVisibility(canOpenSettings ? View.VISIBLE : View.GONE);
        btnSettings.setOnClickListener(v -> openSettings());

        view.findViewById(R.id.btn_recheck).setOnClickListener(v -> {
            dismissCheckDialog();
            runSecurityCheck();
        });
        view.findViewById(R.id.btn_keluar_block).setOnClickListener(v -> quitApp());

        activeCheckDialog = dialog;
        dialog.show();
    }

    private void showBlockedAppsDialog(List<ApplicationInfo> apps) {
        setDeviceBadge(false, apps.size());
        if (activeCheckDialog != null && activeCheckDialog.isShowing()) {
            View existing = activeCheckDialog.findViewById(R.id.container_apps);
            if (existing != null) {
                bindBlockedApps((LinearLayout) existing, apps);
                return;
            }
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_security_apps, null);
        AlertDialog dialog = buildSheet(view);

        LinearLayout container = view.findViewById(R.id.container_apps);
        bindBlockedApps(container, apps);

        view.findViewById(R.id.btn_sudah_hapus).setOnClickListener(v -> {
            List<ApplicationInfo> remaining = SecurityChecker.getDangerousInstalledApps(this);
            if (remaining.isEmpty()) {
                dismissCheckDialog();
                setDeviceBadge(true, 0);
                toast(R.string.check_apps_cleared);
            } else {
                bindBlockedApps(container, remaining);
                setDeviceBadge(false, remaining.size());
                toast(getResources().getQuantityString(R.plurals.check_apps_remaining,
                        remaining.size(), remaining.size()));
            }
        });
        view.findViewById(R.id.btn_keluar_security).setOnClickListener(v -> quitApp());

        activeCheckDialog = dialog;
        dialog.show();
    }

    private void bindBlockedApps(LinearLayout container, List<ApplicationInfo> apps) {
        container.removeAllViews();
        PackageManager pm = getPackageManager();
        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < apps.size(); i++) {
            ApplicationInfo app = apps.get(i);
            View row = inflater.inflate(R.layout.item_blocked_app, container, false);

            AppCompatImageView icon = row.findViewById(R.id.app_icon);
            try {
                icon.setImageDrawable(pm.getApplicationIcon(app.packageName));
            } catch (Exception e) {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            ((TextView) row.findViewById(R.id.app_name))
                    .setText(SecurityChecker.getAppName(this, app));
            ((TextView) row.findViewById(R.id.app_package))
                    .setText(app.packageName);

            row.findViewById(R.id.app_uninstall).setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_DELETE,
                        Uri.parse("package:" + app.packageName));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    toast(R.string.uninstaller_failed);
                }
            });

            container.addView(row);

            if (i < apps.size() - 1) {
                View divider = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                divider.setLayoutParams(lp);
                divider.setBackgroundColor(getColor(R.color.border));
                container.addView(divider);
            }
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                toast(R.string.settings_failed);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Token
    // ─────────────────────────────────────────────────────────────

    private void submitToken() {
        if (isCheckingToken) return;

        String token = etToken.getText().toString().trim();
        String nama  = etNama.getText().toString().trim();
        String nomor = etNomor.getText().toString().trim();

        if (TextUtils.isEmpty(token)) {
            showFieldError(R.string.err_token_empty);
            return;
        }
        if (!token.matches("\\d+")) {
            showFieldError(R.string.err_token_format);
            return;
        }
        if (TextUtils.isEmpty(nama)) {
            showFieldError(R.string.err_nama_empty);
            etNama.requestFocus();
            return;
        }
        if (nama.length() < 2) {
            showFieldError(R.string.err_nama_pendek);
            etNama.requestFocus();
            return;
        }

        clearFieldError();
        setChecking(true);

        SupabaseClient.klaimSesi(this, token, nama, nomor,
                new SupabaseClient.SesiCallback() {
                    @Override
                    public void onBerhasil(SupabaseClient.Sesi sesi) {
                        setChecking(false);
                        if (isFinishing() || isDestroyed()) return;
                        showConfirmDialog(sesi);
                    }

                    @Override
                    public void onGagal(SupabaseClient.Gagal gagal) {
                        setChecking(false);
                        if (isFinishing() || isDestroyed()) return;
                        tvFieldError.setText(gagal.pesan);
                        tvFieldError.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void setChecking(boolean checking) {
        isCheckingToken = checking;
        btnOpen.setEnabled(!checking);
        btnOpen.setText(checking ? R.string.gate_checking : R.string.gate_open);
        if (progressBar != null) {
            progressBar.setVisibility(checking ? View.VISIBLE : View.GONE);
        }
    }

    private void showFieldError(@StringRes int messageRes) {
        tvFieldError.setText(messageRes);
        tvFieldError.setVisibility(View.VISIBLE);
    }

    private void clearFieldError() {
        if (tvFieldError.getVisibility() == View.VISIBLE) {
            tvFieldError.setVisibility(View.GONE);
        }
    }

    private void showConfirmDialog(SupabaseClient.Sesi sesi) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_token_confirm, null);
        AlertDialog dialog = buildSheet(view);

        String judul = sesi.namaKelas;
        if (!sesi.mataPelajaran.isEmpty()) {
            judul = judul + " — " + sesi.mataPelajaran;
        }
        ((TextView) view.findViewById(R.id.tv_kelas)).setText(judul);

        ((TextView) view.findViewById(R.id.tv_durasi))
                .setText(sesi.durasiMenit > 0
                        ? getString(R.string.duration_minutes, sesi.durasiMenit)
                        : getString(R.string.value_none));

        TextView tvExpired = view.findViewById(R.id.tv_expired);
        if (sesi.batasWaktu != null) {
            SimpleDateFormat out = new SimpleDateFormat("d MMM yyyy, HH:mm",
                    new Locale("id", "ID"));
            tvExpired.setText(out.format(sesi.batasWaktu));
        } else {
            tvExpired.setText(R.string.value_none);
        }

        if (sesi.masukUlang) {
            tvFieldError.setText(R.string.exam_reentry);
            tvFieldError.setVisibility(View.VISIBLE);
        }

        view.findViewById(R.id.btn_mulai_ujian).setOnClickListener(v -> {
            dialog.dismiss();
            startExam(sesi);
        });
        view.findViewById(R.id.btn_batal_token).setOnClickListener(v -> dialog.dismiss());

        dialog.setCancelable(true);
        dialog.show();

        SoundManager.playTokenOk(this);
    }

    private void startExam(SupabaseClient.Sesi sesi) {
        SessionManager.simpan(this, sesi);
        SoundManager.playExamStart(this);

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_URL, sesi.url);
        intent.putExtra(MainActivity.EXTRA_SESSION_ID, sesi.sessionId);
        intent.putExtra(MainActivity.EXTRA_SISA_DETIK, sesi.sisaDetik);
        intent.putExtra(MainActivity.EXTRA_NAMA_KELAS, sesi.namaKelas);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        finish();
    }

    // ─────────────────────────────────────────────────────────────
    //  Pemindaian QR
    // ─────────────────────────────────────────────────────────────

    private void showScanOptions() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_scan_options, null);
        AlertDialog dialog = buildSheet(view);
        dialog.setCancelable(true);

        view.findViewById(R.id.btn_scan_camera).setOnClickListener(v -> {
            dialog.dismiss();
            requestCameraScan();
        });
        view.findViewById(R.id.btn_scan_gallery).setOnClickListener(v -> {
            dialog.dismiss();
            openGallery();
        });
        view.findViewById(R.id.btn_scan_cancel).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void requestCameraScan() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA},
                    REQ_CAMERA_PERMISSION);
            return;
        }
        startActivityForResult(new Intent(this, QRScanActivity.class), REQ_CAMERA_SCAN);
    }

    private void openGallery() {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "image/*");
            startActivityForResult(intent, REQ_GALLERY);
        } catch (Exception e) {
            toast(R.string.gallery_unavailable);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startActivityForResult(new Intent(this, QRScanActivity.class),
                        REQ_CAMERA_SCAN);
            } else {
                toast(R.string.scan_camera_denied);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_CAMERA_SCAN) {
            handleScanResult(data.getStringExtra(QRScanActivity.EXTRA_RESULT));
        } else if (requestCode == REQ_GALLERY && data.getData() != null) {
            handleScanResult(decodeQrFromImage(data.getData()));
        }
    }

    private void handleScanResult(@Nullable String text) {
        if (TextUtils.isEmpty(text)) {
            toast(R.string.scan_no_code);
            return;
        }
        String value = text.trim();

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4,12})\\s*$")
                .matcher(value);

        String token = null;
        if (value.matches("\\d{4,12}")) {
            token = value;
        } else if (value.toLowerCase(Locale.ROOT).startsWith("safebrowser:") && m.find()) {
            token = m.group(1);
        }

        if (token == null) {
            toast(R.string.scan_bukan_token);
            return;
        }

        etToken.setText(token);
        etToken.setSelection(token.length());

        if (etNama.getText().toString().trim().length() < 2) {
            showFieldError(R.string.err_nama_empty);
            etNama.requestFocus();
            return;
        }
        submitToken();
    }

    @Nullable
    private String decodeQrFromImage(Uri uri) {
        try {
            Bitmap bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source =
                        ImageDecoder.createSource(getContentResolver(), uri);
                bitmap = ImageDecoder.decodeBitmap(source,
                        (decoder, info, s) -> {
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            decoder.setMutableRequired(false);
                        });
            } else {
                @SuppressWarnings("deprecation")
                Bitmap legacy = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
                bitmap = legacy;
            }
            if (bitmap == null) return null;

            int max = 1600;
            if (bitmap.getWidth() > max || bitmap.getHeight() > max) {
                float scale = Math.min((float) max / bitmap.getWidth(),
                        (float) max / bitmap.getHeight());
                bitmap = Bitmap.createScaledBitmap(bitmap,
                        Math.round(bitmap.getWidth() * scale),
                        Math.round(bitmap.getHeight() * scale), true);
            }

            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

            RGBLuminanceSource source = new RGBLuminanceSource(w, h, pixels);
            BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new QRCodeReader().decode(binary);
            return result != null ? result.getText() : null;

        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.check_quit)
                .setMessage(R.string.quit_confirm_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_close, (d, w) -> quitApp())
                .show();
    }

    private void quitApp() {
        dismissCheckDialog();
        try {
            stopLockTask();
        } catch (Exception ignored) { }
        finishAndRemoveTask();
    }

    private AlertDialog buildSheet(View content) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setCancelable(false)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        return dialog;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(@StringRes int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
