# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Keep all app classes — package is pochi.exam.clock
-keep class pochi.exam.clock.** { *; }

# Keep attributes for stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep EncryptedSharedPreferences
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Keep certificate pinning
-keep class okhttp3.CertificatePinner { *; }

# ML Kit Face Detection
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_vision_face** { *; }
-dontwarn com.google.android.gms.internal.mlkit_vision_face**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Obfuscate everything else
-repackageclasses
-allowaccessmodification
