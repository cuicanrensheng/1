-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

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

-keep class com.tv.live.MainActivity { *; }
-keep class com.tv.live.ChannelListActivity { *; }
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
-keep class com.tv.live.bean.** { *; }
-keep class com.tv.live.SourceManager$SourceItem { *; }

# ==========================================================
# Media3 (ExoPlayer) - 保留 XML 反射必需的 View 类
# R8 会自动保留直接引用的代码，只需保留反射访问的入口
# ==========================================================
-keep class androidx.media3.ui.PlayerView {
    public <init>(...);
}
-keep class androidx.media3.ui.AspectRatioFrameLayout {
    public <init>(...);
}

# Media3 内部使用反射/服务加载的类需要保留
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-keep class androidx.media3.exoplayer.DefaultLoadControl { *; }
-keep class androidx.media3.exoplayer.DefaultTrackSelector { *; }
-keep class androidx.media3.exoplayer.source.DefaultMediaSourceFactory { *; }
-keep class androidx.media3.exoplayer.source.ProgressiveMediaSource { *; }
-keep class androidx.media3.exoplayer.source.hls.HlsMediaSource { *; }
-keep class androidx.media3.exoplayer.source.hls.HlsMediaSource$Factory { *; }
-keep class androidx.media3.common.Format { *; }
-keep class androidx.media3.common.MimeTypes { *; }
-keep class androidx.media3.common.C { *; }
-keep class androidx.media3.common.C$* { *; }
-keep class androidx.media3.common.Player$* { *; }
-keep class androidx.media3.common.util.UnstableApi { *; }
-keep interface androidx.media3.common.Player { *; }

-dontwarn androidx.media3.**

# ==========================================================
# 第三方库混淆规则
# ==========================================================
-keep class com.alibaba.fastjson.JSON { *; }
-keep class com.alibaba.fastjson.JSONObject { *; }
-keep class com.alibaba.fastjson.JSONArray { *; }
-keep class com.alibaba.fastjson.parser.DefaultJSONParser { *; }
-keep class com.alibaba.fastjson.serializer.SerializeWriter { *; }
-keep class com.alibaba.fastjson.parser.deserializer.** { *; }
-keep class com.alibaba.fastjson.serializer.** { *; }
-keepclassmembers class * {
    @com.alibaba.fastjson.annotation.JSONField *;
}
-dontwarn com.alibaba.fastjson.**

# OkHttp - OkHttp 内部使用大量反射，保留核心类
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.OkHttpClient$Builder { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Request$Builder { *; }
-keep class okhttp3.Response { *; }
-keep class okhttp3.Call { *; }
-keep class okhttp3.Callback { *; }
-keep class okhttp3.Headers { *; }
-keep class okhttp3.Headers$Builder { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ZXing - 保留二维码生成核心类
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-dontwarn com.google.zxing.**

-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn javax.ws.rs.ext.**
-dontwarn org.glassfish.jersey.**
