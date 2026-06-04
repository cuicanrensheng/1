package com.iptvlive;

import android.content.Context;
import android.util.Log;
import androidx.multidex.MultiDex;
import androidx.multidex.MultiDexApplication;
import com.iptvlive.util.AppSpUtil;
import com.iptvlive.util.HttpHeaderSpUtil;
import com.iptvlive.util.LogSpUtil;

/**
 * APP全局Application，程序启动最先初始化
 * 初始化全部SP存储工具、全局崩溃捕获
 */
public class App extends MultiDexApplication {

    //【必须新增】低版本安卓手动加载MultiDex，解决ClassNotFound
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(base);
    }

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
