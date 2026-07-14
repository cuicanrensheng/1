package com.tv.live.manager;

import android.util.Log;
import android.view.KeyEvent;

import com.tv.live.ChannelPanelController;

/**
 * 电视遥控器统一管理器
 *
 * 【职责】
 * 统一管理所有遥控器按键操作，支持三种模式：
 * 1. 播放模式（PLAY_MODE）- 全屏播放时
 * 2. 频道面板模式（CHANNEL_PANEL_MODE）- 频道面板打开时
 * 3. 设置模式（SETTINGS_MODE）- 设置页面打开时
 *
 * 【设计原则】
 * 1. 单一入口：所有按键都走 dispatchKeyEvent()
 * 2. 模式驱动：根据当前模式决定按键行为
 * 3. 焦点记忆：记住每个模式下的焦点位置
 * 4. 边界友好：到达边界时有日志，不崩溃
 * 5. 日志完整：每个按键都记录，方便排查
 */
public class TvRemoteManager {

    private static final String TAG = "TvRemoteManager";

    // ====================== 模式枚举 ======================
    public enum Mode {
        PLAY_MODE,           // 播放模式（全屏播放）
        CHANNEL_PANEL_MODE,  // 频道面板模式
        SETTINGS_MODE        // 设置页面模式
    }

    // ====================== 频道面板焦点位置枚举 ======================
    public enum PanelFocus {
        LEFT_GROUP,      // 左侧 - 分组列表
        LEFT_CHANNEL,    // 左侧 - 频道列表
        LEFT_EPG_BTN,    // 左侧 - 节目单按钮
        RIGHT_BACK_BTN,  // 右侧 - 返回按钮
        RIGHT_CHANNEL,   // 右侧 - 频道列表
        RIGHT_DATE,      // 右侧 - 日期列表
        RIGHT_EPG        // 右侧 - EPG列表
    }

    // ====================== 回调接口 ======================
    public interface OnRemoteActionListener {

        // ================== 播放模式回调 ==================
        void onPlayChannelUp();
        void onPlayChannelDown();
        void onPlayTogglePanel();
        void onPlayOpenSettings();
        boolean onPlayBack();

        // 🟢 新增：媒体键回调
        void onPlayMediaPlayPause();
        void onPlayMediaStop();
        void onPlayInfo();

        // ================== 频道面板模式回调 ==================
        void onPanelMoveUp();
        void onPanelMoveDown();
        void onPanelMoveLeft();
        void onPanelMoveRight();
        void onPanelConfirm();
        boolean onPanelBack();
        void onPanelMenu();
        void onPanelNumber(int number);
        void onPanelFocusChanged(PanelFocus newFocus);

        // 🟢 新增：画中画及焦点回调
        boolean onPipBack();
        void onRequestPlayFocus();

        // 🟢 新增：数字选台回调
        void onChannelNumberSelected(int channelIndex);
        void onShowChannelNumber(String number);
        void onHideChannelNumber();

        // ================== 设置模式回调 ==================
        void onSettingsMoveUp();
        void onSettingsMoveDown();
        void onSettingsConfirm();
        boolean onSettingsBack();
        void onSettingsMenu();
        void onSettingsFocusChanged(int position);
    }

    // ====================== 成员变量 ======================
    private Mode currentMode = Mode.PLAY_MODE;
    private OnRemoteActionListener listener;
    private PanelFocus currentPanelFocus = PanelFocus.LEFT_CHANNEL;
    private boolean isRightPanelOpen = false;
    private int settingsItemCount = 0;
    private int settingsFocusPosition = 0;

    // 🟢 新增成员变量（兼容新版）
    private ChannelPanelController channelPanelController;
    private boolean isInPipMode = false;
    private boolean numberChannelEnable = true;
    private int totalChannelCount = 0;

    // ====================== 构造函数 ======================
    public TvRemoteManager() {
    }

    // ====================== 模式切换 ======================
    public void setMode(Mode mode) {
        this.currentMode = mode;
        Log.d(TAG, "切换模式：" + mode);

        switch (mode) {
            case CHANNEL_PANEL_MODE:
                resetPanelFocus();
                break;
            case SETTINGS_MODE:
                resetSettingsFocus();
                break;
            case PLAY_MODE:
            default:
                break;
        }
    }

    public Mode getCurrentMode() {
        return currentMode;
    }

    // ====================== 设置回调监听器 ======================
    public void setOnRemoteActionListener(OnRemoteActionListener listener) {
        this.listener = listener;
    }

    // ====================== 核心：按键分发 ======================
    public boolean dispatchKeyEvent(int keyCode) {
        switch (currentMode) {
            case CHANNEL_PANEL_MODE:
                return dispatchChannelPanelKey(keyCode);
            case SETTINGS_MODE:
                return dispatchSettingsKey(keyCode);
            case PLAY_MODE:
            default:
                return dispatchPlayKey(keyCode);
        }
    }

