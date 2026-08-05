-optimizationpasses 7
-allowaccessmodification
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 移除所有 Log 调用（发布构建）
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ========================
# 应用自身类：只保留反射/组件必需
# ========================
-keep class com.tv.live.MainActivity { *; }
-keep class com.tv.live.CrashActivity { *; }
-keep class com.tv.live.MyApplication { *; }
-keep class com.tv.live.BootStartReceiver { *; }
-keep class com.tv.live.BootReceiver { *; }

-keep class com.tv.live.jsparser.JsLayer$ParserJsInterface { *; }
-keepclassmembers class com.tv.live.jsparser.** {
    public <methods>;
}

-keep class com.tv.live.Channel { *; }
-keep class com.tv.live.Channel$EpgItem { *; }
-keep class com.tv.live.Channel$Variant { *; }
-keep class com.tv.live.SourceManager$SourceItem { *; }

# ========================
# Media3 (ExoPlayer)：只保留 XML 反射必需的 View 构造器
# ========================
-keep class androidx.media3.ui.PlayerView {
    public <init>(...);
}
-keep class androidx.media3.ui.AspectRatioFrameLayout {
    public <init>(...);
}

-keep class androidx.media3.common.C { *; }
-keep class androidx.media3.common.C$* { *; }
-keep class androidx.media3.common.Format { *; }
-keep interface androidx.media3.common.Player { *; }
-keep class androidx.media3.common.Player$* { *; }
-keep class androidx.media3.common.util.UnstableApi { *; }

-dontwarn androidx.media3.**

# ========================
# 第三方库：只保留核心反射必需
# ========================

# OkHttp（自带 consumerProguardFiles，仅保留 -dontwarn 兜底）
-dontwarn okhttp3.**
-dontwarn okio.**

# ZXing：只保留实际使用的 QRCodeWriter / BitMatrix / BarcodeFormat
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-dontwarn com.google.zxing.**

-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn javax.ws.rs.ext.**
-dontwarn org.glassfish.jersey.**
