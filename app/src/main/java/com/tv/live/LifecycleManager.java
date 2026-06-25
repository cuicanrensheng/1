package com.tv.live;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.tv.live.manager.*;

import java.util.List;

/**
 * 生命周期管理器
 * 作用：统一管理 MainActivity 的生命周期逻辑
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 onPause、onStop、onResume、onWindowFocusChanged、onDestroy
 * 等生命周期方法里的逻辑抽离到这里，统一管理生命周期相关的逻辑。
 *
 * 【职责】
 * - onPause：暂停相关逻辑（画中画保持播放等）
 * - onStop：停止相关逻辑
 * - onResume：恢复相关逻辑（重新加载设置、恢复播放等）
 * - onWindowFocusChanged：窗口焦点变化逻辑
 * - onDestroy：释放资源
 */
public class LifecycleManager {

    private static LifecycleManager instance;

    private Activity activity;

    private AppCoreManager appCoreManager;
    private PictureInPictureManager pipManager;
    private TVPlayerManager playerManager;
    private SettingsManager settingsManager;
    private ScreenRatioManager screenRatioManager;
    private DisplayManager displayManager;
    private TvRemoteManager remoteManager;
    private ChannelPanelController channelPanelController;
    private InfoDisplayManager infoDisplayManager;
    private ChannelNumberManager channelNumberManager;
    private ChannelPlayManager channelPlayManager;
    private PanelAutoHideManager panelAutoHideManager;
    private AutoSkipManager autoSkipManager;

    private boolean isOpeningSettings = false;

    private LifecycleManager() {
    }

    public static synchronized LifecycleManager getInstance() {
        if (instance == null) {
            instance = new LifecycleManager();
        }
        return instance;
    }

    public void setActivity(Activity activity) {
        this.activity = activity;
    }

    public void setAppCoreManager(AppCoreManager manager) {
        this.appCoreManager = manager;
    }

    public void setPipManager(PictureInPictureManager manager) {
        this.pipManager = manager;
    }

    public void setPlayerManager(TVPlayerManager manager) {
        this.playerManager = manager;
    }

    public void setSettingsManager(SettingsManager manager) {
        this.settingsManager = manager;
    }

    public void setScreenRatioManager(ScreenRatioManager manager) {
        this.screenRatioManager = manager;
    }

    public void setDisplayManager(DisplayManager manager) {
        this.displayManager = manager;
    }

    public void setRemoteManager(TvRemoteManager manager) {
        this.remoteManager = manager;
    }

    public void setChannelPanelController(ChannelPanelController controller) {
        this.channelPanelController = controller;
    }

    public void setInfoDisplayManager(InfoDisplayManager manager) {
        this.infoDisplayManager = manager;
    }

    public void setChannelNumberManager(ChannelNumberManager manager) {
        this.channelNumberManager = manager;
    }

    public void setChannelPlayManager(ChannelPlayManager manager) {
        this.channelPlayManager = manager;
    }

    public void setPanelAutoHideManager(PanelAutoHideManager manager) {
        this.panelAutoHideManager = manager;
    }

    public void setAutoSkipManager(AutoSkipManager manager) {
        this.autoSkipManager = manager;
    }

    public void setOpeningSettings(boolean opening) {
        this.isOpeningSettings = opening;
    }

    public boolean isOpeningSettings() {
        return isOpeningSettings;
    }

    public void onPause() {
        if (appCoreManager != null) {
            appCoreManager.onPause();
        }
        if (pipManager != null) {
            pipManager.handleOnPause(new Runnable() {
                @Override
                public void run() {
                    if (playerManager != null) {
                        playerManager.resume();
                        SettingsActivity.logOperation("【画中画】✅ onPause后立即恢复播放（防止暂停）");
                    }
                }
            });
        }
    }

    public void onStop() {
        if (pipManager != null) {
            pipManager.setStopCalled(true);
            SettingsActivity.logOperation("【画中画】onStop 被调用");
        }
    }

