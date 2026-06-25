package com.tv.live;

import android.os.Handler;
import android.os.Looper;

import com.tv.live.manager.ChannelPanelController;

/**
 * 面板自动隐藏管理器
 * 作用：管理频道面板的自动隐藏计时
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 mPanelAutoHideHandler、mPanelAutoHideRunnable
 * 和 resetPanelAutoHide()、cancelPanelAutoHide() 等方法抽离到这里，
 * 统一管理面板自动隐藏的计时逻辑。
 */
public class PanelAutoHideManager {

    private static final long DEFAULT_AUTO_HIDE_DELAY = 5000;
    private static final long FIRST_LAUNCH_DELAY = 3000;

    private static PanelAutoHideManager instance;

    private final Handler handler;
    private final Runnable hideRunnable;

    private ChannelPanelController panelController;
    private long autoHideDelay = DEFAULT_AUTO_HIDE_DELAY;

    private PanelAutoHideManager() {
        this.handler = new Handler(Looper.getMainLooper());
        this.hideRunnable = new Runnable() {
            @Override
            public void run() {
                if (panelController != null) {
                    panelController.hidePanel();
                }
                SettingsActivity.logOperation("【面板】自动隐藏（计时到期）");
            }
        };
    }

    public static synchronized PanelAutoHideManager getInstance() {
        if (instance == null) {
            instance = new PanelAutoHideManager();
        }
        return instance;
    }

    public void setPanelController(ChannelPanelController controller) {
        this.panelController = controller;
    }

    public void setAutoHideDelay(long delayMillis) {
        this.autoHideDelay = delayMillis;
    }

    public void reset() {
        if (handler != null && hideRunnable != null) {
            handler.removeCallbacks(hideRunnable);
            handler.postDelayed(hideRunnable, autoHideDelay);
        }
        SettingsActivity.logOperation("【面板】重置自动隐藏计时（"
                + (autoHideDelay / 1000) + "秒后隐藏）");
    }

    public void cancel() {
        if (handler != null && hideRunnable != null) {
            handler.removeCallbacks(hideRunnable);
        }
    }

    public void postFirstLaunch() {
        if (handler != null && hideRunnable != null) {
            handler.postDelayed(hideRunnable, FIRST_LAUNCH_DELAY);
        }
        SettingsActivity.logOperation("【面板】首次启动，"
                + (FIRST_LAUNCH_DELAY / 1000) + "秒后自动隐藏");
    }

    public void release() {
        if (handler != null) {
            handler.removeCallbacks(hideRunnable);
        }
        panelController = null;
    }
}
