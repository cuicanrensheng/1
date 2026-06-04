package com.iptvlive;

import android.app.Application;
import android.util.Log;
import com.iptvlive.util.AppSpUtil;
import com.iptvlive.util.HttpHeaderSpUtil;
import com.iptvlive.util.LogSpUtil;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppSpUtil.init(this);
        HttpHeaderSpUtil.init(this);
        LogSpUtil.init(this);
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            String crashLog = Log.getStackTraceString(throwable);
            LogSpUtil.addOperCrashLog("【APP全局崩溃】" + crashLog);
            System.exit(1);
        });
    }
}