    // ====================================================================
    // 一、播放模式按键处理
    // ====================================================================
    private boolean dispatchPlayKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                Log.d(TAG, "播放 上键 → 上一台");
                if (listener != null) listener.onPlayChannelUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                Log.d(TAG, "播放 下键 → 下一台");
                if (listener != null) listener.onPlayChannelDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                Log.d(TAG, "播放 OK键 → 切换面板");
                if (listener != null) listener.onPlayTogglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                Log.d(TAG, "播放 左右键 → 切换面板");
                if (listener != null) listener.onPlayTogglePanel();
                return true;
            case KeyEvent.KEYCODE_MENU:
                Log.d(TAG, "播放 菜单键 → 打开设置");
                if (listener != null) listener.onPlayOpenSettings();
                return true;
            case KeyEvent.KEYCODE_BACK:
                Log.d(TAG, "播放 返回键");
                if (listener != null) return listener.onPlayBack();
                return false;
            // 🟢 新增：媒体键支持
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                Log.d(TAG, "播放 播放/暂停键");
                if (listener != null) listener.onPlayMediaPlayPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                Log.d(TAG, "播放 停止键");
                if (listener != null) listener.onPlayMediaStop();
                return true;
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_TV:
                Log.d(TAG, "播放 信息键");
                if (listener != null) listener.onPlayInfo();
                return true;
            // 数字键
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                int number = keyCode - KeyEvent.KEYCODE_0;
                Log.d(TAG, "播放 数字键 → " + number);
                if (listener != null) listener.onPanelNumber(number);
                return true;
            default:
                return false;
        }
    }

    // ====================================================================
    // 二、频道面板模式按键处理
    // ====================================================================
    private boolean dispatchChannelPanelKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                Log.d(TAG, "面板 上键，焦点：" + currentPanelFocus);
                if (listener != null) listener.onPanelMoveUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                Log.d(TAG, "面板 下键，焦点：" + currentPanelFocus);
                if (listener != null) listener.onPanelMoveDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return handlePanelLeftKey();
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return handlePanelRightKey();
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                Log.d(TAG, "面板 OK键，焦点：" + currentPanelFocus);
                if (listener != null) listener.onPanelConfirm();
                return true;
            case KeyEvent.KEYCODE_BACK:
                Log.d(TAG, "面板 返回键");
                if (listener != null) return listener.onPanelBack();
                return false;
            case KeyEvent.KEYCODE_MENU:
                Log.d(TAG, "面板 菜单键 → 关闭面板");
                if (listener != null) listener.onPanelMenu();
                return true;
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                int number = keyCode - KeyEvent.KEYCODE_0;
                Log.d(TAG, "面板 数字键 → " + number);
                if (listener != null) listener.onPanelNumber(number);
                return true;
            default:
                return false;
        }
    }

    private boolean handlePanelLeftKey() {
        PanelFocus oldFocus = currentPanelFocus;
        switch (currentPanelFocus) {
            case LEFT_EPG_BTN: currentPanelFocus = PanelFocus.LEFT_CHANNEL; break;
            case LEFT_CHANNEL: currentPanelFocus = PanelFocus.LEFT_GROUP; break;
            case RIGHT_EPG: currentPanelFocus = PanelFocus.RIGHT_DATE; break;
            case RIGHT_DATE: currentPanelFocus = PanelFocus.RIGHT_CHANNEL; break;
            case RIGHT_CHANNEL: currentPanelFocus = PanelFocus.RIGHT_BACK_BTN; break;
            default:
                Log.d(TAG, "面板 左键 → 已在最左侧，无法左移");
                return false;
        }
        Log.d(TAG, "面板 左键 → " + oldFocus + " → " + currentPanelFocus);
        if (listener != null) {
            listener.onPanelMoveLeft();
            listener.onPanelFocusChanged(currentPanelFocus);
        }
        return true;
    }

    private boolean handlePanelRightKey() {
        PanelFocus oldFocus = currentPanelFocus;
        switch (currentPanelFocus) {
            case LEFT_GROUP: currentPanelFocus = PanelFocus.LEFT_CHANNEL; break;
            case LEFT_CHANNEL: currentPanelFocus = PanelFocus.LEFT_EPG_BTN; break;
            case RIGHT_BACK_BTN: currentPanelFocus = PanelFocus.RIGHT_CHANNEL; break;
            case RIGHT_CHANNEL: currentPanelFocus = PanelFocus.RIGHT_DATE; break;
            case RIGHT_DATE: currentPanelFocus = PanelFocus.RIGHT_EPG; break;
            default:
                Log.d(TAG, "面板 右键 → 已在最右侧，无法右移");
                return false;
        }
        Log.d(TAG, "面板 右键 → " + oldFocus + " → " + currentPanelFocus);
        if (listener != null) {
            listener.onPanelMoveRight();
            listener.onPanelFocusChanged(currentPanelFocus);
        }
        return true;
    }

    // ====================================================================
    // 三、设置页面模式按键处理
    // ====================================================================
    private boolean dispatchSettingsKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return handleSettingsMoveUp();
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return handleSettingsMoveDown();
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                Log.d(TAG, "设置 OK键 → 第 " + settingsFocusPosition + " 项");
                if (listener != null) listener.onSettingsConfirm();
                return true;
            case KeyEvent.KEYCODE_BACK:
                Log.d(TAG, "设置 返回键 → 关闭设置");
                if (listener != null) return listener.onSettingsBack();
                return false;
            case KeyEvent.KEYCODE_MENU:
                Log.d(TAG, "设置 菜单键 → 关闭设置");
                if (listener != null) listener.onSettingsMenu();
                return true;
            default:
                return false;
        }
    }

    private boolean handleSettingsMoveUp() {
        if (settingsFocusPosition > 0) {
            settingsFocusPosition--;
            Log.d(TAG, "设置 上移 → 第 " + settingsFocusPosition + " 项");
            if (listener != null) {
                listener.onSettingsMoveUp();
                listener.onSettingsFocusChanged(settingsFocusPosition);
            }
            return true;
        } else {
            Log.d(TAG, "设置 上移 → 已在顶部");
            return false;
        }
    }

    private boolean handleSettingsMoveDown() {
        if (settingsFocusPosition < settingsItemCount - 1) {
            settingsFocusPosition++;
            Log.d(TAG, "设置 下移 → 第 " + settingsFocusPosition + " 项");
            if (listener != null) {
                listener.onSettingsMoveDown();
                listener.onSettingsFocusChanged(settingsFocusPosition);
            }
            return true;
        } else {
            Log.d(TAG, "设置 下移 → 已在底部");
            return false;
        }
    }

    // ====================================================================
    // 频道面板相关辅助方法
    // ====================================================================
    public void setRightPanelOpen(boolean open) {
        this.isRightPanelOpen = open;
        resetPanelFocus();
    }

    public PanelFocus getCurrentPanelFocus() {
        return currentPanelFocus;
    }

    public void setCurrentPanelFocus(PanelFocus focus) {
        this.currentPanelFocus = focus;
        Log.d(TAG, "设置面板焦点：" + focus);
    }

    public void resetPanelFocus() {
        currentPanelFocus = isRightPanelOpen ? PanelFocus.RIGHT_CHANNEL : PanelFocus.LEFT_CHANNEL;
        Log.d(TAG, "重置面板焦点：" + currentPanelFocus);
    }

    // ====================================================================
    // 设置页面相关辅助方法
    // ====================================================================
    public void setSettingsItemCount(int count) {
        this.settingsItemCount = count;
        if (settingsFocusPosition >= count) settingsFocusPosition = count - 1;
        if (settingsFocusPosition < 0) settingsFocusPosition = 0;
    }

    public int getSettingsItemCount() {
        return settingsItemCount;
    }

    public int getSettingsFocusPosition() {
        return settingsFocusPosition;
    }

    public void setSettingsFocusPosition(int position) {
        if (position >= 0 && position < settingsItemCount) {
            this.settingsFocusPosition = position;
            Log.d(TAG, "设置焦点：第 " + position + " 项");
        }
    }

    public void resetSettingsFocus() {
        settingsFocusPosition = 0;
        Log.d(TAG, "重置设置焦点到第一项");
    }

    // ====================================================================
    // 🟢 新增方法（兼容新版）
    // ====================================================================
    public void setChannelPanelController(ChannelPanelController controller) {
        this.channelPanelController = controller;
    }

    public void syncMode() {
        if (channelPanelController == null) return;
        if (channelPanelController.isPanelOpen()) {
            if (currentMode != Mode.CHANNEL_PANEL_MODE) {
                setMode(Mode.CHANNEL_PANEL_MODE);
            }
            setRightPanelOpen(channelPanelController.isRightPanelOpen());
        } else {
            if (currentMode != Mode.PLAY_MODE) {
                setMode(Mode.PLAY_MODE);
            }
        }
    }

    public void setInPipMode(boolean inPip) {
        this.isInPipMode = inPip;
    }

    public void setNumberChannelEnable(boolean enable) {
        this.numberChannelEnable = enable;
    }

    public void setTotalChannelCount(int count) {
        this.totalChannelCount = count;
    }

    public boolean dispatchKeyLongPress(int keyCode) {
        return false;
    }

    public boolean handleBackPressed() {
        if (isInPipMode) {
            return false;
        }
        switch (currentMode) {
            case CHANNEL_PANEL_MODE:
                if (channelPanelController != null) {
                    return channelPanelController.handleBackPressed();
                }
                break;
            case SETTINGS_MODE:
                if (listener != null) {
                    return listener.onSettingsBack();
                }
                break;
            case PLAY_MODE:
            default:
                if (listener != null) {
                    return listener.onPlayBack();
                }
                break;
        }
        return false;
    }

    public void release() {
        listener = null;
        channelPanelController = null;
    }
 }
