package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 主页面
 *
 * 【2026-06-25 优化：代码拆分 + 合并】
 * 【优化说明】
 * 1. 把 MainActivity 里的各个功能模块抽离到独立的 Manager 类
 * 2. 然后又把功能重复的小 Manager 合并到大的 Manager 里
 * 3. 最终 MainActivity 只负责生命周期和简单的转发逻辑
 *
 * 【合并后引用变化】
 * - UiInitializer → LifecycleManager（已合并）
 * - AutoSkipManager → ChannelPlayManager（已合并）
 * - PanelAutoHideManager → ChannelPanelController（已合并）
 * - DirectionKeyHandler → KeyDispatcher（已合并）
 * - BackPressHandler → KeyDispatcher（已合并）
 * - LogHelper → AppCoreManager（已合并）
 */
public class MainActivity extends AppCompatActivity {

    public static MainActivity mInstance;

    // ✅ 2026-06-25 合并：UiInitializer 已合并到 LifecycleManager
    private LifecycleManager lifecycleManager;

    private BroadcastReceiver decoderModeReceiver;
    private boolean mIsFirstLaunch = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsActivity.logOperation("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");

        mInstance = this;

        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        lifecycleManager = LifecycleManager.getInstance();
        lifecycleManager.setActivity(this);

        // 初始化基础配置
        lifecycleManager.initBaseConfig();
        setContentView(R.layout.activity_main);
        lifecycleManager.getDisplayManager().applyFullScreen();
        SettingsActivity.logOperation("【主页】全面屏适配已应用");

        // 初始化信息展示管理器
        lifecycleManager.initInfoDisplayManager();

        // 初始化配置管理器
        lifecycleManager.initConfigManagers();

        // 初始化功能管理器
        lifecycleManager.initFeatureManagers();

        // 加载设置
        loadSettings();

        // 加载自定义配置
        loadCustomConfig();

        // 初始化播放器视图
        lifecycleManager.initPlayerView();

        // 初始化频道面板
        lifecycleManager.initChannelPanel();

        // 初始化遥控器管理器
        lifecycleManager.initRemoteManager();

        // 初始化画中画
        initPictureInPicture();

        // 初始化解码器模式广播接收器
        initDecoderModeReceiver();

        // ✅ 2026-06-25 合并：PanelAutoHideManager → ChannelPanelController
        // 首次启动延迟隐藏面板
        if (mIsFirstLaunch) {
            lifecycleManager.getChannelPanelController().postFirstLaunchAutoHide();
            mIsFirstLaunch = false;
        }

        // 初始化播放器
        lifecycleManager.initPlayer();

        // 初始化频道播放管理器
        lifecycleManager.initChannelPlayManager();

        // 初始化数字选台管理器
        lifecycleManager.initChannelNumberManager();

        // ✅ 2026-06-25 合并：DirectionKeyHandler → KeyDispatcher
        // 初始化方向键处理器（已合并到 KeyDispatcher，这里直接初始化 KeyDispatcher）
        lifecycleManager.initDirectionKeyHandler();

        // 初始化屏幕比例管理器
        lifecycleManager.initScreenRatioManager();

        // 初始化手势管理器
        lifecycleManager.initGestureManager();

        // 初始化按键事件管理器
        lifecycleManager.initKeyEventManager();

        // ✅ 2026-06-25 合并：LifecycleManager 就是自己，这里调用 initLifecycleManager 只是记录日志
        lifecycleManager.initLifecycleManager();

        // 初始化按键分发器
        lifecycleManager.initKeyDispatcher();

        // ✅ 2026-06-25 合并：BackPressHandler → KeyDispatcher
        // 初始化返回键处理器（已合并到 KeyDispatcher，这里直接初始化 KeyDispatcher）
        lifecycleManager.initBackPressHandler();

        // 初始化应用核心管理器
        lifecycleManager.initAppCoreManager();

        // 加载上次播放索引
        lifecycleManager.loadLastPlayIndex();

