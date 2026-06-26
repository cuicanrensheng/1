package com.tv.live.manager;

import android.view.KeyEvent;

import com.tv.live.MainActivity;
import com.tv.live.SettingsActivity;

/**
 * 按键事件管理器
 *
 * 【职责】
 * 处理播放模式下（面板关闭时）的遥控器按键事件，包括：
 * 1. 上下键：切换频道（带反转逻辑，通过 ChannelPanelController）
 * 2. 左右键：打开/关闭频道面板
 * 3. OK键/确认键：打开/关闭频道面板（数字输入时确认选台）
 * 4. Menu键：打开设置页面
 *
 * 【2026-06-20 修复：换台反转失效 + 详细操作日志】
 * 【问题原因】
 * 之前直接调用 activity.playPrev() 和 activity.playNext()，
 * 这两个是底层方法，不考虑反转设置，导致反转失效，而且没有日志很难排查。
 *
 * 【解决方案】
 * 1. 加上反转判断：调用 activity.isChannelReverse() 获取反转状态
 * 2. 加上详细的操作日志，记录是从 KeyEventManager 入口触发的
 *
 * 【2026-06-26 修改：合并 handleDirectionKey 逻辑】
 * 【修改说明】
 * 把原来 MainActivity 中的 handleDirectionKey() 方法合并到这里，
 * 统一管理播放模式下的所有方向键处理。
 * 
 * 【主要变化】
 * 1. 上下键改用 channelPanelController.switchUp()/switchDown()
 *    （反转逻辑封装在 ChannelPanelController 中，更规范）
 * 2. 新增左右键处理：打开/关闭面板
 * 3. OK键新增数字输入确认逻辑
 * 4. 保留 Menu 键打开设置
 */
public class KeyEventManager {
    private final MainActivity activity;
    private ChannelPanelController channelPanelController;
    private ChannelNumberManager channelNumberManager;

    public KeyEventManager(MainActivity activity) {
        this.activity = activity;
    }

    /**
     * 设置频道面板控制器
     * 
     * 【作用】
     * 用于调用 switchUp()/switchDown() 等切台方法，
     * 以及 togglePanel() 等面板控制方法。
     */
    public void setChannelPanelController(ChannelPanelController controller) {
        this.channelPanelController = controller;
    }

    /**
     * 设置数字选台管理器
     * 
     * 【作用】
     * OK 键时判断是否正在输入数字，
     * 如果是则确认选台，而不是打开面板。
     */
    public void setChannelNumberManager(ChannelNumberManager manager) {
        this.channelNumberManager = manager;
    }

    /**
     * 分发按键事件（播放模式下的方向键处理）
     *
     * @param keyCode 按键码
     * @return 是否处理了按键（true=已处理）
     */
    public boolean dispatchKey(int keyCode) {
        switch (keyCode) {
            // ====================================================================
            // 上键：切换到上一个频道（带反转逻辑）
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_UP:
                SettingsActivity.logOperation("【按键】KeyEventManager 上键 → 反转状态：" 
                        + (activity.isChannelReverse() ? "开启" : "关闭"));
                
                if (channelPanelController != null) {
                    channelPanelController.switchUp();
                } else {
                    // 兜底：如果没有设置 controller，用旧方式
                    if (activity.isChannelReverse()) {
                        activity.playNext();
                    } else {
                        activity.playPrev();
                    }
                }
                return true;

            // ====================================================================
            // 下键：切换到下一个频道（带反转逻辑）
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_DOWN:
                SettingsActivity.logOperation("【按键】KeyEventManager 下键 → 反转状态：" 
                        + (activity.isChannelReverse() ? "开启" : "关闭"));
                
                if (channelPanelController != null) {
                    channelPanelController.switchDown();
                } else {
                    // 兜底：如果没有设置 controller，用旧方式
                    if (activity.isChannelReverse()) {
                        activity.playPrev();
                    } else {
                        activity.playNext();
                    }
                }
                return true;

            // ====================================================================
            // 左键：打开/关闭频道面板
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_LEFT:
                SettingsActivity.logOperation("【按键】KeyEventManager 左键 → 切换面板");
                activity.togglePanel();
                return true;

            // ====================================================================
            // 右键：打开/关闭频道面板
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                SettingsActivity.logOperation("【按键】KeyEventManager 右键 → 切换面板");
                activity.togglePanel();
                return true;

            // ====================================================================
            // OK键/确认键：数字输入时确认选台，否则打开/关闭面板
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 如果正在输入数字，确认选台
                if (channelNumberManager != null && channelNumberManager.isInputting()) {
                    SettingsActivity.logOperation("【按键】KeyEventManager OK键 → 确认数字选台");
                    channelNumberManager.confirmChannelNum();
                    return true;
                }
                
                // 否则切换面板
                SettingsActivity.logOperation("【按键】KeyEventManager OK键 → 切换面板");
                activity.togglePanel();
                return true;

            // ====================================================================
            // Menu键：打开设置页面
            // ====================================================================
            case KeyEvent.KEYCODE_MENU:
                SettingsActivity.logOperation("【按键】KeyEventManager Menu键 → 打开设置");
                activity.openSettings();
                return true;
        }
        return false;
    }
}
