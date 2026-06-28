package com.tv.live;

import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.ui.PlayerView;

import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.*;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 主活动类：直播APP的核心页面，负责直播播放、频道管理、交互控制等核心功能
 * 包含播放器初始化、频道面板控制、遥控器适配、画中画、EPG节目指南等核心逻辑
 *
 * @author 开发者
 * @version 1.0
 */
public class MainActivity extends AppCompatActivity {

    // 单例实例：全局可访问当前MainActivity实例
    public static MainActivity mInstance;
    // 直播频道数据源列表
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前正在播放的频道索引
    public int currentPlayIndex = 0;

    // 播放器视图：用于渲染直播画面
    private PlayerView playerView;
    // 播放器管理类：封装播放核心逻辑
    public TVPlayerManager mPlayerManager;
    // 应用配置类：管理全局配置项
    private AppConfig appConfig;
    // 屏幕比例管理类：控制播放画面的比例适配
    private ScreenRatioManager screenRatioManager;
    // 手势管理类：处理触摸/手势交互
    private GestureManager gestureManager;
    // 播放器状态监听器：监听播放状态变化（播放、暂停、缓冲等）
    private PlayerStateListenerImpl playerStateListener;
    // 显示管理类：控制全屏、加载动画等显示相关逻辑
    private DisplayManager displayManager;
    // 信息显示管理类：控制频道号、节目信息、码率等UI展示
    private InfoDisplayManager infoDisplayManager;
    // 频道面板控制器：管理频道列表、分组、EPG面板的显示与交互
    private ChannelPanelController channelPanelController;
    // 应用核心管理类：统筹直播源、EPG加载等核心业务
    private AppCoreManager appCoreManager;
    // 遥控器管理类：处理电视遥控器按键事件
    private TvRemoteManager remoteManager;
    // 画中画管理类：处理画中画模式的进入/退出/状态同步
    private PictureInPictureManager pipManager;

    // 画中画功能开关
    private boolean pipEnable = false;
    // 频道切换方向反转开关
    private boolean channel_reverse;
    // 数字选台功能开关
    private boolean number_channel_enable;
    // 是否正在打开设置页面标记
    private boolean isOpeningSettings = false;

    // 全局日志列表：存储运行时日志
    public static List<String> logList = new ArrayList<>();

