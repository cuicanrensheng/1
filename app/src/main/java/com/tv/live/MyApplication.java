package com.tv.live;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import androidx.multidex.MultiDex;
import androidx.emoji2.text.DefaultEmojiCompatConfig;
import androidx.emoji2.text.EmojiCompat;
import com.tv.live.util.NetUtil;

public class MyApplication extends Application {

    // ✅ 在 Application 创建之初安装 MultiDex，解决旧电视启动闪退
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // ================================================================
        // 🔥【方案A修复代码】使用 create() 方法初始化 Emoji2，修复编译错误
        // ================================================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) { // Android 4.4以上
            try {
                // 使用官方推荐的方法 create(this) 替代废弃的构造函数
                EmojiCompat.init(DefaultEmojiCompatConfig.create(this));
                Log.d("MyApplication", "EmojiCompat 初始化成功");
            } catch (Throwable t) {
                // 如果异常仍发生，仅打印日志，保证应用其他流程不受影响
                Log.e("MyApplication", "EmojiCompat 初始化失败 (不影响主流程)", t);
            }
        }
        // ================================================================

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
