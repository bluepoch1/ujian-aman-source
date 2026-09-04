package pochi.exam.clock

import android.content.Context
import android.media.MediaPlayer
import pochi.exam.clock.R

object SoundManager {
    private var mp: MediaPlayer? = null

    fun playExamStart(context: Context) { play(context, R.raw.exam_start) }
    fun playTokenOk(context: Context) { play(context, R.raw.token_ok) }
    fun playExit(context: Context) { play(context, R.raw.exit) }
    fun playAlarm(context: Context) { play(context, R.raw.alarm) }

    private fun play(context: Context, resId: Int) {
        try {
            mp?.release()
            mp = MediaPlayer.create(context, resId)?.apply {
                setOnCompletionListener { it.release(); mp = null }
                start()
            }
        } catch (_: Exception) {}
    }
}
