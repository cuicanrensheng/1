# ==================== Media3 播放器防混淆核心规则 ====================
-keep class androidx.media3.ui.PlayerView { *; }
-keep class androidx.media3.ui.SurfaceType { *; }
-keep enum androidx.media3.ui.SurfaceType
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 通用混淆基础配置
-keepattributes Signature,InnerClasses,SourceFile,LineNumberTable
-keepclasseswithmembers class * {
    native <methods>;
}

# Gson 实体类保留（适配你的项目包）
-keep class com.tv.live.bean.** {*;}
-keep class com.tv.live.Channel {*;}

# Glide 图片库防混淆
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class com.bumptech.glide.** {*;}
-dontwarn com.bumptech.glide.**
