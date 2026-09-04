package pochi.exam.clock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import pochi.exam.clock.databinding.ActivityInputAddressBinding

class InputAddressActivity : AppCompatActivity() {
    private lateinit var b: ActivityInputAddressBinding

    private val cameraPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, "Izin kamera diperlukan untuk scan QR", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityInputAddressBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnSubmit.setOnClickListener { submitToken() }
        b.btnQr.setOnClickListener { openQrScan() }

        b.editToken.setOnEditorActionListener { _, _, _ ->
            submitToken()
            true
        }
    }

    private fun submitToken() {
        val token = b.editToken.text?.toString()?.trim() ?: ""
        val nama = b.editNama.text?.toString()?.trim() ?: ""

        if (token.isEmpty()) {
            b.editToken.error = "Token wajib diisi"
            return
        }
        if (nama.length < 2) {
            b.editNama.error = "Nama minimal 2 karakter"
            return
        }

        b.btnSubmit.isEnabled = false
        b.progressBar.visibility = View.VISIBLE

        FirebaseClient.klaimSesi(token, nama, "", this, object : FirebaseClient.SesiCallback {
            override fun onBerhasil(sesi: FirebaseClient.Sesi) {
                SoundManager.playTokenOk(this@InputAddressActivity)
                val intent = Intent(this@InputAddressActivity, MainActivity::class.java).apply {
                    putExtra("session_id", sesi.sessionId)
                    putExtra("url", sesi.url)
                    putExtra("nama_kelas", sesi.namaKelas)
                    putExtra("mata_pelajaran", sesi.mataPelajaran)
                    putExtra("nama_peserta", sesi.namaPeserta)
                    putExtra("durasi_menit", sesi.durasiMenit)
                    putExtra("sisa_detik", sesi.sisaDetik)
                    putExtra("masuk_ulang", sesi.masukUlang)
                    putExtra("batas_waktu", sesi.batasWaktu?.time ?: 0L)
                }
                startActivity(intent)
                b.btnSubmit.isEnabled = true
                b.progressBar.visibility = View.GONE
            }

            override fun onGagal(gagal: FirebaseClient.Gagal) {
                b.btnSubmit.isEnabled = true
                b.progressBar.visibility = View.GONE
                var msg = gagal.pesan
                if (gagal.kode == "BELUM_MULAI" && gagal.mulaiAt != null) {
                    msg += "\nBuka: ${gagal.mulaiAt}"
                }
                AlertDialog.Builder(this@InputAddressActivity)
                    .setTitle("Gagal")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        })
    }

    private fun openQrScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPerm.launch(Manifest.permission.CAMERA)
            return
        }
        startActivity(Intent(this, QRScanActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        intent?.getStringExtra("scanned_token")?.let { token ->
            b.editToken.setText(token)
            intent.removeExtra("scanned_token")
        }
    }
}
