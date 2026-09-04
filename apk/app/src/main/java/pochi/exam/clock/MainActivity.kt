package pochi.exam.clock

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import pochi.exam.clock.databinding.ActivityMainBinding
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var sessionId = ""
    private var examUrl = ""
    private var timer: CountDownTimer? = null
    private var sisaDetik = 0L
    private var tabSwitchCount = 0
    private var lastFocusLost = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var faceManager: FaceDetectionManager? = null

    private val tabSwitchRunnable = Runnable {
        tabSwitchCount++
        ViolationReporter.laporkan("tab_switch", "Tab switch #$tabSwitchCount", 0, this)
        if (tabSwitchCount >= 3) {
            exitApp("Terlalu banyak ganti tab.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        sessionId = intent.getStringExtra("session_id") ?: ""
        examUrl = intent.getStringExtra("url") ?: ""
        val namaKelas = intent.getStringExtra("nama_kelas") ?: ""
        val mapel = intent.getStringExtra("mata_pelajaran") ?: ""
        val nama = intent.getStringExtra("nama_peserta") ?: ""
        sisaDetik = intent.getLongExtra("sisa_detik", 0)
        val masukUlang = intent.getBooleanExtra("masuk_ulang", false)

        b.tvNama.text = "$namaKelas - $mapel"
        b.tvPeserta.text = nama

        if (masukUlang) {
            Toast.makeText(this, "Anda masuk kembali ke ujian.", Toast.LENGTH_SHORT).show()
        }

        setupWebView()
        startTimer()
        startHeartbeat()

        faceManager = FaceDetectionManager(this, this, b.previewCamera) {
            ViolationReporter.laporkan("wajah_tidak_terdeteksi", "Wajah tidak terlihat", 0, this)
        }
        faceManager?.mulai()

        b.btnBack.setOnClickListener { exitTemporarily() }
        b.btnForward.setOnClickListener {
            b.webView.evaluateJavascript("document.querySelector(\"input[type=submit],button[type=submit]\")?.click()") {}
        }
    }

    private fun setupWebView() {
        b.webView.settings.javaScriptEnabled = true
        b.webView.settings.domStorageEnabled = true
        b.webView.settings.allowFileAccess = false
        b.webView.settings.allowContentAccess = false
        b.webView.webViewClient = WebViewClient()
        b.webView.webChromeClient = WebChromeClient()
        b.webView.loadUrl(examUrl)
    }

    private fun startTimer() {
        timer = object : CountDownTimer(sisaDetik * 1000, 1000) {
            override fun onTick(millis: Long) {
                val jam = TimeUnit.MILLISECONDS.toHours(millis)
                val menit = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
                val detik = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
                b.tvTimer.text = if (jam > 0) {
                    String.format(Locale.US, "%d:%02d:%02d", jam, menit, detik)
                } else {
                    String.format(Locale.US, "%02d:%02d", menit, detik)
                }
            }
            override fun onFinish() {
                exitApp("Waktu ujian habis.")
            }
        }.start()
    }

    private fun startHeartbeat() {
        SessionManager.mulai(sessionId, this,
            expired = { handler.post { exitApp("Sesi berakhir.") } },
            error = { handler.postDelayed({ startHeartbeat() }, 5000) }
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            lastFocusLost = System.currentTimeMillis()
            handler.postDelayed(tabSwitchRunnable, 15000)
        } else {
            handler.removeCallbacks(tabSwitchRunnable)
            val elapsed = System.currentTimeMillis() - lastFocusLost
            if (elapsed > 15000) {
                tabSwitchCount++
                ViolationReporter.laporkan("focus_loss", "Focus loss ${elapsed/1000}s", (elapsed/1000).toInt(), this)
            }
        }
    }

    private fun exitTemporarily() {
        AlertDialog.Builder(this)
            .setTitle("Keluar Sementara?")
            .setMessage("Anda akan keluar dari ujian. Sesi akan dijeda.")
            .setPositiveButton("Keluar") { _, _ ->
                SessionManager.hentikan(this)
                startActivity(Intent(this, InputAddressActivity::class.java))
                finish()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun exitApp(reason: String) {
        timer?.cancel()
        faceManager?.hentikan()
        SessionManager.hentikan(this)
        AlertDialog.Builder(this)
            .setTitle("Ujian Berakhir")
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                finishAndRemoveTask()
            }
            .show()
    }

    override fun onBackPressed() {
        // Block back during exam
    }

    override fun onDestroy() {
        timer?.cancel()
        faceManager?.hentikan()
        super.onDestroy()
    }
}
