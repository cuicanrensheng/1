package com.tv.live.manager;

import android.annotation.SuppressLint; // ✅ 新增导入
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.media3.ui.PlayerView;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

/**
 * 播放器控制栏管理器
 */
@SuppressLint("UnsafeOptInUsageError") // ✅ 消除 Media3 不稳定 API 的 Lint 错误
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

    public void showExoController() {
        // 🛡️ 核心拦截：只有回看模式才允许显示控制栏
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

    // ✅【核心修改】：每次隐藏时，彻底「斩杀」控制栏的弹出权限
    public void hideExoController() {
        if (playerView == null) return;
        mainHandler.removeCallbacks(hideControllerRunnable);
        
        // 1. 先隐藏UI
        playerView.hideController();
        // 2. 强制剥夺控制栏的响应权限
        playerView.setUseController(false);
        isControllerShowing = false;

        // 恢复自定义触摸监听
        if (activity.getTouchListener() != null) {
            if (gestureManager != null) {
                final PlayerGestureHelper newGestureHelper = gestureManager.create();
                activity.getTouchListener().updateGestureHelper(newGestureHelper);
            }
            playerView.setOnTouchListener(activity.getTouchListener());
        }
    }

    public void onResume() {
        if (activity.getPipManager() != null && activity.getPipManager().isInPipMode()) {
            if (playerView != null) {
                playerView.setUseController(false);
                playerView.hideController();
                isControllerShowing = false;
            }
            return;
        }

        if (playerView != null) {
            hideExoController();
        }
    }

    public void onOpenSettings() {
        hideExoController();
        if (playerView != null) {
            playerView.setUseController(false);
        }
    }

    public void onSettingsClosed() {
        if (playerView == null) return;
        // 返回时再次锁定，防止某些特定时序意外恢复
        hideExoController();
    }

    public void release() {
        mainHandler.removeCallbacksAndMessages(null);
    }
}
