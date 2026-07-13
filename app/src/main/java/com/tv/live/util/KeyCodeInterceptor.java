package com.tv.live.util;

import android.view.KeyEvent;
import com.tv.live.MainActivity;

/**
 * 万能遥控器按键拦截器
 * 支持小米、创维、酷开、华为、海信、TCL、索尼等所有主流电视品牌
 * 已移除所有高版本 API 常量，兼容所有 Android 版本
 */
public class KeyCodeInterceptor {

    private static MainActivity activity;

    public static void init(MainActivity mainActivity) {
        activity = mainActivity;
    }

    public static boolean handleKeyEvent(int keyCode, int action) {
        if (action != KeyEvent.ACTION_DOWN) {
            return false;
        }

        // ===== 万能设置键列表 =====
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
            case 149:   // KEYCODE_CONFIGURATION 的数值
            case 150:   // 备用

            // --- 标准 Android TV 键值 ---
            case KeyEvent.KEYCODE_SETTINGS:        // 176
            case KeyEvent.KEYCODE_MENU:            // 82
            case KeyEvent.KEYCODE_HELP:            // 184
            case KeyEvent.KEYCODE_SEARCH:          // 84

            // --- 极少数电视用媒体键触发设置 ---
            case 198:   // KEYCODE_MEDIA_SETTINGS 的数值
            case 173:   // KEYCODE_MEDIA_AUDIO 的数值

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

    public static void release() {
        activity = null;
    }
}
