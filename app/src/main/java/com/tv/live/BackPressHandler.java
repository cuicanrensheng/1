package com.tv.live;

import android.view.KeyEvent;
import android.view.View;

import com.tv.live.manager.*;

/**
 * 返回键处理器
 * 作用：统一管理 MainActivity 的返回键处理逻辑
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 onBackPressed() 方法里的逻辑抽离到这里，
 * 统一管理返回键的处理顺序和逻辑。
 *
 * 【处理顺序】
 * 1. 画中画模式下：退到后台
 * 2. 数字选台输入中：取消输入
 * 3. 遥控器管理器处理
 * 4. 频道面板处理
 * 5. 都不处理：调用 super.onBackPressed()
 */
public class BackPressHandler {

    private static BackPressHandler instance;

    private PictureInPictureManager pipManager;
    private ChannelNumberManager channelNumberManager;
    private TvRemoteManager remoteManager;
    private ChannelPanelController channelPanelController;

    private View playerView;

    private OnBackPressListener listener;

    private BackPressHandler() {
    }

    public static synchronized BackPressHandler getInstance() {
        if (instance == null) {
            instance = new BackPressHandler();
        }
        return instance;
    }

    public interface OnBackPressListener {
        void onMoveTaskToBack();
        void onSyncRemoteMode();
        void onSuperBackPressed();
    }

    public void setOnBackPressListener(OnBackPressListener listener) {
        this.listener = listener;
    }

    public void setPipManager(PictureInPictureManager manager) {
        this.pipManager = manager;
    }

    public void setChannelNumberManager(ChannelNumberManager manager) {
        this.channelNumberManager = manager;
    }

    public void setRemoteManager(TvRemoteManager manager) {
        this.remoteManager = manager;
    }

    public void setChannelPanelController(ChannelPanelController controller) {
        this.channelPanelController = controller;
    }

    public void setPlayerView(View view) {
        this.playerView = view;
    }

    public boolean handleBackPressed() {
        if (pipManager != null && pipManager.isInPipMode()) {
            if (listener != null) {
                listener.onMoveTaskToBack();
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
            if (listener != null) {
                listener.onSyncRemoteMode();
            }
            SettingsActivity.logOperation("【返回】频道面板处理");
            return true;
        }

        return false;
    }

    public void release() {
        pipManager = null;
        channelNumberManager = null;
        remoteManager = null;
        channelPanelController = null;
        playerView = null;
        listener = null;
    }
}
