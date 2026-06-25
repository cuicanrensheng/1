package com.tv.live;

import android.view.KeyEvent;
import android.view.View;

import com.tv.live.manager.*;

/**
 * 按键分发器
 * 作用：统一管理 MainActivity 的按键分发和返回键处理逻辑
 *
 * 【2026-06-25 合并：DirectionKeyHandler + BackPressHandler】
 * 【合并说明】
 * 把 DirectionKeyHandler 和 BackPressHandler 的逻辑都合并到这里，减少文件数量。
 *
 * 【2026-06-25 修复：PanelAutoHideManager 已合并到 ChannelPanelController】
 * 【修复说明】
 * 原来调用 panelAutoHideManager.reset()，
 * 现在 PanelAutoHideManager 已经合并到 ChannelPanelController 里了，
 * 直接调用 channelPanelController.resetAutoHide()。
 */
public class KeyDispatcher {

    // ====================== 单例模式 ======================
    private static KeyDispatcher instance;

    private KeyDispatcher() {
    }

    public static synchronized KeyDispatcher getInstance() {
        if (instance == null) {
            instance = new KeyDispatcher();
        }
        return instance;
    }

    // ====================== 子管理器 ======================
    private PictureInPictureManager pipManager;
    private TvRemoteManager remoteManager;
    private ChannelNumberManager channelNumberManager;
    private ChannelPanelController channelPanelController;
    private KeyEventManager keyEventManager;

    // ====================================================================
    // 方向键相关（从 DirectionKeyHandler 合并）
    // ====================================================================
    private boolean channelReverse = false;
    private PanelToggleCallback panelToggleCallback;

    // ====================================================================
    // 返回键相关（从 BackPressHandler 合并）
    // ====================================================================
    private View playerView;
    private OnBackPressListener backPressListener;

    // ====================== 监听器 ======================
    private OnKeyDispatcherListener listener;

    // ====================================================================
    // 接口定义
    // ====================================================================

    public interface OnKeyDispatcherListener {
        boolean onPipBackKey();
        void onSuperKeyDown(int keyCode, KeyEvent event);
    }

    public interface PanelToggleCallback {
        void onTogglePanel();
    }

    public interface OnBackPressListener {
        void onMoveTaskToBack();
        void onSyncRemoteMode();
        void onSuperBackPressed();
    }

    // ====================================================================
    // Setter 方法
    // ====================================================================

    public void setOnKeyDispatcherListener(OnKeyDispatcherListener listener) {
        this.listener = listener;
    }

    public void setPipManager(PictureInPictureManager manager) {
        this.pipManager = manager;
    }

    // ✅ 2026-06-25 修复：PanelAutoHideManager 已合并到 ChannelPanelController
    @Deprecated
    public void setPanelAutoHideManager(Object manager) {
        // 空实现，向后兼容
    }

    public void setRemoteManager(TvRemoteManager manager) {
        this.remoteManager = manager;
    }

    public void setChannelNumberManager(ChannelNumberManager manager) {
        this.channelNumberManager = manager;
    }

    public void setChannelPanelController(ChannelPanelController controller) {
        this.channelPanelController = controller;
    }

    @Deprecated
    public void setDirectionKeyHandler(Object handler) {
        // 空实现，向后兼容
    }

    public void setKeyEventManager(KeyEventManager manager) {
        this.keyEventManager = manager;
    }

    // ====================================================================
    // 方向键相关 setter（从 DirectionKeyHandler 合并）
    // ====================================================================

    @Deprecated
    public void setPanelController(ChannelPanelController controller) {
        this.channelPanelController = controller;
    }

    public void setChannelReverse(boolean reverse) {
        this.channelReverse = reverse;
    }

    public boolean isChannelReverse() {
        return channelReverse;
    }

    public void setPanelToggleCallback(PanelToggleCallback callback) {
        this.panelToggleCallback = callback;
    }

    // ====================================================================
    // 返回键相关 setter（从 BackPressHandler 合并）
    // ====================================================================

