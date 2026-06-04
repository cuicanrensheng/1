#==========Application 必保，防止打包被删类==========
-keep class com.iptvlive.App {*;}
-keep public class * extends android.app.Application

#==========所有业务代码不被混淆删除==========
-keep class com.iptvlive.activity.**{*;}
-keep class com.iptvlive.receiver.**{*;}
-keep class com.iptvlive.util.**{*;}
-keep class com.iptvlive.bean.**{*;}
-keep class com.iptvlive.httpserver.**{*;}

#==========系统基础规则==========
-keepattributes Signature
-keepattributes Exceptions
