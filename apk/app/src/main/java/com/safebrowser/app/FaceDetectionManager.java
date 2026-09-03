package com.safebrowser.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deteksi wajah periodik menggunakan kamera depan + ML Kit.
 * Menangkap frame tiap ~5 detik, memeriksa apakah ada wajah,
 * dan melaporkan status ke server.
 */
public final class FaceDetectionManager {

    private static final long CAPTURE_INTERVAL_MS = 5_000L;
    private static final int IMAGE_WIDTH = 320;
    private static final int IMAGE_HEIGHT = 240;

    public interface Callback {
        void onFaceDetected(boolean adaWajah, int jumlah);
        void onError(String pesan);
    }

    private final Context context;
    private final String sessionId;
    @Nullable private Callback callback;

    @Nullable private CameraDevice cameraDevice;
    @Nullable private CameraCaptureSession captureSession;
    @Nullable private ImageReader imageReader;
    @Nullable private HandlerThread cameraThread;
    @Nullable private Handler cameraHandler;

    private final FaceDetector faceDetector;
    private final AtomicBoolean sedangCapture = new AtomicBoolean(false);
    private final AtomicBoolean aktif = new AtomicBoolean(false);
    private long lastCaptureAt;

    public FaceDetectionManager(@NonNull Context context, @NonNull String sessionId) {
        this.context = context.getApplicationContext();
        this.sessionId = sessionId;

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.1f)
                .build();

        this.faceDetector = FaceDetection.getClient(options);
    }

    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    /**
     * Mulai deteksi wajah periodik.
     * Membutuhkan izin CAMERA.
     */
    public void mulai() {
        if (aktif.getAndSet(true)) return;

        cameraThread = new HandlerThread("FaceDetectThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        bukaKamera();
    }

    private void tutupKamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Exception ignored) {}
    }

    /**
     * Hentikan deteksi wajah dan lepaskan resources.
     */
    public void berhenti() {
        if (!aktif.getAndSet(false)) return;

        tutupKamera();
        faceDetector.close();

        if (cameraThread != null) {
            cameraThread.quitSafely();
            try { cameraThread.join(1000); } catch (Exception ignored) {}
            cameraThread = null;
        }
        cameraHandler = null;
    }

    private void bukaKamera() {
        if (!aktif.get()) return;

        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            if (callback != null) callback.onError("Camera service tidak tersedia");
            return;
        }

        try {
            String cameraId = getFrontCameraId(cm);
            if (cameraId == null) {
                if (callback != null) callback.onError("Kamera depan tidak ditemukan");
                return;
            }

            CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                if (callback != null) callback.onError("Stream config tidak tersedia");
                return;
            }

            android.util.Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            android.util.Size size = sizes != null && sizes.length > 0 ? sizes[0] : new android.util.Size(IMAGE_WIDTH, IMAGE_HEIGHT);

            imageReader = ImageReader.newInstance(
                    size.getWidth(), size.getHeight(),
                    ImageFormat.YUV_420_888, 1);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                if (callback != null) callback.onError("Izin kamera belum diberikan");
                return;
            }

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    mulaiCapture();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    if (callback != null) callback.onError("Kamera error: " + error);
                }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            if (callback != null) callback.onError("Akses kamera ditolak");
        }
    }

    @Nullable
    private String getFrontCameraId(CameraManager cm) throws CameraAccessException {
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics chars = cm.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id;
            }
        }
        return null;
    }

    private void mulaiCapture() {
        if (!aktif.get() || cameraDevice == null || imageReader == null) return;

        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());

            cameraDevice.createCaptureSession(
                    Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            mulaiPeriodikCapture();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            if (callback != null) callback.onError("Gagal setup capture");
                        }
                    }, cameraHandler);

        } catch (CameraAccessException e) {
            if (callback != null) callback.onError("Gagal membuat capture request");
        }
    }

    private void mulaiPeriodikCapture() {
        if (!aktif.get()) return;

        cameraHandler.postDelayed(() -> {
            if (!aktif.get()) return;

            long now = SystemClock.elapsedRealtime();
            if (now - lastCaptureAt >= CAPTURE_INTERVAL_MS && !sedangCapture.get()) {
                captureFrame();
            }

            mulaiPeriodikCapture();
        }, 1000L);
    }

    private void captureFrame() {
        if (cameraDevice == null || imageReader == null) return;
        if (!sedangCapture.compareAndSet(false, true)) return;

        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());

            captureSession.capture(builder.build(), null, cameraHandler);
            lastCaptureAt = SystemClock.elapsedRealtime();
        } catch (CameraAccessException e) {
            sedangCapture.set(false);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;

            Bitmap bitmap = imageToBitmap(image);
            if (bitmap != null) {
                deteksiWajah(bitmap);
            }
        } catch (Exception e) {
            if (callback != null) callback.onError("Gagal memproses gambar");
        } finally {
            sedangCapture.set(false);
            if (image != null) image.close();
        }
    }

    @Nullable
    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21,
                    image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 50, out);
            byte[] jpegBytes = out.toByteArray();

            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

            // Rotate if needed
            Matrix matrix = new Matrix();
            matrix.postRotate(90);
            return Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (Exception e) {
            return null;
        }
    }

    private void deteksiWajah(Bitmap bitmap) {
        InputImage image = InputImage.fromBitmap(bitmap, 0);

        faceDetector.process(image)
                .addOnSuccessListener(faces -> {
                    boolean adaWajah = !faces.isEmpty();
                    int jumlah = faces.size();

                    if (callback != null) {
                        callback.onFaceDetected(adaWajah, jumlah);
                    }

                    // Laporkan ke server jika tidak ada wajah
                    if (!adaWajah && !sessionId.isEmpty()) {
                        ViolationReporter.laporkan(context, sessionId,
                                "wajah_tidak_terdeteksi",
                                "Tidak ada wajah terdeteksi di kamera", 0);
                    }

                    bitmap.recycle();
                })
                .addOnFailureListener(e -> {
                    if (callback != null) {
                        callback.onError("Deteksi wajah gagal: " + e.getMessage());
                    }
                    bitmap.recycle();
                });
    }
}
