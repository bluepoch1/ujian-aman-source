package pochi.exam.clock

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.Timestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

object FirebaseClient {
    private const val TAG = "FirebaseClient"
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val main = Handler(Looper.getMainLooper())

    data class Sesi(val sessionId: String = "", val url: String = "", val namaKelas: String = "",
        val mataPelajaran: String = "", val namaPeserta: String = "", val durasiMenit: Int = 0,
        val sisaDetik: Long = 0, val masukUlang: Boolean = false, val batasWaktu: Date? = null)
    data class Gagal(val kode: String, val pesan: String)
    data class Detak(val sisaDetik: Long = 0, val status: String = "aktif",
        val sesiHilang: Boolean = false, val pesan: String = "")
    interface SesiCallback { fun onBerhasil(sesi: Sesi); fun onGagal(gagal: Gagal) }
    interface DetakCallback { fun onDetak(detak: Detak); fun onOffline() }
    interface SimpleCallback { fun onSelesai(berhasil: Boolean) }

    private fun onMain(action: () -> Unit) { main.post(action) }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun ensureAuth() {
        if (auth.currentUser == null) {
            Log.d(TAG, "Signing in anonymously...")
            auth.signInAnonymously().await()
            Log.d(TAG, "Anonymous auth successful, uid=${auth.currentUser?.uid}")
        }
        auth.currentUser?.getIdToken(true)?.await()
    }

    private fun getFirebaseErrorMessage(e: Exception): Gagal {
        val msg = e.message ?: "Unknown error"
        Log.e(TAG, "Firebase error: ${e.javaClass.simpleName}: $msg")

        return when {
            msg.contains("PERMISSION_DENIED") || msg.contains("permission") ->
                Gagal("PERMISSION", "Izin akses ditolak. Hubungi pengawas ujian.")
            msg.contains("UNAUTHENTICATED") || msg.contains("auth") ->
                Gagal("AUTH", "Gagal autentikasi. Coba lagi.")
            msg.contains("UNAVAILABLE") || msg.contains("network") || msg.contains("timeout") ->
                Gagal("NETWORK", "Jaringan tidak tersedia. Periksa koneksi internet Anda.")
            msg.contains("NOT_FOUND") || msg.contains("not found") ->
                Gagal("NOT_FOUND", "Data tidak ditemukan.")
            msg.contains("DEADLINE_EXCEEDED") || msg.contains("deadline") ->
                Gagal("TIMEOUT", "Permintaan timeout. Coba lagi.")
            msg.contains("RESOURCE_EXHAUSTED") ->
                Gagal("RATE_LIMIT", "Terlalu banyak permintaan. Coba lagi nanti.")
            else ->
                Gagal("NETWORK", "Gagal terhubung: ${msg.take(100)}")
        }
    }

    fun klaimSesi(token: String, nama: String, nomorPeserta: String, context: Context, callback: SesiCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!isNetworkAvailable(context)) {
                    onMain { callback.onGagal(Gagal("NO_NETWORK", "Tidak ada koneksi internet. Periksa jaringan Anda.")) }
                    return@launch
                }

                Log.d(TAG, "klaimSesi: token=$token, nama=$nama")
                ensureAuth()

                val deviceHash = DeviceIdentity.hash(context)
                Log.d(TAG, "Device hash: $deviceHash")

                val tokenSnap = db.collection("tokens")
                    .whereEqualTo("token", token.trim()).whereEqualTo("is_active", true)
                    .limit(1).get().await()
                Log.d(TAG, "Token query result: ${tokenSnap.size()} documents")

                if (tokenSnap.isEmpty) {
                    onMain { callback.onGagal(Gagal("TOKEN_TIDAK_ADA", "Token tidak dikenali atau belum aktif.")) }
                    return@launch
                }

                val tokenDoc = tokenSnap.documents[0]
                val td = tokenDoc.data!!
                Log.d(TAG, "Token data: $td")

                val now = System.currentTimeMillis()
                val mulaiAt = (td["mulai_at"] as? Timestamp)?.toDate()?.time ?: 0L
                val expiredAt = (td["expired_at"] as? Timestamp)?.toDate()?.time ?: Long.MAX_VALUE

                if (now < mulaiAt) {
                    onMain { callback.onGagal(Gagal("BELUM_MULAI", "Ujian belum dibuka.")) }
                    return@launch
                }
                if (now > expiredAt) {
                    onMain { callback.onGagal(Gagal("EXPIRED", "Token sudah habis.")) }
                    return@launch
                }

                val existSess = db.collection("sessions")
                    .whereEqualTo("token_id", tokenDoc.id).whereEqualTo("uid", auth.currentUser?.uid)
                    .limit(1).get().await()
                Log.d(TAG, "Existing sessions: ${existSess.size()}")

                if (!existSess.isEmpty) {
                    val sess = existSess.documents[0]
                    val sd = sess.data!!
                    val status = sd["status"] as? String ?: ""
                    if (status == "dihentikan") {
                        onMain { callback.onGagal(Gagal("DIHENTIKAN", "Sesi dihentikan pengawas.")) }
                        return@launch
                    }
                    if (status == "selesai") {
                        onMain { callback.onGagal(Gagal("SELESAI", "Ujian sudah selesai.")) }
                        return@launch
                    }
                    db.collection("violations").add(hashMapOf(
                        "session_id" to sess.id, "jenis" to "masuk_ulang",
                        "detail" to "Aplikasi dibuka kembali", "waktu" to Timestamp.now()
                    )).await()
                    db.collection("sessions").document(sess.id).update(
                        "jumlah_pelanggaran", FieldValue.increment(1), "terakhir_aktif", Timestamp.now()
                    ).await()
                    val batasMs = (sd["batas_waktu_at"] as? Timestamp)?.toDate()?.time ?: 0L
                    val sisa = maxOf(0, (batasMs - now) / 1000)
                    onMain { callback.onBerhasil(Sesi(sess.id, td["url"] as? String ?: "",
                        td["nama_kelas"] as? String ?: "", td["mata_pelajaran"] as? String ?: "",
                        sd["nama_peserta"] as? String ?: nama, (td["durasi_menit"] as? Long)?.toInt() ?: 0,
                        sisa, true, Date(batasMs))) }
                    return@launch
                }

