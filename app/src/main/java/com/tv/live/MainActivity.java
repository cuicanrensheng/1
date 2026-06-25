package com.tv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import androidx.media3.ui.PlayerView;
import androidx.appcompat.app.AppCompatActivity;

import com.tv.live.config.AppConfig;
import com.tv.live.manager.AppCoreManager;
import com.tv.live.manager.ChannelPanelController;
import com.tv.live.manager.DisplayManager;
import com.tv.live.manager.InfoDisplayManager;
import com.tv.live.manager.TvRemoteManager;

import java.util.List;

/**
 * 主页面
 *
 * 【2026-06-25 优化：代码拆分 + 合并】
 * 【优化说明】
 * 1. 把 MainActivity 里的各个功能模块抽离到独立的 Manager 类
 * 2. 然后又把功能重复的小 Manager 合并到大的 Manager 里
 * 3. 最终 MainActivity 只负责生命周期和简单的转发逻辑
 */
public class MainActivity extends AppCompatActivity {

    public static MainActivity mInstance;

    // ✅ 2026-06-25 兼容层：保留旧的 public 变量，供外部类访问
    // 【为什么保留？】
    // EpgManagerWrapper、ChannelListActivity 等外部类还在引用这些变量，
    // 为了不改动太多文件，保留这些变量名，实际值从 Manager 里获取。
    // 注意：这些变量会在初始化后同步，但建议外部类逐步改用 Manager 的方式访问。
    public List<Channel> channelSourceList;
    public int currentPlayIndex = 0;
    public TVPlayerManager mPlayerManager;

    private LifecycleManager lifecycleManager;
    private BroadcastReceiver decoderModeReceiver;
    private boolean mIsFirstLaunch = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsActivity.logOperation("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");

        mInstance = this;

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

        // 初始化方向键处理器（已合并到 KeyDispatcher）
        lifecycleManager.initDirectionKeyHandler();

        // 初始化屏幕比例管理器
        lifecycleManager.initScreenRatioManager();

        // 初始化手势管理器
        lifecycleManager.initGestureManager();

        // 初始化按键事件管理器
        lifecycleManager.initKeyEventManager();

        // 初始化生命周期管理器（自己，记录日志）
        lifecycleManager.initLifecycleManager();

        // 初始化按键分发器
        lifecycleManager.initKeyDispatcher();

        // 初始化返回键处理器（已合并到 KeyDispatcher）
        lifecycleManager.initBackPressHandler();

        // 初始化应用核心管理器
        lifecycleManager.initAppCoreManager();

        // 加载上次播放索引
        lifecycleManager.loadLastPlayIndex();

        // 开始加载
        lifecycleManager.startLoading();

