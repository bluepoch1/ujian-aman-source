package pochi.exam.clock

import android.content.Context
import android.os.Handler
import android.os.Looper

object SessionManager {
    private var sessionId: String? = null
    private var active = false
    private val handler = Handler(Looper.getMainLooper())
    private var onExpired: (() -> Unit)? = null
    private var onHeartbeatError: (() -> Unit)? = null
    private var sisaDetik: Long = 0

    fun getSessionId(): String? = sessionId

    fun mulai(sid: String, context: Context, expired: () -> Unit, error: () -> Unit) {
        sessionId = sid
        active = true
        onExpired = expired
        onHeartbeatError = error
        mulaiHeartbeat(context)
    }

    fun hentikan(context: Context) {
        active = false
        sessionId?.let { sid ->
            FirebaseClient.akhiriSesi(sid, "selesai", context) {}
        }
    }

    fun getSisaDetik() = sisaDetik

    private fun mulaiHeartbeat(context: Context) {
        if (!active) return
        val sid = sessionId ?: return
        FirebaseClient.heartbeat(sid, context, object : FirebaseClient.DetakCallback {
            override fun onDetak(detak: FirebaseClient.Detak) {
                sisaDetik = detak.sisaDetik
                if (detak.sesiHilang) {
                    active = false
                    onExpired?.invoke()
                    return
                }
                handler.postDelayed({ mulaiHeartbeat(context) }, 15_000)
            }
            override fun onOffline() {
                handler.postDelayed({ mulaiHeartbeat(context) }, 5_000)
            }
        })
    }
}
