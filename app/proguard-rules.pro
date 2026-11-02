# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep JavaScript interfaces for WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep @androidx.annotation.Keep class * { *; }
-keep class com.example.engine.DriveExtractorEngine$DriveJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep data models
-keepclassmembers class com.example.data.model.** { *; }

# Suppress benign warnings from OkHttp/Okio during R8 optimization
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