        // ✅ 2026-06-25 兼容层：同步旧变量
        syncCompatVariables();
    }

    /**
     * ✅ 2026-06-25 兼容层：同步旧变量
     * 【为什么需要？】
     * EpgManagerWrapper、ChannelListActivity 等外部类还在引用
     * channelSourceList、currentPlayIndex、mPlayerManager 这些旧变量。
     * 为了保持向后兼容，这里同步一下这些变量的值。
     */
    private void syncCompatVariables() {
        if (lifecycleManager != null) {
            mPlayerManager = lifecycleManager.getPlayerManager();
            if (lifecycleManager.getChannelPlayManager() != null) {
                channelSourceList = lifecycleManager.getChannelPlayManager().getChannelSourceList();
                currentPlayIndex = lifecycleManager.getChannelPlayManager().getCurrentPlayIndex();
            }
        }
    }

    private void loadSettings() {
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

    private void loadCustomConfig() {
        AppConfig appConfig = lifecycleManager.getAppConfig();
        AppCoreManager appCoreManager = lifecycleManager.getAppCoreManager();

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();

        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;

        appCoreManager.log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        appCoreManager.log("【配置】EPG地址：" + UrlConfig.EPG_URL);
    }

    private void initPictureInPicture() {
        lifecycleManager.initPipManager();
    }

    private void initDecoderModeReceiver() {
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

    public boolean isChannelReverse() {
        KeyDispatcher dispatcher = lifecycleManager.getKeyDispatcher();
        if (dispatcher != null) {
            return dispatcher.isChannelReverse();
        }
        return false;
    }

    public void togglePanel() {
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

    public void playPrev() {
        ChannelPlayManager manager = lifecycleManager.getChannelPlayManager();
        if (manager != null) {
            manager.playPrev();
            // 同步兼容变量
            currentPlayIndex = manager.getCurrentPlayIndex();
        }
    }

    public void playNext() {
        ChannelPlayManager manager = lifecycleManager.getChannelPlayManager();
        if (manager != null) {
            manager.playNext();
            // 同步兼容变量
            currentPlayIndex = manager.getCurrentPlayIndex();
        }
    }

    /**
     * ✅ 2026-06-25 兼容层：playChannel 方法
     * 【为什么保留？】
     * ChannelListActivity 等外部类还在调用 MainActivity.mInstance.playChannel(position)，
     * 为了保持向后兼容，保留这个方法，内部转发给 ChannelPlayManager。
     */
    public void playChannel(int position) {
        ChannelPlayManager manager = lifecycleManager.getChannelPlayManager();
        if (manager != null) {
            manager.playChannel(position);
            // 同步兼容变量
            currentPlayIndex = manager.getCurrentPlayIndex();
        }
    }

    @Override
    public void onBackPressed() {
        KeyDispatcher dispatcher = lifecycleManager.getKeyDispatcher();
        if (dispatcher != null && dispatcher.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        KeyDispatcher dispatcher = lifecycleManager.getKeyDispatcher();
        if (dispatcher != null) {
            if (dispatcher.dispatchKeyEvent(keyCode, event)) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    public void openSettings() {
        AppCoreManager appCoreManager = lifecycleManager.getAppCoreManager();

        lifecycleManager.setOpeningSettings(true);

        if (appCoreManager != null) {
            appCoreManager.beforeOpenSettings();
        }

        startActivity(new Intent(this, SettingsActivity.class));
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        AppCoreManager appCoreManager = lifecycleManager.getAppCoreManager();
        if (appCoreManager != null) {
            appCoreManager.onReceiveConfig(liveUrl, epgUrl);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

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

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);

        SettingsActivity.logOperation("【画中画】模式变化 → "
                + (isInPictureInPictureMode ? "进入" : "退出"));

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

    private void handleEnterPip() {
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

    private void handleExitPip() {
        PictureInPictureManager pipManager = lifecycleManager.getPipManager();
        DisplayManager displayManager = lifecycleManager.getDisplayManager();
        PlayerView playerView = lifecycleManager.getPlayerView();
        TVPlayerManager playerManager = lifecycleManager.getPlayerManager();
        TvRemoteManager remoteManager = lifecycleManager.getRemoteManager();
        InfoDisplayManager infoDisplayManager = lifecycleManager.getInfoDisplayManager();
        ChannelPlayManager channelPlayManager = lifecycleManager.getChannelPlayManager();
        ChannelPanelController panelController = lifecycleManager.getChannelPanelController();

        if (pipManager != null && channelPlayManager != null) {
            pipManager.handleExitPip(
                    this,
                    displayManager,
                    playerView,
                    playerManager,
                    remoteManager,
                    infoDisplayManager,
                    panelController,
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

        // 同步兼容变量
        syncCompatVariables();
    }

    @Override
    protected void onPause() {
        super.onPause();
        lifecycleManager.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        lifecycleManager.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleManager.onResume();
        // 同步兼容变量
        syncCompatVariables();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        lifecycleManager.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        lifecycleManager.onDestroy(decoderModeReceiver);
        lifecycleManager.release();

        mInstance = null;
    }
}