    /**
     * 活动创建生命周期方法：初始化页面、配置、播放器、交互组件等核心逻辑
     * @param savedInstanceState 保存的实例状态（如屏幕旋转、后台恢复）
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 记录操作日志
        SettingsActivity.logOperation("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");
        // 初始化单例
        mInstance = this;

        // 设置屏幕方向为横向传感器（根据设备方向自动横屏）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        // 初始化显示管理器
        displayManager = new DisplayManager(this);
        // 设置布局文件
        setContentView(R.layout.activity_main);
        // 应用全屏显示配置
        displayManager.applyFullScreen();
        SettingsActivity.logOperation("【主页】全面屏适配已应用");

        // 保持屏幕常亮（播放时不锁屏）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 初始化信息显示管理器（频道号、节目信息等）
        initInfoDisplayManager();
        // 获取应用配置单例
        appConfig = App.getInstance(this);
        // 加载本地保存的设置项
        loadSettings();

        // 加载自定义直播源/EPG地址（覆盖默认配置）
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        // 初始化播放器视图
        playerView = findViewById(R.id.player_view);
        // 禁用默认控制器（自定义UI）
        playerView.setUseController(false);
        // 清空控制器可见性监听器
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);

        // 初始化频道面板控制器
        initChannelPanelController();
        // 初始化遥控器管理器
        initRemoteManager();
        // 初始化画中画功能
        initPictureInPicture();
        // 处理首次启动逻辑（如默认选中第一个频道）
        channelPanelController.handleFirstLaunch();

        // 初始化播放器核心
        initPlayer();
        // 注册解码器模式广播接收器
        mPlayerManager.registerDecoderModeReceiver();

        // 初始化屏幕比例管理器并应用配置
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        // 初始化手势管理器并绑定触摸事件
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });

        // 恢复上次播放的频道索引
        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        SettingsActivity.logOperation("【播放】记录上次播放索引：" + currentPlayIndex);

        // 设置数字选台功能开关
        remoteManager.setNumberChannelEnable(number_channel_enable);

        // 初始化应用核心管理器
        initAppCoreManager();

        // 显示加载动画
        displayManager.showLoading("正在加载直播源...");
        // 加载直播源和EPG数据
        appCoreManager.loadLiveAndEpg();
    }

    /**
     * 初始化画中画（PIP）功能
     * 包含PIP管理器初始化、开关配置、状态监听器设置、后台返回交互恢复监听
     */
    private void initPictureInPicture() {
        try {
            // 获取PIP管理器单例
            pipManager = PictureInPictureManager.getInstance(this);
            // 设置PIP功能开关状态
            pipManager.setPipEnabled(pipEnable);
            // 启用调试日志
            pipManager.setDebugLogEnabled(true);

            // 绑定交互恢复监听器，后台小窗返回自动恢复手势、切台、横屏
            pipManager.setInteractionRestoreListener(new PictureInPictureManager.OnPipInteractionRestoreListener() {
                @Override
                public void onRestoreGesture() {
                    gestureManager.enable(true);
                    log("【画中画恢复】手势操作已启用");
                }

                @Override
                public void onRestoreChannelSwitch() {
                    log("【画中画恢复】频道切换功能已启用");
                }

                @Override
                public void onRestoreLandscapeUi() {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    displayManager.reapplyFullScreen();
                    log("【画中画恢复】横屏全屏UI已重置");
                }
            });

            // 设置PIP状态变化监听器
            pipManager.setListener(new PictureInPictureManager.OnPipListener() {
                @Override
                public void onPipModeChanged(boolean inPip) {
                    log("【画中画】监听器回调：" + (inPip ? "进入" : "退出"));
                    // 进入画中画禁用手势
                    if (inPip) {
                        gestureManager.enable(false);
                        log("【画中画】进入小窗，手势已禁用");
                    }
                }
            });

            log("【画中画】初始化完成，开关状态：" + (pipEnable ? "开启" : "关闭"));
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持：" + pipManager.isPipSupported());
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
    }

    /**
     * 初始化遥控器管理器
     */
    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        remoteManager.setChannelPanelController(channelPanelController);

        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            @Override
            public void onPlayChannelUp() {
                channelPanelController.switchUp();
            }

            @Override
            public void onPlayChannelDown() {
                channelPanelController.switchDown();
            }

            @Override
            public void onPlayTogglePanel() {
                togglePanel();
                remoteManager.syncMode();
            }

            @Override
            public void onPlayOpenSettings() {
                openSettings();
            }

            @Override
            public boolean onPlayBack() {
                return false;
            }

            @Override
            public void onPanelMoveUp() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
            }

