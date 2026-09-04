package pochi.exam.clock

import android.content.Context

object ViolationReporter {
    fun laporkan(jenis: String, detail: String = "", durasiDetik: Int = 0, context: Context) {
        val sid = SessionManager.getSessionId() ?: return
        FirebaseClient.catatPelanggaran(sid, jenis, detail, durasiDetik, context, null)
    }
}
