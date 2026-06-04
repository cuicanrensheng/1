package com.iptvlive;

import androidx.multidex.MultiDexApplication;
import android.util.Log;
import com.iptvlive.util.AppSpUtil;
import com.iptvlive.util.HttpHeaderSpUtil;
import com.iptvlive.util.LogSpUtil;

/**
 * APP全局Application，程序启动最先初始化
 * 初始化全部SP存储工具、全局崩溃捕获
 */
// 父类从Application改为MultiDexApplication
public class App extends MultiDexApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        //初始化设置SP
        AppSpUtil.init(this);
        //初始化HTTP头SP
        HttpHeaderSpUtil.init(this);
        //初始化日志SP
        LogSpUtil.init(this);
        //全局崩溃捕获，写入操作日志
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String crashLog = Log.getStackTraceString(throwable);
            LogSpUtil.addOperCrashLog("【APP全局崩溃】" + crashLog);
            System.exit(1);
        });
    }
}
