# ===================== Media3 播放器完整防混淆 =====================
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep enum androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 清晰度轨道相关类强制保留
-keep class androidx.media3.common.TrackGroup
-keep class androidx.media3.common.TrackGroupArray
-keep class androidx.media3.exoplayer.trackselection.** {*;}

# 播放器核心组件
-keep class androidx.media3.ui.PlayerView { *; }
-keep class androidx.media3.ui.AspectRatioFrameLayout { *; }
-keep class androidx.media3.ui.SurfaceType { *; }
-keep enum androidx.media3.ui.SurfaceType
-keep class androidx.media3.exoplayer.ExoPlayer
-keep class androidx.media3.exoplayer.DefaultTrackSelector
-keep class androidx.media3.exoplayer.mediacodec.** {*;}
-keep class androidx.media3.exoplayer.hls.** {*;}
-keep class androidx.media3.exoplayer.source.** {*}

# ===================== 通用基础 =====================
-keepattributes Signature,InnerClasses,SourceFile,LineNumberTable,Exceptions
-keepclasseswithmembers class * { native <methods>; }
-keepclassmembers class * {
    void *(android.view.View);
    *** *(...);
}

# ===================== 项目自有类 =====================
-keep class com.tv.live.bean.** {*;}
-keep class com.tv.live.Channel {*;}
-keep class com.tv.live.TVPlayerManager {*;}

# ===================== Gson =====================
-keep class com.google.gson.** {*;}
-dontwarn com.google.gson

# ===================== Glide =====================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.** {*;}
-dontwarn com.bumptech.glide.**

# ===================== OkHttp Retrofit =====================
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

# ===================== NanoHttpd =====================
-keep class org.nanohttpd.** {*;}
-dontwarn org.nanohttpd

# ===================== Apache Commons =====================
-keep class org.apache.commons.** {*;}
-dontwarn org.apache.commons
