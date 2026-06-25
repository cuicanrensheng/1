package com.tv.live;

import android.view.KeyEvent;

import com.tv.live.manager.*;

/**
 * 按键分发器
 * 作用：统一管理 MainActivity 的按键分发逻辑
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 onKeyDown() 方法里的分发逻辑抽离到这里，
 * 统一管理按键事件的分发顺序和处理逻辑。
 *
 * 【分发顺序】
 * 1. 画中画模式下的特殊处理
 * 2. 重置面板自动隐藏计时
 * 3. TvRemoteManager（遥控器统一管理）
 * 4. ChannelNumberManager（数字选台）
 * 5. ChannelPanelController（频道面板）
 * 6. DirectionKeyHandler（方向键处理）
 * 7. KeyEventManager（其他按键）
 */
public class KeyDispatcher {

    private static KeyDispatcher instance;

    private PictureInPictureManager pipManager;
    private PanelAutoHideManager panelAutoHideManager;
    private TvRemoteManager remoteManager;
    private ChannelNumberManager channelNumberManager;
    private ChannelPanelController channelPanelController;
    private DirectionKeyHandler directionKeyHandler;
    private KeyEventManager keyEventManager;

    private OnKeyDispatcherListener listener;

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

    public void setDirectionKeyHandler(DirectionKeyHandler handler) {
        this.directionKeyHandler = handler;
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

        if (directionKeyHandler != null && directionKeyHandler.handleDirectionKey(keyCode)) {
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
        directionKeyHandler = null;
        keyEventManager = null;
        listener = null;
    }
}
