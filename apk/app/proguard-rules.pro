# OkHttp / Okio — referensi opsional ke platform yang tidak ada di Android.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Receiver device admin dirujuk lewat manifest, bukan dari kode.
-keep class com.safebrowser.app.DeviceAdminReceiver { *; }

# Nama activity dirujuk dari manifest.
-keep class com.safebrowser.app.*Activity { *; }

# Pertahankan nomor baris agar stack trace crash tetap terbaca.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── 3.0: kelas backend ────────────────────────────────────────
# Semua dipanggil langsung dari kode kita, jadi aman diobfuscate.
# Yang perlu dijaga hanya nama field JSON — tapi itu string literal,
# bukan refleksi, sehingga R8 tidak menyentuhnya.

# ── SECURITY: Jangan hapus security fixes! ──────────────────────
# Hanya keep class yang PENTING untuk keamanan, biarkan R8 obfuscate sisanya
-keep class com.safebrowser.app.SecurityChecker { *; }
-keep class com.safebrowser.app.DeviceAdminReceiver { *; }
-keep class com.safebrowser.app.BuildConfig { *; }

# Keep methods that are called via reflection or from XML
-keepclassmembers class com.safebrowser.app.*Activity { 
    public void *(android.view.View);
}

# Keep Supabase client methods (called via RPC)
-keep class com.safebrowser.app.SupabaseClient$* { *; }
-keep class com.safebrowser.app.AuthManager { *; }

# Keep ViolationReporter (uses reflection for stack traces)
-keep class com.safebrowser.app.ViolationReporter { *; }

# Keep SessionManager (stores session data)
-keep class com.safebrowser.app.SessionManager { *; }

# Keep DeviceIdentity (device fingerprinting)
-keep class com.safebrowser.app.DeviceIdentity { *; }

# Keep EncryptedSharedPreferences (from security-crypto library)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Keep certificate pinning
-keep class okhttp3.CertificatePinner { *; }

# Obfuscate everything else in safebrowser package
-repackageclasses 
-allowaccessmodification

# Jangan sisakan Log.d/Log.v di rilis: jalur klaim sesi mencatat
# token dan URL ujian saat debugging.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# ML Kit Face Detection
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_vision_face** { *; }
-dontwarn com.google.android.gms.internal.mlkit_vision_face**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