        // 开始加载
        lifecycleManager.startLoading();
    }

    /**
     * 加载设置
     */
    private void loadSettings() {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        SettingsManager settingsManager = lifecycleManager.getSettingsManager();
        ChannelPanelController panelController = lifecycleManager.getChannelPanelController();

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

    /**
     * 加载自定义配置
     *
     * ✅ 2026-06-25 合并：LogHelper → AppCoreManager
     * 【修改说明】
     * 原来调用 logHelper.log()，现在改成 appCoreManager.log()
     */
    private void loadCustomConfig() {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        AppConfig appConfig = lifecycleManager.getAppConfig();

        // ✅ 2026-06-25 合并：LogHelper → AppCoreManager
        AppCoreManager appCoreManager = lifecycleManager.getAppCoreManager();

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();

        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        // ✅ 2026-06-25 合并：LogHelper → AppCoreManager
        appCoreManager.log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        appCoreManager.log("【配置】EPG地址：" + UrlConfig.EPG_URL);
    }

    /**
     * 初始化画中画
     */
    private void initPictureInPicture() {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        lifecycleManager.initPipManager();
    }

    /**
     * 初始化解码器模式广播接收器
     */
    private void initDecoderModeReceiver() {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        final SettingsManager settingsManager = lifecycleManager.getSettingsManager();
        final TVPlayerManager playerManager = lifecycleManager.getPlayerManager();

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

    /**
     * 是否开启切台反转
     *
     * ✅ 2026-06-25 合并：DirectionKeyHandler → KeyDispatcher
     * 【修改说明】
     * 原来调用 directionKeyHandler.isChannelReverse()，
     * 现在 DirectionKeyHandler 已经合并到 KeyDispatcher 里了，
     * 直接调用 keyDispatcher.isChannelReverse()。
     */
    public boolean isChannelReverse() {
        // ✅ 2026-06-25 合并：DirectionKeyHandler → KeyDispatcher
        KeyDispatcher dispatcher = lifecycleManager.getKeyDispatcher();
        if (dispatcher != null) {
            return dispatcher.isChannelReverse();
        }
        return false;
    }

    /**
     * 切换面板
     */
    public void togglePanel() {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        ChannelPanelController controller = lifecycleManager.getChannelPanelController();
        TvRemoteManager remoteManager = lifecycleManager.getRemoteManager();

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

    /**
     * 播放上一个频道
     */
    public void playPrev() {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        ChannelPlayManager manager = lifecycleManager.getChannelPlayManager();
        if (manager != null) {
            manager.playPrev();
        }
    }

    /**
     * 播放下一个频道
     */
    public void playNext() {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        ChannelPlayManager manager = lifecycleManager.getChannelPlayManager();
        if (manager != null) {
            manager.playNext();
        }
    }

    /**
     * 返回键处理
     *
     * ✅ 2026-06-25 合并：BackPressHandler → KeyDispatcher
     * 【修改说明】
     * 原来调用 backPressHandler.handleBackPressed()，
     * 现在 BackPressHandler 已经合并到 KeyDispatcher 里了，
     * 直接调用 keyDispatcher.handleBackPressed()。
     */
    @Override
    public void onBackPressed() {
        // ✅ 2026-06-25 合并：BackPressHandler → KeyDispatcher
        KeyDispatcher dispatcher = lifecycleManager.getKeyDispatcher();
        if (dispatcher != null && dispatcher.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    /**
     * 按键事件处理
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        KeyDispatcher dispatcher = lifecycleManager.getKeyDispatcher();
        if (dispatcher != null) {
            if (dispatcher.dispatchKeyEvent(keyCode, event)) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 打开设置页面
     *
     * ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
     * 【修改说明】
     * 原来通过 uiInitializer.getLifecycleManager() 获取，
     * 现在 LifecycleManager 就是 lifecycleManager 本身，直接用。
     */
    public void openSettings() {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        AppCoreManager appCoreManager = lifecycleManager.getAppCoreManager();

        // ✅ 2026-06-25 合并：LifecycleManager 就是自己，直接调用
        lifecycleManager.setOpeningSettings(true);

        if (appCoreManager != null) {
            appCoreManager.beforeOpenSettings();
        }

        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 接收远程配置
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        AppCoreManager appCoreManager = lifecycleManager.getAppCoreManager();
        if (appCoreManager != null) {
            appCoreManager.onReceiveConfig(liveUrl, epgUrl);
        }
    }

    /**
     * 用户按 Home 键
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        PictureInPictureManager pipManager = lifecycleManager.getPipManager();
        SettingsManager settingsManager = lifecycleManager.getSettingsManager();
        TVPlayerManager playerManager = lifecycleManager.getPlayerManager();

        if (pipManager != null) {
            pipManager.handleUserLeaveHint(this,
                    lifecycleManager.isOpeningSettings(),
                    settingsManager != null && settingsManager.isPipEnabled(),
                    playerManager);
        }
    }

    /**
     * 画中画模式变化
     */
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);

        SettingsActivity.logOperation("【画中画】模式变化 → "
                + (isInPictureInPictureMode ? "进入" : "退出"));

        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        PictureInPictureManager pipManager = lifecycleManager.getPipManager();

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

    /**
     * 进入画中画
     */
    private void handleEnterPip() {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        PictureInPictureManager pipManager = lifecycleManager.getPipManager();
        ChannelPanelController panelController = lifecycleManager.getChannelPanelController();
        InfoDisplayManager infoDisplayManager = lifecycleManager.getInfoDisplayManager();
        TVPlayerManager playerManager = lifecycleManager.getPlayerManager();
        View playerView = lifecycleManager.getPlayerView();

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

    /**
     * 退出画中画
     */
    private void handleExitPip() {
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        PictureInPictureManager pipManager = lifecycleManager.getPipManager();
        DisplayManager displayManager = lifecycleManager.getDisplayManager();
        View playerView = lifecycleManager.getPlayerView();
        TVPlayerManager playerManager = lifecycleManager.getPlayerManager();
        TvRemoteManager remoteManager = lifecycleManager.getRemoteManager();
        InfoDisplayManager infoDisplayManager = lifecycleManager.getInfoDisplayManager();
        ChannelPlayManager channelPlayManager = lifecycleManager.getChannelPlayManager();

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

    /**
     * 页面暂停
     */
    @Override
    protected void onPause() {
        super.onPause();
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        lifecycleManager.onPause();
    }

    /**
     * 页面停止
     */
    @Override
    protected void onStop() {
        super.onStop();
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        lifecycleManager.onStop();
    }

    /**
     * 页面恢复
     */
    @Override
    protected void onResume() {
        super.onResume();
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        lifecycleManager.onResume();
    }

    /**
     * 窗口焦点变化
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        lifecycleManager.onWindowFocusChanged(hasFocus);
    }

    /**
     * 页面销毁
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();

        // ✅ 2026-06-25 合并：UiInitializer → LifecycleManager
        lifecycleManager.onDestroy(decoderModeReceiver);
        lifecycleManager.release();

        mInstance = null;
    }
}
