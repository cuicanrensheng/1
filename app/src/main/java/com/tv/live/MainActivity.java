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
        appConfig = AppConfig.getInstance(this);
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
        // 注册解码器模式广播接收器（监听解码器切换）
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
                // 处理播放器区域的触摸手势（如滑动调音量、切换频道）
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

        // 显示加载动画，提示加载直播源
        displayManager.showLoading("正在加载直播源...");
        // 加载直播源和EPG数据
        appCoreManager.loadLiveAndEpg();
    }

    /**
     * 初始化画中画（PIP）功能
     * 包含PIP管理器初始化、开关配置、状态监听器设置
     */
    private void initPictureInPicture() {
        try {
            // 获取PIP管理器单例
            pipManager = PictureInPictureManager.getInstance(this);
            // 设置PIP功能开关状态
            pipManager.setPipEnabled(pipEnable);
            // 启用调试日志
            pipManager.setDebugLogEnabled(true);
            // 设置PIP状态变化监听器
            pipManager.setListener(new PictureInPictureManager.OnPipListener() {
                @Override
                public void onPipModeChanged(boolean inPip) {
                    log("【画中画】监听器回调：" + (inPip ? "进入" : "退出"));
                }
            });
            log("【画中画】初始化完成，开关状态：" + (pipEnable ? "开启" : "关闭"));
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持：" + pipManager.isPipSupported());
        } catch (Exception e) {
            // 捕获初始化异常，记录日志
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
    }

    /**
     * 初始化遥控器管理器
     * 配置遥控器按键事件回调（切换频道、打开面板、设置、数字选台等）
     */
    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        // 设置遥控器默认模式为播放模式
        remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        // 绑定频道面板控制器（用于按键触发频道切换）
        remoteManager.setChannelPanelController(channelPanelController);

        // 设置遥控器按键事件监听器
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            /** 播放模式-上切频道 */
            @Override
            public void onPlayChannelUp() {
                channelPanelController.switchUp();
            }

            /** 播放模式-下切频道 */
            @Override
            public void onPlayChannelDown() {
                channelPanelController.switchDown();
            }

            /** 播放模式-切换频道面板显示/隐藏 */
            @Override
            public void onPlayTogglePanel() {
                togglePanel();
                remoteManager.syncMode();
            }

            /** 播放模式-打开设置页面 */
            @Override
            public void onPlayOpenSettings() {
                openSettings();
            }

            /** 播放模式-返回键（默认不处理） */
            @Override
            public boolean onPlayBack() {
                return false;
            }

            /** 面板模式-向上移动焦点 */
            @Override
            public void onPanelMoveUp() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
            }

            /** 面板模式-向下移动焦点 */
            @Override
            public void onPanelMoveDown() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN);
            }

            /** 面板模式-向左移动焦点 */
            @Override
            public void onPanelMoveLeft() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
            }

            /** 面板模式-向右移动焦点 */
            @Override
            public void onPanelMoveRight() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
            }

            /** 面板模式-确认选择（选中频道/EPG） */
            @Override
            public void onPanelConfirm() {
                channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER);
            }

            /** 面板模式-返回键（关闭面板/返回上一级） */
            @Override
            public boolean onPanelBack() {
                boolean handled = channelPanelController.handleBackPressed();
                if (!channelPanelController.isPanelOpen()) {
                    remoteManager.syncMode();
                }
                return handled;
            }

            /** 面板模式-菜单键（收藏/取消收藏当前频道） */
            @Override
            public void onPanelMenu() {
                boolean isFavorite = channelPanelController.toggleCurrentFavorite();
                SettingsActivity.logOperation("【遥控】菜单键 → "
                        + (isFavorite ? "已添加收藏" : "已取消收藏"));
            }

            /** 面板模式-数字按键（预留，未实现） */
            @Override
            public void onPanelNumber(int number) {
            }

            /** 面板模式-焦点切换回调 */
            @Override
            public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {
                SettingsActivity.logOperation("【遥控】面板焦点切换：" + newFocus);
            }

            /** 设置页面-向上移动焦点（预留） */
            @Override public void onSettingsMoveUp() {}
            /** 设置页面-向下移动焦点（预留） */
            @Override public void onSettingsMoveDown() {}
            /** 设置页面-确认选择（预留） */
            @Override public void onSettingsConfirm() {}
            /** 设置页面-返回键（预留） */
            @Override public boolean onSettingsBack() { return false; }
            /** 设置页面-菜单键（预留） */
            @Override public void onSettingsMenu() {}
            /** 设置页面-焦点切换（预留） */
            @Override public void onSettingsFocusChanged(int position) {}

            /** 画中画模式-返回键（将应用移至后台） */
            @Override
            public boolean onPipBack() {
                moveTaskToBack(false);
                return true;
            }

            /** 请求播放器焦点（确保按键事件被播放器接收） */
            @Override
            public void onRequestPlayFocus() {
                playerView.requestFocus();
            }

            /** 数字选台-选中频道索引 */
            @Override
            public void onChannelNumberSelected(int channelIndex) {
                channelPanelController.playChannel(channelIndex);
            }

            /** 显示数字选台的数字提示 */
            @Override
            public void onShowChannelNumber(String number) {
                try {
                    infoDisplayManager.showChannelNum(Integer.parseInt(number));
                } catch (Exception e) {
                }
            }

            /** 隐藏数字选台的数字提示 */
            @Override
            public void onHideChannelNumber() {
                infoDisplayManager.hideChannelNum();
            }
        });
    }

    /**
     * 初始化信息显示管理器
     * 绑定UI控件，用于展示频道号、节目名称、码率、EPG等信息
     */
    private void initInfoDisplayManager() {
        // 绑定布局中的UI控件
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

        // 初始化信息显示管理器，传入所有需要控制的UI控件
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
     * 绑定面板UI控件，初始化频道列表、分组、EPG、日期等子管理器
     */
    private void initChannelPanelController() {
        // 绑定面板相关UI控件
        View panel_layout = findViewById(R.id.panel_layout);
        View ll_left_panel = findViewById(R.id.ll_left_panel);
        View ll_right_panel = findViewById(R.id.ll_right_panel);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvChannelListEpg = findViewById(R.id.lv_channel_list_epg);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btn_show_epg = findViewById(R.id.btn_show_epg);
        TextView btn_back_group = findViewById(R.id.btn_back_group);

        // 初始化EPG管理器
        EpgManager.getInstance(this);
        // 初始化频道列表管理器（普通频道列表）
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
        // 初始化频道列表管理器（EPG侧边的频道列表）
        ChannelListManager channelListManagerEpg = new ChannelListManager(this, lvChannelListEpg);
        // 初始化分组列表管理器（频道分组）
        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        // 初始化日期列表管理器（EPG日期选择）
        DateListManager dateListManager = new DateListManager(this, lvDate);
        // 初始化EPG管理器包装类（处理EPG数据展示）
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        // 初始化面板管理器（控制面板显示/隐藏）
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        // 初始化日期列表（加载今日、明日等日期）
        dateListManager.initDate();
        // 设置日期选中监听器（切换EPG日期）
        dateListManager.setOnDateSelectedListener(pos -> {
            channelPanelController.setCurrentDateIndex(pos);
        });

        // 初始化频道面板控制器，整合所有子管理器
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

        // 设置频道切换监听器（选中频道后触发播放）
        channelPanelController.setOnChannelChangeListener(new ChannelPanelController.OnChannelChangeListener() {
            @Override
            public void onChannelChanged(Channel channel, int index) {
                playChannel(channel, index);
            }
        });
    }
            infoDisplayManager.setEpgManagerWrapper(epgManagerWrapper);
    }

    /**
     * 初始化播放器核心
     * 绑定播放器视图、设置状态监听器、直播信息更新监听器等
     */
    private void initPlayer() {
        // 获取播放器管理器单例
        mPlayerManager = TVPlayerManager.getInstance(this);
        // 绑定播放器视图（渲染画面）
        mPlayerManager.attachPlayerView(playerView);

        // 初始化播放器状态监听器
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);

        // 设置直播信息更新监听器（码率、分辨率等变化时更新UI）
        mPlayerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                infoDisplayManager.updateLiveInfo(info);
                // 同步画中画播放状态
                if (pipManager != null) {
                    pipManager.updatePlayState(true);
                }
            }
        });

        // 设置直播源播放失败监听器（切换下一个频道）
        mPlayerManager.setOnSourceFailedListener(new TVPlayerManager.OnSourceFailedListener() {
            @Override
            public void onSourceFailed() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String channelName = "";
                        // 获取当前失败的频道名称
                        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                            Channel ch = channelSourceList.get(currentPlayIndex);
                            if (ch != null) {
                                channelName = ch.getName();
                            }
                        }
                        // 处理直播源失败逻辑（跳过失效频道）
                        appCoreManager.handleSourceFailed(channelName);
                    }
                });
            }
        });
    }

    /**
     * 初始化应用核心管理器
     * 统筹直播源加载、EPG加载、失败重试等核心业务逻辑
     */
    private void initAppCoreManager() {
        // 初始化应用核心管理器
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);

        // 设置数据加载监听器（直播源、EPG加载回调）
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            /**
             * 直播源加载完成回调
             * @param channels 加载的频道列表
             * @param fromCache 是否从缓存加载
             */
            @Override
            public void onLiveSourceLoaded(List<Channel> channels, boolean fromCache) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 更新频道数据源
                        channelSourceList.clear();
                        channelSourceList.addAll(channels);
                        channelPanelController.setChannels(channels);

                        // 更新遥控器的总频道数（数字选台用）
                        if (remoteManager != null) {
                            remoteManager.setTotalChannelCount(channels.size());
                        }

                        // 如果未通过缓存播放过，则恢复上次播放的频道
                        if (!appCoreManager.hasPlayedWithCache()) {
                            if (currentPlayIndex >= 0 && currentPlayIndex < channels.size()) {
                                Channel ch = channels.get(currentPlayIndex);
                                playChannel(ch, currentPlayIndex);
                                appCoreManager.setHasPlayedWithCache(true);
                            }
                        }
                        // 隐藏加载动画
                        displayManager.hideLoading();
                        log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channels.size());
                    }
                });
            }

            /**
             * 直播源加载失败回调
             * @param errorMsg 失败原因
             */
            @Override
            public void onLiveSourceFailed(String errorMsg) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // 如果无缓存数据，显示加载失败提示
                        if (channelSourceList.isEmpty()) {
                            displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                            SettingsActivity.logOperation("【加载】直播源加载失败：" + errorMsg);
                        } else {
                            // 有缓存数据则继续使用缓存
                            log("【缓存】使用缓存数据继续播放");
                            displayManager.hideLoading();
                        }
                    }
                });
            }

            /**
             * EPG数据加载完成回调
             * 更新当前频道的EPG节目信息
             */
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

            /**
             * 加载超时回调
             * @param hasData 是否已有缓存数据
             */
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

        // 设置直播源跳过监听器（失效频道自动跳过）
        appCoreManager.setOnSourceSkipListener(new AppCoreManager.OnSourceSkipListener() {
            /** 需要跳过当前失效频道（切换下一个） */
            @Override
            public void onNeedSkipChannel() {
                channelPanelController.switchDown();
            }

            /** 跳过失效频道达到上限，提示用户 */
            @Override
            public void onSkipLimitReached(int maxSkip) {
                Toast.makeText(MainActivity.this, "已跳过 " + maxSkip
                        + " 个失效频道，请检查直播源", Toast.LENGTH_SHORT).show();
            }

            /** 直播源播放失败（记录失败次数） */
            @Override
            public void onSourceFailed(String channelName, int failedCount) {
            }
        });

        // 注册广播接收器（监听系统/应用内广播）
        appCoreManager.registerReceivers();
    }

    /**
     * 加载本地保存的设置项
     * 从SharedPreferences读取解码器模式、EPG开关、画中画开关等配置
     */
    private void loadSettings() {
        // 获取设置共享偏好
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);          // EPG开关（默认开启）
        channel_reverse = sp.getBoolean("channel_reverse", false);       // 频道切换反转（默认关闭）
        number_channel_enable = sp.getBoolean("number_channel_enable", true); // 数字选台（默认开启）
        boolean auto_update_source = sp.getBoolean("auto_update_source", true); // 自动更新源（默认开启）
        pipEnable = sp.getBoolean("pip_enable", false);                 // 画中画开关（默认关闭）

        // 解码器模式（auto/hard/soft）
        String decoderMode = sp.getString("decoder_mode", "auto");
        int mode = TVPlayerManager.DECODER_MODE_AUTO;
        if ("hard".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_HARD; // 硬解
        } else if ("soft".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_SOFT; // 软解
        }
        // 设置解码器模式
        if (mPlayerManager != null) {
            mPlayerManager.setDecoderMode(mode);
        }

        // 记录解码器模式日志
        String modeName;
        switch (mode) {
            case TVPlayerManager.DECODER_MODE_HARD:
                modeName = "硬解";
                break;
            case TVPlayerManager.DECODER_MODE_SOFT:
                modeName = "软解（兼容性好）";
                break;
            case TVPlayerManager.DECODER_MODE_AUTO:
            default:
                modeName = "自动（推荐）";
                break;
        }
        SettingsActivity.logOperation("【设置】解码器模式：" + modeName);

        // 更新遥控器数字选台开关
        if (remoteManager != null) {
            remoteManager.setNumberChannelEnable(number_channel_enable);
        }

        // 更新频道面板EPG开关和切换反转
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }

        // 更新画中画开关
        if (pipManager != null) {
            pipManager.setPipEnabled(pipEnable);
        }

        // 记录设置项日志
        SettingsActivity.logOperation("【设置】EPG开关：" + epg_enable);
        SettingsActivity.logOperation("【设置】切台反转：" + channel_reverse);
        SettingsActivity.logOperation("【设置】数字选台：" + number_channel_enable);
        SettingsActivity.logOperation("【设置】自动更新源：" + auto_update_source);
        SettingsActivity.logOperation("【设置】画中画开关：" + pipEnable);
    }

    /**
     * 获取频道切换反转状态
     * @return true=反转，false=正常
     */
    public boolean isChannelReverse() {
        return channel_reverse;
    }

    /**
     * 根据索引播放指定频道
     * @param index 频道索引
     */
    public void playChannel(int index) {
        // 校验数据源和索引合法性
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    /**
     * 播放指定频道（核心播放逻辑）
     * @param channel 要播放的频道对象
     * @param index 频道索引
     */
    private void playChannel(Channel channel, int index) {
        // 校验频道和播放地址合法性
        if (channel == null || channel.getPlayUrl() == null) return;
        // 更新当前播放索引
        currentPlayIndex = index;

        // 记录播放日志
        log("========================================");
        log("【播放】频道名称：" + channel.getName());
        log("【播放】播放地址：" + channel.getPlayUrl());
        log("【播放】当前索引：" + index);
        log("========================================");

        // 设置当前播放的频道名称（用于状态监听）
        playerStateListener.setCurrentChannelName(channel.getName());
        // 保存最后播放的索引
        appConfig.setLastPlayIndex(index);
        // 调用播放器播放指定URL
        mPlayerManager.playUrl(channel.getPlayUrl());

        // 更新UI显示（频道信息、码率、EPG等）
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
        infoDisplayManager.showChannelNum(index + 1); // 频道号从1开始

        // 添加到最近播放列表
        try {
            appConfig.addRecentChannel(channel.getName());
        } catch (Exception e) {
        }

        // 重置直播源失败计数（切换频道后重置重试次数）
        appCoreManager.resetSourceFailedCount();

        // 同步画中画频道信息（如果当前在画中画模式）
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

    /**
     * 切换频道面板的显示/隐藏状态
     */
    public void togglePanel() {
        channelPanelController.togglePanel();
        remoteManager.syncMode(); // 同步遥控器模式（播放/面板）
    }

    /**
     * 播放上一个频道（根据反转配置调整方向）
     */
    public void playPrev() {
        channelPanelController.playPrev();
    }

    /**
     * 播放下一个频道（根据反转配置调整方向）
     */
    public void playNext() {
        channelPanelController.playNext();
    }

    /**
     * 返回键事件处理
     * 优先交给遥控器管理器处理，未处理则执行默认逻辑
     */
    @Override
    public void onBackPressed() {
        if (remoteManager != null && remoteManager.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    /**
     * 按键事件处理
     * 优先交给遥控器管理器处理，未处理则执行默认逻辑
     * @param keyCode 按键码
     * @param event 按键事件
     * @return true=已处理，false=未处理
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * 打开设置页面
     * 标记设置页面状态，执行前置操作后跳转
     */
    public void openSettings() {
        isOpeningSettings = true;
        appCoreManager.beforeOpenSettings(); // 打开设置前的预处理（如保存状态）
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /**
     * 接收配置更新（从设置页面返回后更新直播源/EPG地址）
     * @param liveUrl 新的直播源地址
     * @param epgUrl 新的EPG地址
     */
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    /**
     * 应用退到后台时的生命周期方法
     * 非设置页面场景下，尝试进入画中画模式
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isOpeningSettings) {
            return; // 打开设置页面时不进入画中画
        }
        if (pipManager != null) {
            pipManager.enterPip(this, mPlayerManager, pipEnable);
        }
    }

    /**
     * 画中画模式变化回调
     * @param isInPictureInPictureMode 是否进入画中画模式
     */
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        SettingsActivity.logOperation("【画中画】模式变化 → " + (isInPictureInPictureMode ? "进入" : "退出"));

        // 同步遥控器画中画状态
        if (remoteManager != null) {
            remoteManager.setInPipMode(isInPictureInPictureMode);
        }

        // 通知画中画管理器状态变化
        if (pipManager != null) {
            try {
                pipManager.onPipModeChanged(this, isInPictureInPictureMode);
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画】模式变化回调失败：" + e.getMessage());
            }
        }

        // 处理画中画进入/退出逻辑
        if (pipManager != null) {
            if (isInPictureInPictureMode) {
                // 进入画中画：调整UI、暂停非必要组件
                pipManager.handleEnterPip(this, channelPanelController, infoDisplayManager, mPlayerManager, playerView);
            } else {
                // 退出画中画：恢复UI、恢复播放
                pipManager.handleExitPip(new Runnable() {
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

    /**
     * 日志工具方法
     * 同时添加到日志列表和打印Logcat
     * @param msg 日志内容
     */
    private void log(String msg) {
        logList.add(msg);
        Log.d("MainActivity", msg);
    }

    /**
     * 活动暂停生命周期方法
     * 处理播放暂停、画中画状态保持等逻辑
     */
    @Override
    protected void onPause() {
        super.onPause();
        appCoreManager.onPause();
        if (pipManager != null) {
            pipManager.handleOnPause(new Runnable() {
                @Override
                public void run() {
                    // 画中画模式下恢复播放（防止暂停）
                    if (mPlayerManager != null) {
                        mPlayerManager.resume();
                        SettingsActivity.logOperation("【画中画】✅ onPause后立即恢复播放（防止暂停）");
                    }
                }
            });
        }
    }

    /**
     * 活动停止生命周期方法
     * 标记画中画停止状态
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (pipManager != null) {
            pipManager.setStopCalled(true);
            SettingsActivity.logOperation("【画中画】onStop 被调用");
        }
    }

    /**
     * 活动恢复生命周期方法
     * 恢复播放、重新加载设置、适配屏幕等
     */
    @Override
    protected void onResume() {
        super.onResume();
        isOpeningSettings = false; // 重置设置页面标记
        appCoreManager.onResume();
        if (pipManager != null) {
            pipManager.setStopCalled(false);
        }
        // 重新加载设置（防止设置页面修改后未生效）
        loadSettings();
        // 重新应用屏幕比例
        screenRatioManager.apply();
        // 重新应用全屏配置
        displayManager.reapplyFullScreen();

        // 非画中画模式下延迟恢复播放（防止恢复失败）
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
        // 同步遥控器模式
        remoteManager.syncMode();
    }

    /**
     * 窗口焦点变化监听
     * 焦点恢复时重新应用全屏，通知核心管理器
     * @param hasFocus 是否获得焦点
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            displayManager.reapplyFullScreen();
        }
        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    /**
     * 活动销毁生命周期方法
     * 释放所有资源，防止内存泄漏
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 释放各管理器资源
        if (infoDisplayManager != null) {
            infoDisplayManager.release();
        }
        if (remoteManager != null) {
            remoteManager.release();
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
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }

        // 清空单例
        mInstance = null;
        SettingsActivity.logOperation("【系统】APP退出");
    }
}
