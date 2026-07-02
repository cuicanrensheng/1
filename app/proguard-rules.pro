# Media3 播放器防混淆规则，解决找不到SurfaceType、PlayerView方法崩溃
-keep class androidx.media3.ui.PlayerView { *; }
-keep class androidx.media3.ui.SurfaceType { *; }
-keep enum androidx.media3.ui.SurfaceType
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
