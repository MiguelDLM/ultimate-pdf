# Proguard rules for Ultimate PDF

# PDFBox Android
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**

# Bouncy Castle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