    public void onResume() {
        isOpeningSettings = false;
        if (appCoreManager != null) {
            appCoreManager.onResume();
        }
        if (pipManager != null) {
            pipManager.setStopCalled(false);
        }
        applySettings();
        if (screenRatioManager != null) {
            screenRatioManager.apply();
        }
        if (displayManager != null) {
            displayManager.reapplyFullScreen();
        }
        if (pipManager == null || !pipManager.isInPipMode()) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    resumeCurrentChannel();
                }
            }, 200);
        }
        syncRemoteMode();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus && displayManager != null) {
            displayManager.reapplyFullScreen();
        }
        if (appCoreManager != null) {
            appCoreManager.onWindowFocusChanged(hasFocus);
        }
    }

    public void onDestroy(BroadcastReceiver decoderModeReceiver) {
        if (panelAutoHideManager != null) {
            panelAutoHideManager.release();
        }
        if (channelPlayManager != null) {
            channelPlayManager.release();
        }
        if (infoDisplayManager != null) {
            infoDisplayManager.release();
        }
        if (channelNumberManager != null) {
            channelNumberManager.release();
        }
        if (displayManager != null) {
            displayManager.release();
        }
        if (channelPanelController != null) {
            channelPanelController.release();
        }
        if (appCoreManager != null) {
            appCoreManager.release();
        }
        if (pipManager != null) {
            pipManager.release();
        }
        if (autoSkipManager != null) {
            autoSkipManager.release();
        }
        if (decoderModeReceiver != null && activity != null) {
            try {
                activity.unregisterReceiver(decoderModeReceiver);
                decoderModeReceiver = null;
                SettingsActivity.logOperation("【解码器】广播接收器已注销");
            } catch (Exception e) {
            }
        }
        if (playerManager != null) {
            playerManager.release();
        }
        SettingsActivity.logOperation("【系统】APP退出");
    }

    private void applySettings() {
        if (settingsManager == null) return;

        boolean epg_enable = settingsManager.isEpgEnabled();
        boolean channel_reverse = settingsManager.isChannelReverse();
        boolean number_channel_enable = settingsManager.isNumberChannelEnabled();
        boolean auto_update_source = settingsManager.isAutoUpdateSource();
        boolean pipEnable = settingsManager.isPipEnabled();
        int decoderMode = settingsManager.getDecoderModeInt();

        if (playerManager != null) {
            playerManager.setDecoderMode(decoderMode);
        }

        String modeName = SettingsManager.getDecoderModeName(decoderMode);
        SettingsActivity.logOperation("【设置】解码器模式：" + modeName);

        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        }
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }
        if (pipManager != null) {
            pipManager.setPipEnabled(pipEnable);
        }

        SettingsActivity.logOperation("【设置】EPG开关：" + epg_enable);
        SettingsActivity.logOperation("【设置】切台反转：" + channel_reverse);
        SettingsActivity.logOperation("【设置】数字选台：" + number_channel_enable);
        SettingsActivity.logOperation("【设置】自动更新源：" + auto_update_source);
        SettingsActivity.logOperation("【设置】画中画开关：" + pipEnable);
    }

    private void syncRemoteMode() {
        if (remoteManager == null || channelPanelController == null) return;
        if (channelPanelController.isPanelOpen()) {
            remoteManager.setMode(TvRemoteManager.Mode.CHANNEL_PANEL_MODE);
            remoteManager.setRightPanelOpen(channelPanelController.isRightPanelOpen());
        } else {
            remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        }
    }

    private void resumeCurrentChannel() {
        try {
            if (playerManager != null) {
                playerManager.resume();
            }
        } catch (Exception e) {
            SettingsActivity.logOperation("【画中画】恢复播放失败：" + e.getMessage());
        }
    }

    public void release() {
        activity = null;
        appCoreManager = null;
        pipManager = null;
        playerManager = null;
        settingsManager = null;
        screenRatioManager = null;
        displayManager = null;
        remoteManager = null;
        channelPanelController = null;
        infoDisplayManager = null;
        channelNumberManager = null;
        channelPlayManager = null;
        panelAutoHideManager = null;
        autoSkipManager = null;
    }
}
