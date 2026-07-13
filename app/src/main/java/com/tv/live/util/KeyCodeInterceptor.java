package com.tv.live.util;

import android.view.KeyEvent;
import com.tv.live.MainActivity;

/**
 * 万能遥控器按键拦截器
 * 支持小米、创维、酷开、华为、海信、TCL、索尼等所有主流电视品牌
 * 使用方法：在 MainActivity.onCreate() 中调用 KeyCodeInterceptor.init(this)
 */
public class KeyCodeInterceptor {

    private static MainActivity activity;

    /**
     * 在 MainActivity.onCreate() 中调用一次
     */
    public static void init(MainActivity mainActivity) {
        activity = mainActivity;
    }

    /**
     * 在 MainActivity.dispatchKeyEvent() 中调用此方法
     * @param keyCode 当前按键码
     * @param action 按键动作 (ACTION_DOWN / ACTION_UP)
     * @return true 表示已消费该按键，false 表示未处理
     */
    public static boolean handleKeyEvent(int keyCode, int action) {
        if (action != KeyEvent.ACTION_DOWN) {
            return false;
        }

        // ===== 万能设置键列表 =====
        // 覆盖所有已知电视品牌的自定义键值
        switch (keyCode) {

            // --- 小米 / 创维 / 酷开 ---
            case 234:   // 小米、创维设置键
            case 235:   // 部分小米机型
            case 236:   // 备用

            // --- 酷开 / 部分TCL ---
            case KeyEvent.KEYCODE_F1:     // 131
            case KeyEvent.KEYCODE_F2:     // 132
            case KeyEvent.KEYCODE_F3:     // 133
            case KeyEvent.KEYCODE_F4:     // 134

            // --- 华为 / 荣耀 ---
            case 271:   // 华为智慧屏设置键
            case 272:   // 备用

            // --- 海信 / 索尼 ---
            case KeyEvent.KEYCODE_CONFIGURATION:   // 149
            case 150:   // 备用

            // --- 标准 Android TV 键值 ---
            case KeyEvent.KEYCODE_SETTINGS:        // 176
            case KeyEvent.KEYCODE_MENU:            // 82
            case KeyEvent.KEYCODE_HELP:            // 184
            case KeyEvent.KEYCODE_SEARCH:          // 84 (有些电视用搜索键代替设置)

            // --- 极少数电视用媒体键或数字键组合触发设置 ---
            case KeyEvent.KEYCODE_MEDIA_SETTINGS:  // 198
            case KeyEvent.KEYCODE_MEDIA_AUDIO:     // 173

                if (activity != null) {
                    activity.openSettings();
                    return true;
                }
                break;

            default:
                break;
        }
        return false;
    }

    /**
     * 释放资源（可选，在 onDestroy 中调用）
     */
    public static void release() {
        activity = null;
    }
}
