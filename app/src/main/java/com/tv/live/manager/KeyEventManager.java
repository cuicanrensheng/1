package com.tv.live.manager;

import android.util.Log;
import android.view.KeyEvent;

import com.tv.live.MainActivity;

/**
 * 按键事件管理器（老版本兼容）
 *
 * 【职责】
 * 处理遥控器的按键事件，包括：
 * 1. 上下键：切换频道（带反转逻辑）
 * 2. OK键/确认键：打开/关闭频道面板
 * 3. Menu键：打开设置页面
 *
 * 【2026-06-20 修复：换台反转失效 + 详细操作日志】
 * 【问题原因】
 * 之前直接调用 activity.playPrev() 和 activity.playNext()，
 * 这两个是底层方法，不考虑反转设置，导致反转失效，而且没有日志很难排查。
 *
 * 【解决方案】
 * 1. 加上反转判断：调用 activity.isChannelReverse() 获取反转状态
 * 2. 加上详细的操作日志（使用原生日志），记录是从 KeyEventManager 入口触发的
 *
 * 【日志说明】所有日志均使用 android.util.Log.d，Tag 为 "KeyEventManager"
 */
public class KeyEventManager {

    // ====================== 日志 TAG ======================
    private static final String TAG = "KeyEventManager";

    // ====================== 成员变量 ======================
    /** 持有 MainActivity 引用，用于调用播放/切换方法 */
    private final MainActivity activity;

    // ====================== 构造函数 ======================
    /**
     * 构造函数
     * @param activity MainActivity 实例
     */
    public KeyEventManager(MainActivity activity) {
        this.activity = activity;
    }

    // ====================== 核心方法 ======================
    /**
     * 分发按键事件
     *
     * @param keyCode 按键码
     * @return 是否处理了按键（true=已处理）
     */
    public boolean dispatchKey(int keyCode) {
        switch (keyCode) {
            // ====================================================================
            // ✅ 上键：加上反转判断 + 日志
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_UP:
                // 记录入口日志
                Log.d(TAG, "上键 → 反转状态：" + (activity.isChannelReverse() ? "开启" : "关闭"));
                
                if (activity.isChannelReverse()) {
                    // 反转开启：上键 = 下一台
                    activity.playNext();
                } else {
                    // 反转关闭：上键 = 上一台
                    activity.playPrev();
                }
                return true;

            // ====================================================================
            // ✅ 下键：加上反转判断 + 日志
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 记录入口日志
                Log.d(TAG, "下键 → 反转状态：" + (activity.isChannelReverse() ? "开启" : "关闭"));
                
                if (activity.isChannelReverse()) {
                    // 反转开启：下键 = 上一台
                    activity.playPrev();
                } else {
                    // 反转关闭：下键 = 下一台
                    activity.playNext();
                }
                return true;

            // ====================================================================
            // OK键/确认键：打开/关闭频道面板
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                Log.d(TAG, "OK键 → 切换面板");
                activity.togglePanel();
                return true;

            // ====================================================================
            // 菜单键：打开设置页面
            // ====================================================================
            case KeyEvent.KEYCODE_MENU:
                Log.d(TAG, "Menu键 → 打开设置");
                activity.openSettings();
                return true;

            default:
                return false;
        }
    }
}
