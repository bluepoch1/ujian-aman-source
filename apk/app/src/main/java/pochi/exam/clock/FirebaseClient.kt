package pochi.exam.clock

import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object FirebaseClient {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val main = Handler(Looper.getMainLooper())

    data class Sesi(
        val sessionId: String = "",
        val url: String = "",
        val namaKelas: String = "",
        val mataPelajaran: String = "",
        val namaPeserta: String = "",
        val durasiMenit: Int = 0,
        val sisaDetik: Long = 0,
        val masukUlang: Boolean = false,
        val batasWaktu: Date? = null
    )

    data class Gagal(
        val kode: String,
        val pesan: String,
        val mulaiAt: Date? = null
    )

    data class Detak(
        val sisaDetik: Long = 0,
        val status: String = "aktif",
        val sesiHilang: Boolean = false,
        val pesan: String = ""
    )

    interface SesiCallback {
        fun onBerhasil(sesi: Sesi)
        fun onGagal(gagal: Gagal)
    }

    interface DetakCallback {
        fun onDetak(detak: Detak)
        fun onOffline()
    }

    interface SimpleCallback {
        fun onSelesai(berhasil: Boolean)
    }

    private fun main(action: () -> Unit) {
        main.post(action)
    }

    suspend fun ensureAuth(): String {
        val user = auth.currentUser
        if (user != null) {
            return user.getIdToken(true).await().token ?: ""
        }
        val result = auth.signInAnonymously().await()
        return result.user?.getIdToken(true)?.await()?.token ?: ""
    }

    fun klaimSesi(
        token: String,
        nama: String,
        nomorPeserta: String,
        context: android.content.Context,
        callback: SesiCallback
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                ensureAuth()
                val deviceHash = DeviceIdentity.hash(context)
                val deviceModel = DeviceIdentity.model()
                val appVersion = BuildConfig.VERSION_NAME

                val tokenSnap = db.collection("tokens")
                    .whereEqualTo("token", token.trim())
                    .whereEqualTo("is_active", true)
                    .limit(1)
                    .get()
                    .await()

                if (tokenSnap.isEmpty) {
                    main { callback.onGagal(Gagal("TOKEN_TIDAK_ADA", "Token tidak dikenali.")) }
                    return@launch
                }

                val tokenDoc = tokenSnap.documents[0]
                val tokenData = tokenDoc.data ?: run {
                    main { callback.onGagal(Gagal("TOKEN_TIDAK_ADA", "Token tidak valid.")) }
                    return@launch
                }

                val now = System.currentTimeMillis()
                val mulaiAt = (tokenData["mulai_at"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L
                val expiredAt = (tokenData["expired_at"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: Long.MAX_VALUE

                if (now < mulaiAt) {
                    main { callback.onGagal(Gagal("BELUM_MULAI", "Ujian belum dibuka.")) }
                    return@launch
                }
                if (now > expiredAt) {
                    main { callback.onGagal(Gagal("EXPIRED", "Masa berlaku token sudah habis.")) }
                    return@launch
                }

                val existSess = db.collection("sessions")
                    .whereEqualTo("token_id", tokenDoc.id)
                    .whereEqualTo("uid", auth.currentUser?.uid)
                    .limit(1)
                    .get()
                    .await()

                if (!existSess.isEmpty) {
                    val sess = existSess.documents[0]
                    val sd = sess.data!!
                    val status = sd["status"] as? String ?: ""

                    if (status == "dihentikan") {
                        main { callback.onGagal(Gagal("DIHENTIKAN", "Sesi Anda dihentikan pengawas.")) }
                        return@launch
                    }
                    if (status == "selesai") {
                        main { callback.onGagal(Gagal("SELESAI", "Ujian Anda sudah selesai.")) }
                        return@launch
                    }

                    // Re-enter: record violation
                    db.collection("violations").add(hashMapOf(
                        "session_id" to sess.id,
                        "jenis" to "masuk_ulang",
                        "detail" to "Aplikasi dibuka kembali di tengah ujian",
                        "waktu" to com.google.firebase.Timestamp.now()
                    )).await()

                    db.collection("sessions").document(sess.id).update(
                        "jumlah_pelanggaran", com.google.firebase.FieldValue.increment(1),
                        "terakhir_aktif", com.google.firebase.Timestamp.now()
                    ).await()

                    val batasMs = (sd["batas_waktu_at"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L
                    val sisa = maxOf(0, (batasMs - now) / 1000)

                    val sesi = Sesi(
                        sessionId = sess.id,
                        url = tokenData["url"] as? String ?: "",
                        namaKelas = tokenData["nama_kelas"] as? String ?: "Ujian",
                        mataPelajaran = tokenData["mata_pelajaran"] as? String ?: "",
                        namaPeserta = sd["nama_peserta"] as? String ?: nama,
                        durasiMenit = (tokenData["durasi_menit"] as? Long)?.toInt() ?: 0,
                        sisaDetik = sisa,
                        masukUlang = true,
                        batasWaktu = Date(batasMs)
                    )
                    main { callback.onBerhasil(sesi) }
                    return@launch
                }

                // Check device lock
                val kunciPerangkat = tokenData["kunci_perangkat"] as? Boolean ?: false
                if (kunciPerangkat) {
                    val devSnap = db.collection("sessions")
                        .whereEqualTo("token_id", tokenDoc.id)
                        .whereEqualTo("device_hash", deviceHash)
                        .whereNotEqualTo("status", "dihentikan")
                        .limit(1)
                        .get()
                        .await()
                    if (!devSnap.isEmpty) {
                        main { callback.onGagal(Gagal("PERANGKAT_DIPAKAI", "Perangkat ini sudah dipakai peserta lain.")) }
                        return@launch
                    }
                }

                // Quota check
                val maxPeserta = (tokenData["max_peserta"] as? Long)?.toInt()
                val jumlahKlaim = (tokenData["jumlah_klaim"] as? Long)?.toInt() ?: 0
                if (maxPeserta != null && jumlahKlaim >= maxPeserta) {
                    main { callback.onGagal(Gagal("KUOTA_PENUH", "Kuota peserta sudah penuh.")) }
                    return@launch
                }

                val durasiMenit = (tokenData["durasi_menit"] as? Long)?.toInt() ?: 60
                val batasMs = minOf(now + durasiMenit * 60000L, expiredAt)

                val sessRef = db.collection("sessions").add(hashMapOf(
                    "token_id" to tokenDoc.id,
                    "uid" to auth.currentUser?.uid,
                    "nama_peserta" to nama.trim(),
                    "nomor_peserta" to nomorPeserta.trim().ifEmpty { null },
                    "device_hash" to deviceHash,
                    "device_model" to deviceModel,
                    "app_version" to appVersion,
                    "status" to "aktif",
                    "jumlah_pelanggaran" to 0,
                    "tambahan_menit" to 0,
                    "mulai_at" to com.google.firebase.Timestamp.now(),
                    "batas_waktu_at" to com.google.firebase.Timestamp(Date(batasMs)),
                    "selesai_at" to null,
                    "terakhir_aktif" to com.google.firebase.Timestamp.now(),
                    "catatan_pengawas" to null,
                    "keluar_sementara" to false
                )).await()

                db.collection("tokens").document(tokenDoc.id).update(
                    "jumlah_klaim", com.google.firebase.FieldValue.increment(1)
                ).await()

                val sisa = (batasMs - now) / 1000
                val sesi = Sesi(
                    sessionId = sessRef.id,
                    url = tokenData["url"] as? String ?: "",
                    namaKelas = tokenData["nama_kelas"] as? String ?: "Ujian",
                    mataPelajaran = tokenData["mata_pelajaran"] as? String ?: "",
                    namaPeserta = nama.trim(),
                    durasiMenit = durasiMenit,
                    sisaDetik = sisa,
                    masukUlang = false,
                    batasWaktu = Date(batasMs)
                )
                main { callback.onBerhasil(sesi) }

            } catch (e: Exception) {
                main { callback.onGagal(Gagal("NETWORK", "Gagal terhubung ke server.")) }
            }
        }
    }

    fun heartbeat(sessionId: String, context: android.content.Context, callback: DetakCallback) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                ensureAuth()
                val sessSnap = db.collection("sessions").document(sessionId).get().await()
                if (!sessSnap.exists()) {
                    main { callback.onDetak(Detak(sesiHilang = true, status = "hilang")) }
                    return@launch
                }
                val sd = sessSnap.data!!
                val uid = sd["uid"] as? String
                if (uid != auth.currentUser?.uid) {
                    main { callback.onDetak(Detak(sesiHilang = true, status = "hilang")) }
                    return@launch
                }

                val status = sd["status"] as? String ?: "aktif"
                if (status == "dihentikan") {
                    main { callback.onDetak(Detak(sesiHilang = true, status = "dihentikan", pesan = sd["catatan_pengawas"] as? String ?: "Sesi dihentikan.")) }
                    return@launch
                }
                if (status == "selesai") {
                    main { callback.onDetak(Detak(sesiHilang = true, status = "selesai")) }
                    return@launch
                }

                val now = System.currentTimeMillis()
                val batasMs = (sd["batas_waktu_at"] as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L
                val sisa = maxOf(0, (batasMs - now) / 1000)

                if (sisa == 0L) {
                    db.collection("sessions").document(sessionId).update(
                        "status", "selesai",
                        "selesai_at", com.google.firebase.Timestamp.now()
                    ).await()
                    main { callback.onDetak(Detak(sisaDetik = 0, status = "selesai")) }
                    return@launch
                }

                db.collection("sessions").document(sessionId).update(
                    "terakhir_aktif", com.google.firebase.Timestamp.now()
                ).await()

                main { callback.onDetak(Detak(sisaDetik = sisa, status = status)) }
            } catch (e: Exception) {
                main { callback.onOffline() }
            }
        }
    }

    fun catatPelanggaran(
        sessionId: String,
        jenis: String,
        detail: String,
        durasiDetik: Int,
        context: android.content.Context,
        callback: SimpleCallback?
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                ensureAuth()
                val waktuPerangkat = System.currentTimeMillis()

                db.collection("violations").add(hashMapOf(
                    "session_id" to sessionId,
                    "jenis" to jenis,
                    "detail" to detail.take(500),
                    "durasi_detik" to if (durasiDetik > 0) durasiDetik else null,
                    "waktu_perangkat" to waktuPerangkat,
                    "waktu" to com.google.firebase.Timestamp.now()
                )).await()

                db.collection("sessions").document(sessionId).update(
                    "jumlah_pelanggaran", com.google.firebase.FieldValue.increment(1),
                    "terakhir_aktif", com.google.firebase.Timestamp.now()
                ).await()

                main { callback?.onSelesai(true) }
            } catch (e: Exception) {
                main { callback?.onSelesai(false) }
            }
        }
    }

    fun akhiriSesi(sessionId: String, alasan: String?, context: android.content.Context, callback: SimpleCallback?) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                ensureAuth()
                db.collection("sessions").document(sessionId).update(
                    "status", "selesai",
                    "selesai_at", com.google.firebase.Timestamp.now(),
                    "terakhir_aktif", com.google.firebase.Timestamp.now()
                ).await()
                main { callback?.onSelesai(true) }
            } catch (e: Exception) {
                main { callback?.onSelesai(false) }
            }
        }
    }
}
