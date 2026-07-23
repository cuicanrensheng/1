# ===================== 基础配置 =====================
-keepattributes Signature,InnerClasses,SourceFile,LineNumberTable,Exceptions,*Annotation*
-keepclasseswithmembers class * { native <methods>; }
-keepclassmembers class * {
    void *(android.view.View);
    *** *(...);
}

# ===================== 项目自有类（仅保留反射需要的） =====================
# Activity / Service / Receiver（AndroidManifest 需要反射实例化）
-keep class com.tv.live.MainActivity { *; }
-keep class com.tv.live.ChannelListActivity { *; }
-keep class com.tv.live.CrashActivity { *; }
-keep class com.tv.live.MyApplication { *; }
-keep class com.tv.live.BootStartReceiver { *; }
-keep class com.tv.live.BootReceiver { *; }

# JS 接口（WebView 通过反射调用）
-keep class com.tv.live.jsparser.JsLayer$ParserJsInterface { *; }
-keepclassmembers class com.tv.live.jsparser.** { <methods>; }

# 数据模型（JSON 序列化需要保留字段名）
-keep class com.tv.live.Channel { *; }
-keep class com.tv.live.Channel$EpgItem { *; }
-keep class com.tv.live.Channel$Variant { *; }
-keep class com.tv.live.bean.** { *; }

# ===================== Media3（仅保留必要接口） =====================
-keep interface androidx.media3.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-dontwarn androidx.media3.**

# ===================== fastjson =====================
-keepattributes *Annotation*
-dontwarn com.alibaba.fastjson.**
-keep class com.alibaba.fastjson.** { *; }
-keepclassmembers class * { public <init>(org.json.JSONObject); }

# ===================== OkHttp =====================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ===================== NanoHttpd =====================
-keep class org.nanohttpd.** {*;}
-dontwarn org.nanohttpd

# ===================== ZXing =====================
-keep class com.google.zxing.** {*;}
-dontwarn com.google.zxing.**

# ===================== 其他 =====================
-dontwarn java.lang.invoke.MethodHandleProxies
-dontwarn javax.ws.rs.ext.**
-dontwarn org.glassfish.jersey.**
