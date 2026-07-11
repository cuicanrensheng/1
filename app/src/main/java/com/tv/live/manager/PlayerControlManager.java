package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.media3.ui.PlayerView;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

/**
 * 播放器控制栏管理器
 * 统一处理 ExoPlayer 原生控制栏的显示/隐藏以及焦点交互
 */
public class PlayerControlManager {

    private final MainActivity activity;
    private final PlayerView playerView;
    private final GestureManager gestureManager;
    private final InfoDisplayManager infoDisplayManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isControllerShowing = false;

    private final Runnable hideControllerRunnable = new Runnable() {
        @Override
        public void run() {
            if (playerView != null && isControllerShowing) {
                hideExoController();
            }
        }
    };

    public PlayerControlManager(MainActivity activity,
                                 PlayerView playerView,
                                 GestureManager gestureManager,
                                 InfoDisplayManager infoDisplayManager) {
        this.activity = activity;
        this.playerView = playerView;
        this.gestureManager = gestureManager;
        this.infoDisplayManager = infoDisplayManager;
    }

    public boolean isControllerShowing() {
        return isControllerShowing;
    }

    /**
     * 显示控制栏（仅在回看模式下有效，且画中画模式下禁止）
     */
    public void showExoController() {
        if (!activity.isInCatchUpMode()) return;
        if (playerView == null) return;

        if (activity.getPipManager() != null && activity.getPipManager().isInPipMode()) {
            return;
        }

        mainHandler.removeCallbacks(hideControllerRunnable);

        if (activity.getTouchListener() != null) {
            playerView.setOnTouchListener(null);
        }

        playerView.setUseController(true);
        playerView.showController();
        isControllerShowing = true;
        mainHandler.postDelayed(hideControllerRunnable, 5000);

        if (infoDisplayManager != null) {
            infoDisplayManager.hideInfoBar();
        }
    }

    /**
     * 隐藏控制栏，并恢复触摸监听
     */
    public void hideExoController() {
        if (playerView == null) return;
        mainHandler.removeCallbacks(hideControllerRunnable);
        playerView.hideController();
        isControllerShowing = false;

        if (activity.getTouchListener() != null) {
            if (gestureManager != null) {
                final PlayerGestureHelper newGestureHelper = gestureManager.create();
                activity.getTouchListener().updateGestureHelper(newGestureHelper);
            }
            playerView.setOnTouchListener(activity.getTouchListener());
        }
    }

    /**
     * 页面恢复时调用（onResume），确保控制栏不自动弹出
     */
    public void onResume() {
        if (playerView != null && !isControllerShowing) {
            hideExoController();
        }
    }

    /**
     * 打开设置页时调用，强制禁用控制栏，避免在 Activity 切换瞬间弹出
     */
    public void onOpenSettings() {
        hideExoController();
        if (playerView != null) {
            playerView.setUseController(false);
        }
    }

    /**
     * 从设置页返回时调用，恢复控制栏功能
     */
    public void onSettingsClosed() {
        if (playerView == null) return;
        if (!activity.isInCatchUpMode() && !isControllerShowing) {
            playerView.setUseController(true);
        }
        if (!isControllerShowing) {
            hideExoController();
        }
    }

    /**
     * 释放资源，清理延迟任务
     */
    public void release() {
        mainHandler.removeCallbacksAndMessages(null);
    }
}
