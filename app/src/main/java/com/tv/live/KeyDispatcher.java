package com.tv.live;

import android.view.KeyEvent;
import android.view.View;

import com.tv.live.manager.*;

/**
 * 按键分发器
 * 作用：统一管理 MainActivity 的按键分发逻辑，包括方向键、返回键等
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 onKeyDown() 方法里的分发逻辑抽离到这里，
 * 统一管理按键事件的分发顺序和处理逻辑。
 *
 * 【2026-06-25 合并：DirectionKeyHandler + BackPressHandler】
 * 【合并说明】
 * 把 DirectionKeyHandler（方向键处理）和 BackPressHandler（返回键处理）
 * 都合并到 KeyDispatcher 里，减少文件数量。
 * 这三个都是按键处理相关，放在一起更合理。
 *
 * 【分发顺序】
 * 1. 画中画模式下的特殊处理
 * 2. 重置面板自动隐藏计时
 * 3. TvRemoteManager（遥控器统一管理）
 * 4. ChannelNumberManager（数字选台）
 * 5. ChannelPanelController（频道面板）
 * 6. 方向键处理（上/下切台、左/右/OK切换面板）
 * 7. KeyEventManager（其他按键）
 *
 * 【返回键处理顺序】
 * 1. 画中画模式下：退到后台
 * 2. 数字选台输入中：取消输入
 * 3. 遥控器管理器处理
 * 4. 频道面板处理
 * 5. 都不处理：调用 super.onBackPressed()
 */
public class KeyDispatcher {

    private static KeyDispatcher instance;

    private PictureInPictureManager pipManager;
    private PanelAutoHideManager panelAutoHideManager;
    private TvRemoteManager remoteManager;
    private ChannelNumberManager channelNumberManager;
    private ChannelPanelController channelPanelController;
    private KeyEventManager keyEventManager;

    private OnKeyDispatcherListener listener;

    // ====================================================================
    // ✅ 2026-06-25 合并：DirectionKeyHandler - 方向键处理相关
    // ====================================================================
    private boolean channelReverse = false;

    /**
     * 面板切换回调
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     */
    public interface PanelToggleCallback {
        void onTogglePanel();
    }

    private PanelToggleCallback panelToggleCallback;

    /**
     * 设置面板切换回调
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     */
    public void setPanelToggleCallback(PanelToggleCallback callback) {
        this.panelToggleCallback = callback;
    }

    /**
     * 设置切台反转
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     */
    public void setChannelReverse(boolean reverse) {
        this.channelReverse = reverse;
    }

    /**
     * 获取切台反转状态
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     */
    public boolean isChannelReverse() {
        return channelReverse;
    }

    /**
     * 处理方向键
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     *
     * @param keyCode 按键码
     * @return true=已处理，false=未处理
     */
    private boolean handleDirectionKey(int keyCode) {
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
    // ✅ 2026-06-25 合并：BackPressHandler - 返回键处理相关
    // ====================================================================
    private View playerView;

    /**
     * 返回键监听器
     * 【2026-06-25 合并：从 BackPressHandler 移过来】
     */
    public interface OnBackPressListener {
        void onMoveTaskToBack();
        void onSyncRemoteMode();
        void onSuperBackPressed();
    }

    private OnBackPressListener backPressListener;

    /**
     * 设置返回键监听器
     * 【2026-06-25 合并：从 BackPressHandler 移过来】
     */
    public void setOnBackPressListener(OnBackPressListener listener) {
        this.backPressListener = listener;
    }

    /**
     * 设置播放器视图（用于返回后重新请求焦点）
     * 【2026-06-25 合并：从 BackPressHandler 移过来】
     */
    public void setPlayerView(View view) {
        this.playerView = view;
    }

    /**
     * 处理返回键
     * 【2026-06-25 合并：从 BackPressHandler 移过来】
     *
     * @return true=已处理，false=未处理（需要调用 super.onBackPressed()）
     */
    public boolean handleBackPressed() {
        if (pipManager != null && pipManager.isInPipMode()) {
            if (backPressListener != null) {
                backPressListener.onMoveTaskToBack();
            }
            return true;
        }

        if (channelNumberManager != null && channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            SettingsActivity.logOperation("【返回】取消数字选台输入");
            return true;
        }

        if (remoteManager != null) {
            if (remoteManager.dispatchKeyEvent(KeyEvent.KEYCODE_BACK)) {
                SettingsActivity.logOperation("【返回】遥控器管理器处理");
                return true;
            }
        }

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

        return false;
    }

    // ====================================================================
    // 原有 KeyDispatcher 代码
    // ====================================================================

    private KeyDispatcher() {
    }

    public static synchronized KeyDispatcher getInstance() {
        if (instance == null) {
            instance = new KeyDispatcher();
        }
        return instance;
    }

    public interface OnKeyDispatcherListener {
        boolean onPipBackKey();
        void onSuperKeyDown(int keyCode, KeyEvent event);
    }

    public void setOnKeyDispatcherListener(OnKeyDispatcherListener listener) {
        this.listener = listener;
    }

    public void setPipManager(PictureInPictureManager manager) {
        this.pipManager = manager;
    }

    public void setPanelAutoHideManager(PanelAutoHideManager manager) {
        this.panelAutoHideManager = manager;
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

    /**
     * 【2026-06-25 合并：保留 setDirectionKeyHandler 方法用于向后兼容】
     * 现在 DirectionKeyHandler 已经合并到 KeyDispatcher 里了，
     * 这个方法主要是为了不让旧代码报错。
     */
    @Deprecated
    public void setDirectionKeyHandler(Object handler) {
        // 空实现，向后兼容
    }

    public void setKeyEventManager(KeyEventManager manager) {
        this.keyEventManager = manager;
    }

    public boolean dispatchKeyEvent(int keyCode, KeyEvent event) {
        if (pipManager != null && pipManager.isInPipMode()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (listener != null) {
                    return listener.onPipBackKey();
                }
                return true;
            }
            return false;
        }

        if (panelAutoHideManager != null) {
            panelAutoHideManager.reset();
        }

        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }

        if (channelNumberManager != null && channelNumberManager.handleNumberKey(keyCode)) {
            return true;
        }

        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // ✅ 2026-06-25 合并：原来调用 directionKeyHandler.handleDirectionKey()，现在调用自己的
        if (handleDirectionKey(keyCode)) {
            return true;
        }

        if (keyEventManager != null && keyEventManager.dispatchKey(keyCode)) {
            return true;
        }

        return false;
    }

    public void release() {
        pipManager = null;
        panelAutoHideManager = null;
        remoteManager = null;
        channelNumberManager = null;
        channelPanelController = null;
        keyEventManager = null;
        listener = null;
        panelToggleCallback = null;
        backPressListener = null;
        playerView = null;
        channelReverse = false;
    }
}