            @Override
            public void onPanelMoveDown() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN);
            }

            @Override
            public void onPanelMoveLeft() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
            }

            @Override
            public void onPanelMoveRight() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
            }

            @Override
            public void onPanelConfirm() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER);
            }

            @Override
            public boolean onPanelBack() {
                boolean handled = channelPanelController.handleBackPressed();
                if (!channelPanelController.isPanelOpen()) {
                    remoteManager.syncMode();
                }
                return handled;
            }

            @Override
            public void onPanelMenu() {
                boolean isFavorite = channelPanelController.toggleCurrentFavorite();
                SettingsActivity.logOperation("【遥控】菜单键 → " + (isFavorite ? "已添加收藏" : "已取消收藏"));
            }

            @Override
            public void onPanelNumber(int number) {}

            @Override
            public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {
                SettingsActivity.logOperation("【遥控】面板焦点切换：" + newFocus);
            }

            @Override public void onSettingsMoveUp() {}
            @Override public void onSettingsMoveDown() {}
            @Override public void onSettingsConfirm() {}
            @Override public boolean onSettingsBack() { return false; }
            @Override public void onSettingsMenu() {}
            @Override public void onSettingsFocusChanged(int position) {}

            @Override
            public boolean onPipBack() {
                moveTaskToBack(false);
                return true;
            }

            @Override
            public void onRequestPlayFocus() {
                playerView.requestFocus();
            }

            @Override
            public void onChannelNumberSelected(int channelIndex) {
                channelPanelController.playChannel(channelIndex);
            }

            @Override
            public void onShowChannelNumber(String number) {
                try {
                    infoDisplayManager.showChannelNum(Integer.parseInt(number));
                } catch (Exception e) {}
            }

            @Override
            public void onHideChannelNumber() {
                infoDisplayManager.hideChannelNum();
            }
        });
    }

    /**
     * 初始化信息显示管理器
     */
    private void initInfoDisplayManager() {
        TextView tv_channel_num = findViewById(R.id.tv_channel_num);
        View info_bar = findViewById(R.id.info_bar);
        TextView tv_channel_name = findViewById(R.id.tv_channel_name);
        TextView tv_tag_fhd = findViewById(R.id.tv_tag_fhd);
        TextView tv_tag_audio = findViewById(R.id.tv_tag_audio);
        TextView tv_bitrate = findViewById(R.id.tv_bitrate);
        TextView tv_current_program_name = findViewById(R.id.tv_current_program_name);
        TextView tv_current_time_range = findViewById(R.id.tv_current_time_range);
        ProgressBar progress_program = findViewById(R.id.progress_program);
        TextView tv_remaining_time = findViewById(R.id.tv_remaining_time);
        TextView tv_next_program_name = findViewById(R.id.tv_next_program_name);
        TextView tv_next_time_range = findViewById(R.id.tv_next_time_range);

        infoDisplayManager = new InfoDisplayManager(
                this,
                tv_channel_num,
                info_bar,
                tv_channel_name,
                tv_tag_fhd,
                tv_tag_audio,
                tv_bitrate,
                tv_current_program_name,
                tv_current_time_range,
                progress_program,
                tv_remaining_time,
                tv_next_program_name,
                tv_next_time_range
        );
    }

    /**
     * 初始化频道面板控制器
     */
    private void initChannelPanelController() {
        View panel_layout = findViewById(R.id.panel_layout);
        View ll_left_panel = findViewById(R.ll_left_panel);
        View ll_right_panel = findViewById(R.ll_right_panel);
        ListView lvGroup = findViewById(R.lv_group);
        ListView lvChannelList = findViewById(R.lv_channel_list);
        ListView lvChannelListEpg = findViewById(R.lv_channel_list_epg);
        ListView lvDate = findViewById(R.lv_date);
        ListView lvEpg = findViewById(R.lv_epg);
        TextView btn_show_epg = findViewById(R.btn_show_epg);
        TextView btn_back_group = findViewById(R.btn_back_group);

        EpgManager.getInstance(this);
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
        ChannelListManager channelListManagerEpg = new ChannelListManager(this, lvChannelListEpg);
        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        DateListManager dateListManager = new DateListManager(this, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            channelPanelController.setCurrentDateIndex(pos);
        });

        channelPanelController = new ChannelPanelController(
                this,
                panel_layout,
                ll_left_panel,
                ll_right_panel,
                lvGroup,
                lvChannelList,
                lvChannelListEpg,
                lvDate,
                lvEpg,
                btn_show_epg,
                btn_back_group,
                groupListManager,
                channelListManager,
                channelListManagerEpg,
                dateListManager,
                epgManagerWrapper,
                panelManager
        );

        channelPanelController.setOnChannelChangeListener(new ChannelPanelController.OnChannelChangeListener() {
            @Override
            public void onChannelChanged(Channel channel, int index) {
                playChannel(channel, index);
            }
        });
    }

    /**
     * 初始化播放器核心
     */
    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);

        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                infoDisplayManager.updateLiveInfo(info);
                if (pipManager != null) {
                    pipManager.updatePlayState(true);
                }
            }
        });

        mPlayerManager.setOnSourceFailedListener(new TVPlayerManager.OnSourceFailedListener() {
            @Override
            public void onSourceFailed() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String channelName = "";
                        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                            Channel ch = channelSourceList.get(currentPlayIndex);
                            if (ch != null) {
                                channelName = ch.getName();
                            }
                        }
                        appCoreManager.handleSourceFailed(channelName);
                    }
                });
            }
        });
    }

    /**
     * 初始化应用核心管理器
     */
    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);

        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveSourceLoaded(List<Channel> channels, boolean fromCache) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        channelSourceList.clear();
                        channelSourceList.addAll(channels);
                        channelPanelController.setChannels(channels);

                        if (remoteManager != null) {
                            remoteManager.setTotalChannelCount(channels.size());
                        }

                        if (!appCoreManager.hasPlayedWithCache()) {
                            if (currentPlayIndex >= 0 && currentPlayIndex < channels.size()) {
                                Channel ch = channels.get(currentPlayIndex);
                                playChannel(ch, currentPlayIndex);
                                appCoreManager.setHasPlayedWithCache(true);
                            }
                        }
                        displayManager.hideLoading();
                        log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channels.size());
                    }
                });
            }

            @Override
            public void onLiveSourceFailed(String errorMsg) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (channelSourceList.isEmpty()) {
                            displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                            SettingsActivity.logOperation("【加载】直播源加载失败：" + errorMsg);
                        } else {
                            log("【缓存】使用缓存数据继续播放");
                            displayManager.hideLoading();
                        }
                    }
                });
            }

            @Override
            public void onEpgLoaded() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                            Channel curr = channelSourceList.get(currentPlayIndex);
                            infoDisplayManager.updateEpgInfo(curr);
                        }
                    }
                });
            }

            @Override
            public void onLoadTimeout(boolean hasData) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        log("【加载】超时，自动隐藏加载动画");
                        if (!hasData) {
                            displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                            SettingsActivity.logOperation("【加载】直播源加载超时");
                        }
                        displayManager.hideLoading();
                    }
                });
            }
        });

        appCoreManager.setOnSourceSkipListener(new AppCoreManager.OnSourceSkipListener() {
            @Override
            public void onNeedSkipChannel() {
                channelPanelController.switchDown();
            }

            @Override
            public void onSkipLimitReached(int maxSkip) {
                Toast.makeText(MainActivity.this, "已跳过 " + max + " 个失效频道，请检查直播源", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSourceFailed(String channelName, int failedCount) {}
        });

        appCoreManager.registerReceivers();
    }

    /**
     * 加载本地保存的设置项
     */
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);
        pipEnable = sp.getBoolean("pip_enable", false);

        String decoderMode = sp.getString("decoder_mode", "auto");
        int mode = TVPlayerManager.DECODER_MODE_AUTO;
        if ("hard".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_HARD;
        } else if ("soft".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_SOFT;
        }
        if (mPlayerManager != null) {
            mPlayerManager.setDecoderMode(mode);
        }

        String modeName;
        switch (mode) {
            case TVPlayerManager.DECODER_MODE_HARD:
                modeName = "硬解";
                break;
            case TVPlayerManager.DECODER_MODE_SOFT:
                modeName = "软解（兼容性好）";
                break;
            default:
                modeName = "自动（推荐）";
                break;
        }
        SettingsActivity.logOperation("【设置】解码器模式：" + modeName);

        if (remoteManager != null) {
            remoteManager.setNumberChannelEnable(number_channel_enable);
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

    public boolean isChannelReverse() {
        return channel_reverse;
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;

        log("========================================");
        log("【播放】频道名称：" + channel.getName());
        log("【播放】播放地址：" + channel.getPlayUrl());
        log("【播放】当前索引：" + index);
        log("========================================");

        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        mPlayerManager.playUrl(channel.getPlayUrl());

        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
        infoDisplayManager.showChannelNum(index + 1);

        try {
            appConfig.addRecentChannel(channel.getName());
        } catch (Exception e) {}

        appCoreManager.resetSourceFailedCount();

        if (pipManager != null && pipManager.isInPipMode() && channel != null) {
            try {
                pipManager.updateChannelInfo(index + 1,
                        channel.getName() != null ? channel.getName() : "",
                        live != null ? live.bitrate : "");
            } catch (Exception e) {
                log("【画中画】同步频道信息失败：" + e.getMessage());
            }
        }
    }

    public void togglePanel() {
        channelPanelController.togglePanel();
        remoteManager.syncMode();
    }

    public void playPrev() {
        channelPanelController.playPrev();
    }

    public void playNext() {
        channelPanelController.playNext();
    }

    @Override
    public void onBackPressed() {
        if (remoteManager != null && remoteManager.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    public void openSettings() {
        isOpeningSettings = true;
        appCoreManager.beforeOpenSettings();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isOpeningSettings) {
            return;
        }
        if (pipManager != null) {
            pipManager.enterPip(this, mPlayerManager, pipEnable);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        SettingsActivity.logOperation("【画中画】模式变化 → " + (isInPictureInPictureMode ? "进入" : "退出"));

        if (remoteManager != null) {
            remoteManager.setInPipMode(isInPictureInPictureMode);
        }

        if (pipManager != null) {
            try {
                pipManager.onPipModeChanged(this, isInPictureInPictureMode);
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画】模式变化回调失败：" + e.getMessage());
            }
        }

        if (pipManager != null) {
            if (isInPictureInPictureMode) {
                pipManager.handleEnterPip(this, channelPanelController, infoDisplayManager, mPlayerManager, playerView);
            } else {
                // 改为双参数，传入this，退出小窗自动恢复手势切台
                pipManager.handleExitPip(this, new Runnable() {
                    @Override
                    public void run() {
                        SettingsActivity.logOperation("【画中画】应用已关闭，释放播放器");
                    }
                });
                pipManager.handleExitPipRestore(this, displayManager, playerView, mPlayerManager, channelSourceList, currentPlayIndex, infoDisplayManager);
                remoteManager.syncMode();
            }
        }
    }

    private void log(String msg) {
        logList.add(msg);
        Log.d("MainActivity", msg);
    }

    @Override
    protected void onPause() {
        super.onPause();
        appCoreManager.onPause();
        if (pipManager != null) {
            pipManager.handleOnPause(new Runnable() {
                @Override
                public void run() {
                    if (mPlayerManager != null) {
                        mPlayerManager.resume();
                        SettingsActivity.logOperation("【画中画】✅ onPause后立即恢复播放（防止暂停）");
                    }
                }
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (pipManager != null) {
            pipManager.setStopCalled(true);
            SettingsActivity.logOperation("【画中画】onStop 被调用");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isOpeningSettings = false;
        appCoreManager.onResume();
        if (pipManager != null) {
            pipManager.setStopCalled(false);
        }
        loadSettings();
        screenRatioManager.apply();
        displayManager.reapplyFullScreen();

        if (pipManager == null || !pipManager.isInPipMode()) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (pipManager != null) {
                        pipManager.resumePlayback(mPlayerManager);
                    }
                }
            }, 200);
        }
        remoteManager.syncMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            displayManager.reapplyFullScreen();
        }
        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (infoDisplayManager != null) infoDisplayManager.release();
        if (remoteManager != null) remoteManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();
        if (pipManager != null) pipManager.release();
        if (mPlayerManager != null) mPlayerManager.release();

        mInstance = null;
        SettingsActivity.logOperation("【系统】APP退出");
    }
}
