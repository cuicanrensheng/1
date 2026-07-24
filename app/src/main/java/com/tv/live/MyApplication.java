package com.tv.live;

import android.app.Application;
import android.content.Context; // ✅ 新增导入
import android.os.Build;
import androidx.multidex.MultiDex; // ✅ 新增导入
import com.tv.live.util.NetUtil;

public class MyApplication extends Application {

    // ✅ 新增：在 Application 创建之初安装 MultiDex，解决旧电视启动闪退
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

        // 🔧【清理虎牙SDK】原虎牙 SDK 初始化代码已移除
        // if (Build.VERSION.SDK_INT >= 21) {
        //     HuyaBerry.instance().init(this, new HuyaBerryConfig.Builder()
        //             .gameId(2336)
        //             .appId("123456")
        //             .appKey("d8f193dd")
        //             .debugMode(false)
        //             .landscapeMode(false)
        //             .isOpenBugly(false)
        //             .build());
        // }
    }
}
