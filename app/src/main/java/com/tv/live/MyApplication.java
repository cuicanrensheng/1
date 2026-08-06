package com.tv.live;

import android.app.Application;
import android.content.Context;
import androidx.multidex.MultiDex; // 导入分包库
import com.tv.live.util.NetUtil;

public class MyApplication extends Application {

    // ✅ 在 Application 启动前安装 MultiDex（核心修复）
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        CrashHandler.getInstance().init(this);

        NetUtil.init(this);
    }
}