                val durasiMenit = (td["durasi_menit"] as? Long)?.toInt() ?: 60
                val batasMs = minOf(now + durasiMenit * 60000L, expiredAt)
                val sessRef = db.collection("sessions").add(hashMapOf(
                    "token_id" to tokenDoc.id, "uid" to auth.currentUser?.uid,
                    "nama_peserta" to nama.trim(), "nomor_peserta" to nomorPeserta.trim().ifEmpty { null },
                    "device_hash" to deviceHash, "device_model" to DeviceIdentity.model(),
                    "app_version" to BuildConfig.VERSION_NAME, "status" to "aktif",
                    "jumlah_pelanggaran" to 0, "tambahan_menit" to 0,
                    "mulai_at" to Timestamp.now(), "batas_waktu_at" to Timestamp(Date(batasMs)),
                    "selesai_at" to null, "terakhir_aktif" to Timestamp.now(),
                    "catatan_pengawas" to null, "keluar_sementara" to false
                )).await()
                Log.d(TAG, "Session created: ${sessRef.id}")

                db.collection("tokens").document(tokenDoc.id).update(
                    "jumlah_klaim", FieldValue.increment(1)
                ).await()

                val sisa = (batasMs - now) / 1000
                onMain { callback.onBerhasil(Sesi(sessRef.id, td["url"] as? String ?: "",
                    td["nama_kelas"] as? String ?: "", td["mata_pelajaran"] as? String ?: "",
                    nama.trim(), durasiMenit, sisa, false, Date(batasMs))) }
            } catch (e: Exception) {
                Log.e(TAG, "klaimSesi failed", e)
                onMain { callback.onGagal(getFirebaseErrorMessage(e)) }
            }
        }
    }

    fun heartbeat(sessionId: String, context: Context, callback: DetakCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!isNetworkAvailable(context)) {
                    onMain { callback.onOffline() }
                    return@launch
                }

                ensureAuth()
                val snap = db.collection("sessions").document(sessionId).get().await()
                if (!snap.exists()) {
                    onMain { callback.onDetak(Detak(sesiHilang = true, status = "hilang")) }
                    return@launch
                }
                val sd = snap.data!!
                val status = sd["status"] as? String ?: "aktif"
                if (status == "dihentikan" || status == "selesai") {
                    onMain { callback.onDetak(Detak(sesiHilang = true, status = status)) }
                    return@launch
                }
                val now = System.currentTimeMillis()
                val batasMs = (sd["batas_waktu_at"] as? Timestamp)?.toDate()?.time ?: 0L
                val sisa = maxOf(0, (batasMs - now) / 1000)
                if (sisa == 0L) {
                    db.collection("sessions").document(sessionId).update(
                        "status", "selesai", "selesai_at", Timestamp.now()
                    ).await()
                    onMain { callback.onDetak(Detak(sisaDetik = 0, status = "selesai")) }
                    return@launch
                }
                db.collection("sessions").document(sessionId).update("terakhir_aktif", Timestamp.now()).await()
                onMain { callback.onDetak(Detak(sisaDetik = sisa, status = status)) }
            } catch (e: Exception) {
                Log.e(TAG, "heartbeat failed", e)
                onMain { callback.onOffline() }
            }
        }
    }

    fun catatPelanggaran(sessionId: String, jenis: String, detail: String, durasiDetik: Int, context: Context, callback: SimpleCallback?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!isNetworkAvailable(context)) {
                    onMain { callback?.onSelesai(false) }
                    return@launch
                }

                ensureAuth()
                db.collection("violations").add(hashMapOf(
                    "session_id" to sessionId, "jenis" to jenis, "detail" to detail.take(500),
                    "durasi_detik" to if (durasiDetik > 0) durasiDetik else null,
                    "waktu_perangkat" to System.currentTimeMillis(), "waktu" to Timestamp.now()
                )).await()
                db.collection("sessions").document(sessionId).update(
                    "jumlah_pelanggaran", FieldValue.increment(1), "terakhir_aktif", Timestamp.now()
                ).await()
                onMain { callback?.onSelesai(true) }
            } catch (e: Exception) {
                Log.e(TAG, "catatPelanggaran failed", e)
                onMain { callback?.onSelesai(false) }
            }
        }
    }

    fun akhiriSesi(sessionId: String, context: Context, callback: SimpleCallback?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!isNetworkAvailable(context)) {
                    onMain { callback?.onSelesai(false) }
                    return@launch
                }

                ensureAuth()
                db.collection("sessions").document(sessionId).update(
                    "status", "selesai", "selesai_at", Timestamp.now(), "terakhir_aktif", Timestamp.now()
                ).await()
                onMain { callback?.onSelesai(true) }
            } catch (e: Exception) {
                Log.e(TAG, "akhiriSesi failed", e)
                onMain { callback?.onSelesai(false) }
            }
        }
    }
}
