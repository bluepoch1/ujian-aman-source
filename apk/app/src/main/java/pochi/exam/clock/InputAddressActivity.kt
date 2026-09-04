package pochi.exam.clock

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import pochi.exam.clock.databinding.ActivityInputAddressBinding

class InputAddressActivity : AppCompatActivity() {
    private lateinit var b: ActivityInputAddressBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityInputAddressBinding.inflate(layoutInflater)
        setContentView(b.root)
        

        val scannedToken = intent.getStringExtra("scanned_token")
        if (scannedToken != null) {
            b.etUrl.setText(scannedToken)
            b.etUrl.setSelection(scannedToken.length)
        }

        b.btnOpen.setOnClickListener { submitToken() }
        b.btnScanQrInline.setOnClickListener { openQrScan() }
        b.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitToken(); true } else false
        }
    }

    private fun submitToken() {
        val token = b.etUrl.text?.toString()?.trim() ?: ""
        val nama = b.etNama.text?.toString()?.trim() ?: ""
        val nomor = b.etNomor.text?.toString()?.trim() ?: ""

        if (token.isEmpty()) {
            b.etUrl.error = "Token wajib diisi"
            return
        }
        if (nama.isEmpty()) {
            b.etNama.error = "Nama wajib diisi"
            return
        }

        b.btnOpen.isEnabled = false
        b.tvFieldError.visibility = View.GONE

        FirebaseClient.klaimSesi(token, nama, nomor, this, object : FirebaseClient.SesiCallback {
            override fun onBerhasil(sesi: FirebaseClient.Sesi) {
                SoundManager.playTokenOk(this@InputAddressActivity)
                val i = Intent(this@InputAddressActivity, MainActivity::class.java).apply {
                    putExtra("session_id", sesi.sessionId)
                    putExtra("url", sesi.url)
                    putExtra("nama_kelas", sesi.namaKelas)
                    putExtra("mata_pelajaran", sesi.mataPelajaran)
                    putExtra("nama_peserta", sesi.namaPeserta)
                    putExtra("sisa_detik", sesi.sisaDetik)
                    putExtra("masuk_ulang", sesi.masukUlang)
                }
                startActivity(i)
                finish()
            }

            override fun onGagal(gagal: FirebaseClient.Gagal) {
                b.btnOpen.isEnabled = true
                b.tvFieldError.text = gagal.pesan
                b.tvFieldError.visibility = View.VISIBLE
            }
        })
    }

    private fun openQrScan() {
        startActivity(Intent(this, QRScanActivity::class.java))
    }
}
