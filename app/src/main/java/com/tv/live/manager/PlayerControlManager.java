package com.tv.live.manager;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.media3.ui.PlayerView;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

/**
 * 播放器控制栏管理器
 */
@SuppressLint("UnsafeOptInUsageError")
public class PlayerControlManager {

    // 🟢【修复内存泄漏】activity/infoDisplayManager 不能再 final，因为 release 需要置 null 断开 Activity 引用
    private MainActivity activity;
    private InfoDisplayManager infoDisplayManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private GestureManager gestureManager;
    private boolean isControllerShowing = false;

    private final Runnable hideControllerRunnable = new Runnable() {
        @Override
        public void run() {
            // 🟢【修复内存泄漏】activity 可能在 release 后置 null，先做 null 检查
            if (activity == null) return;
            PlayerView currentPlayerView = activity.getPlayerView();
            if (currentPlayerView != null && isControllerShowing) {
                hideExoController();
            }
        }
    };

    public PlayerControlManager(MainActivity activity,
                                 GestureManager gestureManager,
                                 InfoDisplayManager infoDisplayManager) {
        this.activity = activity;
        this.gestureManager = gestureManager;
        this.infoDisplayManager = infoDisplayManager;
    }

    public void updateGestureManager(GestureManager newGestureManager) {
        this.gestureManager = newGestureManager;
    }

    public boolean isControllerShowing() {
        return isControllerShowing;
    }

    public void showExoController() {
        if (!activity.isInCatchUpMode()) return;

        PlayerView currentPlayerView = activity.getPlayerView();
        if (currentPlayerView == null) return;

        if (activity.getPipManager() != null && activity.getPipManager().isInPipMode()) {
            return;
        }

        mainHandler.removeCallbacks(hideControllerRunnable);

        if (activity.getTouchListener() != null) {
            currentPlayerView.setOnTouchListener(null);
        }

        currentPlayerView.setUseController(true);
        currentPlayerView.showController();
        isControllerShowing = true;
        mainHandler.postDelayed(hideControllerRunnable, 5000);

        if (infoDisplayManager != null) {
            infoDisplayManager.hideInfoBar();
        }
    }

    public void hideExoController() {
        PlayerView currentPlayerView = activity.getPlayerView();
        if (currentPlayerView == null) return;

        mainHandler.removeCallbacks(hideControllerRunnable);

        currentPlayerView.hideController();
        currentPlayerView.setUseController(false);
        isControllerShowing = false;

        if (activity.getTouchListener() != null) {
            if (gestureManager != null) {
                final PlayerGestureHelper newGestureHelper = gestureManager.create();
                activity.getTouchListener().updateGestureHelper(newGestureHelper);
            }
            currentPlayerView.setOnTouchListener(activity.getTouchListener());
        }
    }

    public void onResume() {
        if (activity.getPipManager() != null && activity.getPipManager().isInPipMode()) {
            PlayerView currentPlayerView = activity.getPlayerView();
            if (currentPlayerView != null) {
                currentPlayerView.setUseController(false);
                currentPlayerView.hideController();
                isControllerShowing = false;
            }
            return;
        }
        hideExoController();
    }

    public void onOpenSettings() {
        hideExoController();
        PlayerView currentPlayerView = activity.getPlayerView();
        if (currentPlayerView != null) {
            currentPlayerView.setUseController(false);
        }
    }

    public void onSettingsClosed() {
        hideExoController();
    }

    private static final long SEEK_STEP_MS = 10_000;

    public void seekForward() {
        MainActivity act = activity;
        if (act == null || !act.isInCatchUpMode()) return;
        if (act.getPlayerManager() == null) return;
        long cur = act.getPlayerManager().getCurrentPosition();
        long dur = act.getPlayerManager().getDuration();
        if (dur <= 0) return;
        long target = Math.min(cur + SEEK_STEP_MS, dur);
        act.getPlayerManager().seekTo(target);
        showExoController();
    }

    public void seekBackward() {
        MainActivity act = activity;
        if (act == null || !act.isInCatchUpMode()) return;
        if (act.getPlayerManager() == null) return;
        long cur = act.getPlayerManager().getCurrentPosition();
        long target = Math.max(cur - SEEK_STEP_MS, 0);
        act.getPlayerManager().seekTo(target);
        showExoController();
    }

    public void release() {
        // 🟢【修复内存泄漏】同时清除全部回调 + 解除外部强引用，确保 Activity 可被回收
        mainHandler.removeCallbacksAndMessages(null);
        activity = null;
        infoDisplayManager = null;
        gestureManager = null;
        isControllerShowing = false;
    }
}
