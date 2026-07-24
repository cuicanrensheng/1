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

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
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

-keep interface androidx.media3.common.Player { *; }
-keep interface androidx.media3.common.MediaItem { *; }
-keep interface androidx.media3.common.Timeline { *; }
-keep interface androidx.media3.common.CryptoException { *; }
-keep interface androidx.media3.common.analytics.AnalyticsListener { *; }

-keep class androidx.media3.common.util.** { *; }
-keep class androidx.media3.common.C.TrackType { *; }
-keep class androidx.media3.common.Format { *; }
-keep class androidx.media3.common.MimeTypes { *; }
-keep class androidx.media3.common.Player$State { *; }
-keep class androidx.media3.common.Player$EventDispatcher { *; }
-keep class androidx.media3.common.util.Assertions { *; }
-keep class androidx.media3.common.util.UnstableApi { *; }

-keep class androidx.media3.exoplayer.ExoPlayerImpl { *; }
-keep class androidx.media3.exoplayer.ExoPlayerImpl$Builder { *; }
-keep class androidx.media3.exoplayer.DefaultRenderersFactory { *; }
-keep class androidx.media3.exoplayer.DefaultLoadControl { *; }
-keep class androidx.media3.exoplayer.DefaultTrackSelector { *; }
-keep class androidx.media3.exoplayer.source.DefaultMediaSourceFactory { *; }
-keep class androidx.media3.exoplayer.source.hls.HlsMediaSource { *; }
-keep class androidx.media3.exoplayer.source.hls.HlsMediaSource$Factory { *; }

-keep class androidx.media3.ui.PlayerView { *; }
-keep class androidx.media3.ui.AspectRatioFrameLayout { *; }

-dontwarn androidx.media3.**

-dontwarn com.alibaba.fastjson.**
-keep class com.alibaba.fastjson.JSON { *; }
-keep class com.alibaba.fastjson.JSONObject { *; }
-keep class com.alibaba.fastjson.JSONArray { *; }
-keep class com.alibaba.fastjson.parser.JSONParser { *; }
-keep class com.alibaba.fastjson.parser.DefaultJSONParser { *; }
-keep class com.alibaba.fastjson.serializer.JSONSerializer { *; }
-keep class com.alibaba.fastjson.serializer.SerializeWriter { *; }
-keep class com.alibaba.fastjson.parser.deserializer.** { *; }
-keep class com.alibaba.fastjson.serializer.** { *; }
-keepclassmembers class * {
    @com.alibaba.fastjson.annotation.JSONField *;
}

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.OkHttpClient$Builder { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Request$Builder { *; }
-keep class okhttp3.Response { *; }
-keep class okhttp3.Call { *; }
-keep class okhttp3.Callback { *; }
-keep class okhttp3.ConnectionPool { *; }
-keep class okhttp3.Interceptor { *; }

-keep class com.google.zxing.BinaryBitmap { *; }
-keep class com.google.zxing.MultiFormatReader { *; }
-keep class com.google.zxing.DecodeHintType { *; }
-keep class com.google.zxing.Result { *; }
-keep class com.google.zxing.common.HybridBinarizer { *; }
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.qrcode.QRCodeReader { *; }
-keep class com.google.zxing.qrcode.detector.Detector { *; }
-dontwarn com.google.zxing.**

-keep class org.nanohttpd.** {*;}
-dontwarn org.nanohttpd

-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn javax.ws.rs.ext.**
-dontwarn org.glassfish.jersey.**
