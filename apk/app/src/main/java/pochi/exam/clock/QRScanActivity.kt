package pochi.exam.clock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult

class QRScanActivity : AppCompatActivity() {

    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val intentResult = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
        if (intentResult != null && intentResult.contents != null) {
            val scanned = intentResult.contents
            val token = extractToken(scanned)
            if (token != null) {
                val i = Intent(this, InputAddressActivity::class.java)
                i.putExtra("scanned_token", token)
                i.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(i)
                finish()
            } else {
                Toast.makeText(this, "QR tidak valid", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            finish()
        }
    }

    private fun startScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Scan QR Code Ujian")
        integrator.setBeepEnabled(true)
        integrator.setOrientationLocked(true)
        scanner.launch(integrator.createScanIntent())
    }

    private fun extractToken(text: String): String? {
        val m = Regex("(\\d{4,12})\\s*$").find(text)
        return m?.groupValues?.get(1)
            ?: if (text.trim().matches(Regex("\\d{4,12}"))) text.trim() else null
    }
}
