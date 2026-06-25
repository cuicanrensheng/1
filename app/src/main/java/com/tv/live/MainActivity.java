package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    public static MainActivity mInstance;

    private UiInitializer uiInitializer;
    private BroadcastReceiver decoderModeReceiver;
    private boolean mIsFirstLaunch = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsActivity.logOperation("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");
        mInstance = this;

        uiInitializer = UiInitializer.getInstance();
        uiInitializer.setActivity(this);

        uiInitializer.initBaseConfig();
        setContentView(R.layout.activity_main);
        uiInitializer.getDisplayManager().applyFullScreen();
        SettingsActivity.logOperation("【主页】全面屏适配已应用");

        uiInitializer.initInfoDisplayManager();
        uiInitializer.initConfigManagers();
        uiInitializer.initFeatureManagers();

        loadSettings();
        loadCustomConfig();

        uiInitializer.initPlayerView();
        uiInitializer.initChannelPanel();
        uiInitializer.initRemoteManager();
        initPictureInPicture();
        initDecoderModeReceiver();

        if (mIsFirstLaunch) {
            uiInitializer.getPanelAutoHideManager().postFirstLaunch();
            mIsFirstLaunch = false;
        }

        uiInitializer.initPlayer();
        uiInitializer.initChannelPlayManager();
        uiInitializer.initChannelNumberManager();
        uiInitializer.initDirectionKeyHandler();
        uiInitializer.initScreenRatioManager();
        uiInitializer.initGestureManager();
        uiInitializer.initKeyEventManager();
        uiInitializer.initLifecycleManager();
        uiInitializer.initKeyDispatcher();
        uiInitializer.initBackPressHandler();
        uiInitializer.initAppCoreManager();

        uiInitializer.loadLastPlayIndex();
        uiInitializer.startLoading();
    }

    private void loadSettings() {
        SettingsManager settingsManager = uiInitializer.getSettingsManager();
        ChannelPanelController panelController = uiInitializer.getChannelPanelController();

        boolean epg_enable = settingsManager.isEpgEnabled();
        boolean channel_reverse = settingsManager.isChannelReverse();
        boolean auto_update_source = settingsManager.isAutoUpdateSource();

        if (panelController != null) {
            panelController.setEpgEnable(epg_enable);
            panelController.setReverse(channel_reverse);
        }

        SettingsActivity.logOperation("【设置】EPG开关：" + epg_enable);
        SettingsActivity.logOperation("【设置】切台反转：" + channel_reverse);
        SettingsActivity.logOperation("【设置】自动更新源：" + auto_update_source);
    }

    private void loadCustomConfig() {
        AppConfig appConfig = uiInitializer.getAppConfig();
        LogHelper logHelper = uiInitializer.getLogHelper();

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        logHelper.log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        logHelper.log("【配置】EPG地址：" + UrlConfig.EPG_URL);
    }

    private void initPictureInPicture() {
        uiInitializer.initPipManager();
    }

    private void initDecoderModeReceiver() {
        final SettingsManager settingsManager = uiInitializer.getSettingsManager();
        final TVPlayerManager playerManager = uiInitializer.getPlayerManager();

        decoderModeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                    int mode = settingsManager.getDecoderModeInt();
                    if (playerManager != null) {
                        playerManager.setDecoderMode(mode);
                    }
                    String modeName = SettingsManager.getDecoderModeName(mode);
                    SettingsActivity.logOperation("【解码器】收到广播，切换到：" + modeName);
                }
            }
        };
        IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
        registerReceiver(decoderModeReceiver, filter);
        SettingsActivity.logOperation("【解码器】广播接收器已注册");
    }

    public boolean isChannelReverse() {
        DirectionKeyHandler handler = uiInitializer.getDirectionKeyHandler();
        if (handler != null) {
            return handler.isChannelReverse();
        }
        return false;
    }

    public void togglePanel() {
        ChannelPanelController controller = uiInitializer.getChannelPanelController();
        TvRemoteManager remoteManager = uiInitializer.getRemoteManager();
        if (controller != null) {
            controller.togglePanel();
        }
        if (remoteManager != null && controller != null) {
            if (controller.isPanelOpen()) {
                remoteManager.setMode(TvRemoteManager.Mode.CHANNEL_PANEL_MODE);
                remoteManager.setRightPanelOpen(controller.isRightPanelOpen());
            } else {
                remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
            }
        }
    }

    public void playPrev() {
        ChannelPlayManager manager = uiInitializer.getChannelPlayManager();
        if (manager != null) {
            manager.playPrev();
        }
    }

    public void playNext() {
        ChannelPlayManager manager = uiInitializer.getChannelPlayManager();
        if (manager != null) {
            manager.playNext();
        }
    }

    @Override
    public void onBackPressed() {
        BackPressHandler handler = uiInitializer.getBackPressHandler();
        if (handler != null && handler.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        KeyDispatcher dispatcher = uiInitializer.getKeyDispatcher();
        if (dispatcher != null) {
            if (dispatcher.dispatchKeyEvent(keyCode, event)) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void openSettings() {
        LifecycleManager lifecycleManager = uiInitializer.getLifecycleManager();
        AppCoreManager appCoreManager = uiInitializer.getAppCoreManager();
        if (lifecycleManager != null) {
            lifecycleManager.setOpeningSettings(true);
        }
        if (appCoreManager != null) {
            appCoreManager.beforeOpenSettings();
        }
        startActivity(new Intent(this, SettingsActivity.class));
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppCoreManager appCoreManager = uiInitializer.getAppCoreManager();
        if (appCoreManager != null) {
            appCoreManager.onReceiveConfig(liveUrl, epgUrl);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        PictureInPictureManager pipManager = uiInitializer.getPipManager();
        SettingsManager settingsManager = uiInitializer.getSettingsManager();
        TVPlayerManager playerManager = uiInitializer.getPlayerManager();
        LifecycleManager lifecycleManager = uiInitializer.getLifecycleManager();
        if (pipManager != null) {
            pipManager.handleUserLeaveHint(this,
                    lifecycleManager != null && lifecycleManager.isOpeningSettings(),
                    settingsManager != null && settingsManager.isPipEnabled(),
                    playerManager);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        SettingsActivity.logOperation("【画中画】模式变化 → "
                + (isInPictureInPictureMode ? "进入" : "退出"));

        PictureInPictureManager pipManager = uiInitializer.getPipManager();
        if (pipManager != null) {
            try {
                pipManager.onPipModeChanged(this, isInPictureInPictureMode);
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画】模式变化回调失败：" + e.getMessage());
            }
        }

        if (isInPictureInPictureMode) {
            handleEnterPip();
        } else {
            handleExitPip();
        }
    }

    private void handleEnterPip() {
        PictureInPictureManager pipManager = uiInitializer.getPipManager();
        ChannelPanelController panelController = uiInitializer.getChannelPanelController();
        InfoDisplayManager infoDisplayManager = uiInitializer.getInfoDisplayManager();
        TVPlayerManager playerManager = uiInitializer.getPlayerManager();
        View playerView = uiInitializer.getPlayerView();
        if (pipManager != null) {
            pipManager.handleEnterPip(
                    this,
                    panelController,
                    infoDisplayManager,
                    playerManager,
                    playerView
            );
        }
    }

    private void handleExitPip() {
        PictureInPictureManager pipManager = uiInitializer.getPipManager();
        DisplayManager displayManager = uiInitializer.getDisplayManager();
        View playerView = uiInitializer.getPlayerView();
        TVPlayerManager playerManager = uiInitializer.getPlayerManager();
        TvRemoteManager remoteManager = uiInitializer.getRemoteManager();
        InfoDisplayManager infoDisplayManager = uiInitializer.getInfoDisplayManager();
        ChannelPlayManager channelPlayManager = uiInitializer.getChannelPlayManager();
        if (pipManager != null && channelPlayManager != null) {
            pipManager.handleExitPip(
                    this,
                    displayManager,
                    playerView,
                    playerManager,
                    remoteManager,
                    infoDisplayManager,
                    channelPlayManager.getChannelSourceList(),
                    channelPlayManager.getCurrentPlayIndex(),
                    new Runnable() {
                        @Override
                        public void run() {
                            SettingsActivity.logOperation("【画中画】应用已关闭，释放播放器");
                        }
                    }
            );
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LifecycleManager lifecycleManager = uiInitializer.getLifecycleManager();
        if (lifecycleManager != null) {
            lifecycleManager.onPause();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        LifecycleManager lifecycleManager = uiInitializer.getLifecycleManager();
        if (lifecycleManager != null) {
            lifecycleManager.onStop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LifecycleManager lifecycleManager = uiInitializer.getLifecycleManager();
        if (lifecycleManager != null) {
            lifecycleManager.onResume();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        LifecycleManager lifecycleManager = uiInitializer.getLifecycleManager();
        if (lifecycleManager != null) {
            lifecycleManager.onWindowFocusChanged(hasFocus);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LifecycleManager lifecycleManager = uiInitializer.getLifecycleManager();
        if (lifecycleManager != null) {
            lifecycleManager.onDestroy(decoderModeReceiver);
        }
        if (uiInitializer != null) {
            uiInitializer.release();
        }
        mInstance = null;
    }
}
