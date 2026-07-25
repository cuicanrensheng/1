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

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this); // 保持你的多Dex配置
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // ================================================================
        // 🔥【方案A修复代码】强制初始化 Emoji2 (放最前面，一启动就执行)
        // ================================================================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) { // Android 4.4以上
            try {
                // 使用默认配置初始化 EmojiCompat，解决 Context 传空导致的 NullPointerException
                EmojiCompat.init(new DefaultEmojiCompatConfig(this));
                Log.d("MyApplication", "EmojiCompat 初始化成功");
            } catch (Throwable t) {
                // 如果异常仍发生，仅打印日志，保证应用其他流程不受影响
                Log.e("MyApplication", "EmojiCompat 初始化失败", t);
            }
        }
        // ================================================================

        CrashHandler.getInstance().init(this);

        NetUtil.init(this);

        // 🔧【清理虎牙SDK】原虎牙 SDK 初始化代码已移除
        // if (Build.VERSION.SDK_INT >= 21) { ... }
    }
}
