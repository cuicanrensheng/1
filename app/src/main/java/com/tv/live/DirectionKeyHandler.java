package com.tv.live;

import android.view.KeyEvent;

import com.tv.live.manager.ChannelPanelController;

/**
 * 方向键处理器
 * 作用：统一处理播放模式下的方向键逻辑
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 handleDirectionKey() 方法抽离到这里，
 * 统一管理播放模式下的方向键处理逻辑。
 *
 * 【职责】
 * - 上/下键：切换频道
 * - 左/右键：切换面板显示/隐藏
 * - OK/确认键：切换面板显示/隐藏（或确认数字选台）
 */
public class DirectionKeyHandler {

    private static DirectionKeyHandler instance;

    private ChannelPanelController panelController;
    private ChannelNumberManager channelNumberManager;
    private PanelToggleCallback panelToggleCallback;

    private boolean channelReverse = false;

    private DirectionKeyHandler() {
    }

    public static synchronized DirectionKeyHandler getInstance() {
        if (instance == null) {
            instance = new DirectionKeyHandler();
        }
        return instance;
    }

    public interface PanelToggleCallback {
        void onTogglePanel();
    }

    public void setPanelController(ChannelPanelController controller) {
        this.panelController = controller;
    }

    public void setChannelNumberManager(ChannelNumberManager manager) {
        this.channelNumberManager = manager;
    }

    public void setPanelToggleCallback(PanelToggleCallback callback) {
        this.panelToggleCallback = callback;
    }

    public void setChannelReverse(boolean reverse) {
        this.channelReverse = reverse;
    }

    public boolean isChannelReverse() {
        return channelReverse;
    }

    public boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                SettingsActivity.logOperation("【按键】方向键上 → 反转状态："
                        + (channelReverse ? "开启" : "关闭"));
                if (panelController != null) {
                    panelController.switchUp();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                SettingsActivity.logOperation("【按键】方向键下 → 反转状态："
                        + (channelReverse ? "开启" : "关闭"));
                if (panelController != null) {
                    panelController.switchDown();
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

    public void release() {
        panelController = null;
        channelNumberManager = null;
        panelToggleCallback = null;
    }
}
