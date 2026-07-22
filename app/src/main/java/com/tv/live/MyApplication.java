package com.tv.live;

import android.app.Application;
import android.os.Build;
// 🔧【清理虎牙SDK】已移除：import com.huya.berry.client.HuyaBerry;
// 🔧【清理虎牙SDK】已移除：import com.huya.berry.client.HuyaBerryConfig;
import com.tv.live.util.NetUtil;

public class MyApplication extends Application {

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
