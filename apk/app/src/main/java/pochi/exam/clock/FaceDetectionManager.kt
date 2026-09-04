package pochi.exam.clock

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceDetectionManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onNoFace: () -> Unit
) {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
    )
    private var analyzing = false
    private var lastCheck = 0L

    fun mulai() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { img -> analisis(img) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e("FaceDetect", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun hentikan() {
        analyzing = false
        detector.close()
    }

    private fun analisis(img: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 5000) { img.close(); return }
        lastCheck = now
        if (analyzing) { img.close(); return }
        analyzing = true

        try {
            val mediaImage = img.image ?: run { analyzing = false; img.close(); return }
            val image = InputImage.fromMediaImage(mediaImage, img.imageInfo.rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) onNoFace()
                    analyzing = false
                }
                .addOnFailureListener { analyzing = false }
                .addOnCompleteListener { img.close() }
        } catch (e: Exception) {
            analyzing = false
            img.close()
        }
    }
}