    @Deprecated
    public void setBackPressHandler(Object handler) {
        // 空实现，向后兼容
    }

    public void setPlayerView(View view) {
        this.playerView = view;
    }

    public void setOnBackPressListener(OnBackPressListener listener) {
        this.backPressListener = listener;
    }

    // ====================================================================
    // 核心方法：按键分发
    // ====================================================================
    public boolean dispatchKeyEvent(int keyCode, KeyEvent event) {
        // 1. 画中画模式下的特殊处理
        if (pipManager != null && pipManager.isInPipMode()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (listener != null) {
                    return listener.onPipBackKey();
                }
                return true;
            }
            return false;
        }

        // ✅ 2026-06-25 修复：PanelAutoHideManager → ChannelPanelController
        // 2. 重置面板自动隐藏计时
        if (channelPanelController != null) {
            channelPanelController.resetAutoHide();
        }

        // 3. 遥控器统一管理
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // 4. 数字选台
        if (channelNumberManager != null && channelNumberManager.handleNumberKey(keyCode)) {
            return true;
        }

        // 5. 频道面板（面板打开时才处理）
        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // 6. 方向键处理（播放模式下）
        if (handleDirectionKey(keyCode)) {
            return true;
        }

        // 7. 其他按键
        if (keyEventManager != null && keyEventManager.dispatchKey(keyCode)) {
            return true;
        }

        return false;
    }

    // ====================================================================
    // 方向键处理（从 DirectionKeyHandler 合并）
    // ====================================================================
    public boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                SettingsActivity.logOperation("【按键】方向键上 → 反转状态："
                        + (channelReverse ? "开启" : "关闭"));
                if (channelPanelController != null) {
                    channelPanelController.switchUp();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                SettingsActivity.logOperation("【按键】方向键下 → 反转状态："
                        + (channelReverse ? "开启" : "关闭"));
                if (channelPanelController != null) {
                    channelPanelController.switchDown();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (channelNumberManager != null && channelNumberManager.isInputting()) {
                    channelNumberManager.confirmChannelNum();
                    return true;
                }
                if (panelToggleCallback != null) {
                    panelToggleCallback.onTogglePanel();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (panelToggleCallback != null) {
                    panelToggleCallback.onTogglePanel();
                }
                return true;

            default:
                return false;
        }
    }

    // ====================================================================
    // 返回键处理（从 BackPressHandler 合并）
    // ====================================================================
    public boolean handleBackPressed() {
        // 1. 画中画模式下：退到后台
        if (pipManager != null && pipManager.isInPipMode()) {
            if (backPressListener != null) {
                backPressListener.onMoveTaskToBack();
            }
            return true;
        }

        // 2. 数字选台输入中：取消输入
        if (channelNumberManager != null && channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            SettingsActivity.logOperation("【返回】取消数字选台输入");
            return true;
        }

        // 3. 遥控器管理器处理
        if (remoteManager != null) {
            if (remoteManager.dispatchKeyEvent(KeyEvent.KEYCODE_BACK)) {
                SettingsActivity.logOperation("【返回】遥控器管理器处理");
                return true;
            }
        }

        // 4. 频道面板处理
        if (channelPanelController != null && channelPanelController.handleBackPressed()) {
            if (playerView != null) {
                playerView.requestFocus();
            }
            if (backPressListener != null) {
                backPressListener.onSyncRemoteMode();
            }
            SettingsActivity.logOperation("【返回】频道面板处理");
            return true;
        }

        // 5. 都不处理，返回 false
        return false;
    }

    // ====================================================================
    // 资源释放
    // ====================================================================
    public void release() {
        pipManager = null;
        remoteManager = null;
        channelNumberManager = null;
        channelPanelController = null;
        keyEventManager = null;
        listener = null;

        // 方向键相关
        panelToggleCallback = null;
        channelReverse = false;

        // 返回键相关
        playerView = null;
        backPressListener = null;
    }
}
