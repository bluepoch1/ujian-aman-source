package com.safebrowser.app;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pemindai QR berbasis API Camera lama.
 *
 * API ini memang usang, tetapi tetap dipakai secara sengaja: aplikasi ini
 * berjalan di ponsel murah milik sekolah, dan Camera1 punya jangkauan
 * perangkat terluas tanpa menambah dependensi CameraX.
 */
public class QRScanActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    public static final String EXTRA_RESULT = "SCAN_RESULT";
    private static final String TAG = "QRScan";
    private static final int REQ_CAMERA = 100;

    @Nullable private Camera camera;
    private SurfaceView surfaceView;

    /**
     * Pendekodean dipindah ke thread latar. Versi lama menjalankan ZXing pada
     * setiap frame preview di thread utama, yang membekukan UI dan membuat
     * pemindaian terasa rusak di perangkat lambat.
     */
    @Nullable private HandlerThread decodeThread;
    @Nullable private Handler decodeHandler;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final QRCodeReader reader = new QRCodeReader();
    private volatile boolean isScanning = true;
    private volatile boolean isDecoding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scan);

        surfaceView = findViewById(R.id.surface_view);
        findViewById(R.id.btn_cancel_scan).setOnClickListener(v -> finish());

        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA},
                    REQ_CAMERA);
        } else {
            surfaceView.getHolder().addCallback(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isScanning = true;
        decodeThread = new HandlerThread("qr-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());
    }

    @Override
    protected void onPause() {
        isScanning = false;
        releaseCamera();
        if (decodeThread != null) {
            decodeThread.quitSafely();
            decodeThread = null;
            decodeHandler = null;
        }
        super.onPause();
    }

    // ─────────────────────────────────────────────────────────────

    private final Camera.PreviewCallback previewCallback = (data, cam) -> {
        if (!isScanning || isDecoding || decodeHandler == null || data == null) return;

        Camera.Parameters params;
        try {
            params = cam.getParameters();
        } catch (Exception e) {
            return;
        }
        Camera.Size size = params.getPreviewSize();
        if (size == null) return;

        isDecoding = true;
        final int width = size.width;
        final int height = size.height;
        final byte[] frame = data.clone();

        decodeHandler.post(() -> {
            String text = decode(frame, width, height);
            isDecoding = false;
            if (text == null || !isScanning) return;

            isScanning = false;
            main.post(() -> {
                SoundManager.playBeep(this);
                Intent result = new Intent();
                result.putExtra(EXTRA_RESULT, text);
                setResult(RESULT_OK, result);
                finish();
            });
        });
    };

    @Nullable
    private String decode(byte[] data, int width, int height) {
        try {
            // Pendekodean dibatasi pada persegi tengah — area yang sama dengan
            // bingkai pindai di layar. Ini lebih cepat dan mencegah kode QR
            // lain di dekatnya ikut terbaca.
            int square = (int) (Math.min(width, height) * 0.7f);
            int left = (width - square) / 2;
            int top = (height - square) / 2;

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    data, width, height, left, top, square, square, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS,
                    Arrays.asList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");

            Result result = reader.decode(bitmap, hints);
            return result != null ? result.getText() : null;

        } catch (Exception e) {
            return null;   // tidak ada kode pada frame ini
        } finally {
            reader.reset();
        }
    }

    // ─────────────────────────────────────────────────────────────

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            if (camera == null) {
                fail();
                return;
            }

            Camera.Parameters params = camera.getParameters();
            Camera.Size preview = pickPreviewSize(params.getSupportedPreviewSizes());
            if (preview != null) {
                params.setPreviewSize(preview.width, preview.height);
            }

            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }
            params.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(params);
            camera.setDisplayOrientation(90);

            if (preview != null) {
                applyAspectRatio(preview);
            }

            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(previewCallback);
            camera.startPreview();

        } catch (Exception e) {
            Log.w(TAG, "Kamera gagal dibuka", e);
            fail();
        }
    }

    /**
     * Preview kamera bersifat lanskap dan diputar 90°. Kita perbesar tampilan
     * agar menutupi layar sambil mempertahankan rasio aspek, jika tidak preview
     * akan tampak gepeng.
     */
    private void applyAspectRatio(Camera.Size preview) {
        int rotatedWidth = preview.height;
        int rotatedHeight = preview.width;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        float scale = Math.max((float) screenWidth / rotatedWidth,
                (float) screenHeight / rotatedHeight);

        ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
        lp.width = Math.round(rotatedWidth * scale);
        lp.height = Math.round(rotatedHeight * scale);
        surfaceView.setLayoutParams(lp);

        surfaceView.setX((screenWidth - lp.width) / 2f);
        surfaceView.setY((screenHeight - lp.height) / 2f);
    }

    @Nullable
    private Camera.Size pickPreviewSize(@Nullable List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        float targetRatio = (float) Math.max(screenWidth, screenHeight)
                / Math.min(screenWidth, screenHeight);

        Camera.Size best = null;
        float bestScore = Float.MAX_VALUE;

        for (Camera.Size size : sizes) {
            // Resolusi sangat tinggi memperlambat pendekodean tanpa manfaat;
            // 1280 piksel sudah lebih dari cukup untuk kode QR.
            if (size.width > 1280) continue;
            float ratio = (float) size.width / size.height;
            float score = Math.abs(ratio - targetRatio)
                    + Math.abs(size.width - 960) / 4000f;
            if (score < bestScore) {
                bestScore = score;
                best = size;
            }
        }
        return best != null ? best : sizes.get(0);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (camera == null || holder.getSurface() == null) return;
        try {
            camera.stopPreview();
            camera.setPreviewDisplay(holder);
            camera.startPreview();
        } catch (Exception e) {
            Log.w(TAG, "Preview tidak dapat dimulai ulang", e);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void releaseCamera() {
        if (camera == null) return;
        try {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
        } catch (Exception ignored) {
            // kamera sudah dilepas
        }
        camera = null;
    }

    private void fail() {
        Toast.makeText(this, R.string.scan_camera_failed, Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;

        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // recreate() pada versi lama memicu perulangan izin tak berujung.
            surfaceView.getHolder().addCallback(this);
        } else {
            Toast.makeText(this, R.string.scan_camera_denied, Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        super.onDestroy();
    }
}
