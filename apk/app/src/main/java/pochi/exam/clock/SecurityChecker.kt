package pochi.exam.clock

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

object SecurityChecker {

    fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/system/bin/su",
            "/system/xbin/su", "/sbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/su"
        )
        if (paths.any { java.io.File(it).exists() }) return true
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val br = java.io.BufferedReader(java.io.InputStreamReader(p.inputStream))
            br.readLine() != null
        } catch (_: Exception) { false }
    }

    fun hasSuspiciousAccessibilityServices(context: Context): Boolean {
        val blocked = listOf(
            "com.teamviewer", "com.anydesk", "com.rustdesk",
            "com.microsoft.rdc", "com.splashtop",
            "com.hecorat.screenrecorder", "com.kimcy929.screenrecorder"
        )
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return blocked.any { enabled.contains(it, ignoreCase = true) }
    }

    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ?: false
    }
}
