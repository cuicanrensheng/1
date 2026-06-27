package com.tv.live;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.SimpleDateFormat;
import android.graphics.Rect;
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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
/**
 * 主活动类：直播APP的核心页面，负责直播播放、频道管理、交互控制等核心功能
 * 新增：频道面板右上角频道号/日期/周几/时间、右下角信息栏联动逻辑
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
    // 播放器状态监听器
    private PlayerStateListenerImpl playerStateListener;
    // 显示管理类
    private DisplayManager displayManager;
    // 信息显示管理类
    private InfoDisplayManager infoDisplayManager;
    // 频道面板控制器
    private ChannelPanelController channelPanelController;
    private AppCoreManager appCoreManager;
    private TvRemoteManager remoteManager;
    private PictureInPictureManager pipManager;
    private boolean pipEnable = false;
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean isOpeningSettings = false;
    public static List<String> logList = new ArrayList<>();

    // ===================== 新增：面板信息控件 =====================
    private TextView tvPanelChannelNum;
    private TextView tvPanelDateTime;
    private TextView tvPanelBitrate;
    private TextView tvPanelEpgBrief;
    // 时间刷新定时器
    private final Handler timeHandler = new Handler(Looper.getMainLooper());
    private Runnable timeRefreshTask;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd E HH:mm", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsActivity.logOperation("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");
        mInstance = this;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        displayManager = new DisplayManager(this);
        setContentView(R.layout.activity_main);
        displayManager.applyFullScreen();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        initInfoDisplayManager();
        appConfig = AppConfig.getInstance(this);
        loadSettings();
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);
        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);

        // 绑定面板新增信息控件
        tvPanelChannelNum = findViewById(R.id.tv_panel_channel_num);
        tvPanelDateTime = findViewById(R.id.tv_panel_date_time);
        tvPanelBitrate = findViewById(R.id.tv_panel_bitrate);
        tvPanelEpgBrief = findViewById(R.id.tv_panel_epg_brief);
        // 初始化定时刷新时间任务
        initTimeTask();

        initChannelPanelController();
        initRemoteManager();
        initPictureInPicture();
        channelPanelController.handleFirstLaunch();
        initPlayer();
        mPlayerManager.registerDecoderModeReceiver();
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener((v, event) -> {
            gestureHelper.handleTouch(event);
            return true;
        });
        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        remoteManager.setNumberChannelEnable(number_channel_enable);
        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();
    }

    /** 初始化每秒刷新时间任务 */
    private void initTimeTask() {
        timeRefreshTask = new Runnable() {
            @Override
            public void run() {
                if (tvPanelDateTime != null) {
                    tvPanelDateTime.setText(DATE_FORMAT.format(new Date()));
                }
                timeHandler.postDelayed(this, 1000);
            }
        };
    }

    /** 启动时间刷新 */
    private void startTimeTask() {
        timeHandler.removeCallbacks(timeRefreshTask);
        timeHandler.post(timeRefreshTask);
        // 打开面板立刻刷新一次所有信息
        refreshAllPanelInfo();
    }

    /** 停止时间刷新 */
    private void stopTimeTask() {
        timeHandler.removeCallbacks(timeRefreshTask);
    }

    /** 一次性刷新面板全部信息：频道号/时间/码率/EPG */
    private void refreshAllPanelInfo() {
        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel currCh = channelSourceList.get(currentPlayIndex);
            // 更新频道号
            if (tvPanelChannelNum != null) {
                tvPanelChannelNum.setText((currentPlayIndex + 1) + "频道 " + currCh.getName());
            }
            // 更新码率
            TVPlayerManager.LiveInfo info = mPlayerManager.getLiveInfo();
            if (tvPanelBitrate != null && info != null) {
                tvPanelBitrate.setText("码率：" + info.bitrate);
            }
            // 更新EPG简要
            refreshPanelEpgText(currCh);
        }
    }

    /** 刷新面板EPG节目文字 */
    private void refreshPanelEpgText(Channel channel) {
        if (tvPanelEpgBrief == null || channel == null) return;
        int dateIdx = channelPanelController.getCurrentDateIndex();
        List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channel.getName());
        String now = getNowTime();
        String currPro = "无节目", nextPro = "无下一档";
        // 遍历查找当前播放节目
        for (Channel.EpgItem item : epgList) {
            String s = item.time.split("-")[0].trim();
            String e = item.time.contains("-") ? item.time.split("-")[1].trim() : addOneHour(s);
            if (now.compareTo(s) >= 0 && now.compareTo(e) < 0) {
                currPro = item.title;
                break;
            }
        }
        tvPanelEpgBrief.setText("当前：" + currPro + " | 下一档：" + nextPro);
    }

    /** 获取当前HH:mm */
    private String getNowTime() {
        Calendar c = Calendar.getInstance();
        return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** 时分+1小时工具 */
    private String addOneHour(String hm) {
        try {
            String[] arr = hm.split(":");
            int h = Integer.parseInt(arr[0]);
            int m = Integer.parseInt(arr[1]);
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
            cal.add(Calendar.HOUR, 1);
            return String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        } catch (Exception e) {
            return "23:59";
        }
    }

    private void initPictureInPicture() {
        try {
            pipManager = PictureInPictureManager.getInstance(this);
            pipManager.setPipEnabled(pipEnable);
            pipManager.setDebugLogEnabled(true);
            pipManager.setListener(new PictureInPictureManager.OnPipListener() {
                @Override
                public void onPipModeChanged(boolean inPip) {
                    log("【画中画】监听器回调：" + (inPip ? "进入" : "退出"));
                }
            });
            log("【画中画】初始化完成，开关状态：" + (pipEnable ? "开启" : "关闭"));
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持：" + pipManager.isPipSupported());
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
    }

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
        TextView tv_next_time_range = findViewById(R.id.tv_next_time);
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

    private void initChannelPanelController() {
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
        EpgManager.getInstance(this);
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
        ChannelListManager channelListManagerEpg = new ChannelListManager(this, lvChannelListEpg);
        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        DateListManager dateListManager = new DateListManager(this, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        // 修复构造参数变量名错误
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);
        // ========== 关键：面板开关联动监听 ==========
        panelManager.setOnPanelVisibilityListener(visible -> {
            if (visible) {
                startTimeTask();
            } else {
                stopTimeTask();
            }
        });
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            channelPanelController.setCurrentDateIndex(pos);
            // 切换日期刷新面板EPG文字
            if (channelSourceList.size() > currentPlayIndex) {
                refreshPanelEpgText(channelSourceList.get(currentPlayIndex));
            }
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
        channelPanelController.setOnChannelChangeListener((channel, index) -> {
            playChannel(channel, index);
            // 切台同步刷新面板信息
            refreshAllPanelInfo();
        });
    }

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            infoDisplayManager.updateLiveInfo(info);
            // 码率变化同步更新面板右下角
            if (channelPanelController.isPanelOpen()) {
                if (tvPanelBitrate != null && info != null) {
                    tvPanelBitrate.setText("码率：" + info.bitrate);
                }
            }
            if (pipManager != null) pipManager.updatePlayState(true);
        });
        mPlayerManager.setOnSourceFailedListener(() -> runOnUiThread(() -> {
            String channelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel ch = channelSourceList.get(currentPlayIndex);
                if (ch != null) channelName = ch.getName();
            }
            appCoreManager.handleSourceFailed(channelName);
        }));
    }

    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);
        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveSourceLoaded(List<Channel> channels, boolean fromCache) {
                runOnUiThread(() -> {
                    channelSourceList.clear();
                    channelSourceList.addAll(channels);
                    channelPanelController.setChannels(channels);
                    if (remoteManager != null) remoteManager.setTotalChannelCount(channels.size());
                    if (!appCoreManager.hasPlayedWithCache()) {
                        if (currentPlayIndex >= 0 && currentPlayIndex < channels.size()) {
                            Channel ch = channels.get(currentPlayIndex);
                            playChannel(ch, currentPlayIndex);
                            appCoreManager.setHasPlayedWithCache(true);
                        }
                    }
                    displayManager.hideLoading();
                    log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channels.size());
                });
            }
            @Override
            public void onLiveSourceFailed(String errorMsg) {
                runOnUiThread(() -> {
                    if (channelSourceList.isEmpty()) {
                        displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                        SettingsActivity.logOperation("【加载】直播源加载失败：" + errorMsg);
                    } else {
                        log("【缓存】使用缓存数据继续播放");
                        displayManager.hideLoading();
                    }
                });
            }
            @Override
            public void onEpgLoaded() {
                runOnUiThread(() -> {
                    if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                        Channel curr = channelSourceList.get(currentPlayIndex);
                        infoDisplayManager.updateEpgInfo(curr);
                        // EPG刷新同步面板节目文字
                        if (channelPanelController.isPanelOpen()) {
                            refreshPanelEpgText(curr);
                        }
                    }
                });
            }
            @Override
            public void onLoadTimeout(boolean hasData) {
                runOnUiThread(() -> {
                    log("【加载】超时，自动隐藏加载动画");
                    if (!hasData) {
                        displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                        SettingsActivity.logOperation("【加载】直播源加载超时");
                    }
                    displayManager.hideLoading();
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
                Toast.makeText(MainActivity.this, "已跳过 " + maxSkip + " 个失效频道，请检查直播源", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onSourceFailed(String channelName, int failedCount) {}
        });
        appCoreManager.registerReceivers();
    }

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);
        pipEnable = sp.getBoolean("pip_enable", false);
        String decoderMode = sp.getString("decoder_mode", "auto");
        int mode = TVPlayerManager.DECODER_MODE_AUTO;
        if ("hard".equals(decoderMode)) mode = TVPlayerManager.DECODER_MODE_HARD;
        else if ("soft".equals(decoderMode)) mode = TVPlayerManager.DECODER_MODE_SOFT;
        if (mPlayerManager != null) mPlayerManager.setDecoderMode(mode);
        String modeName;
        switch (mode) {
            case TVPlayerManager.DECODER_MODE_HARD: modeName = "硬解"; break;
            case TVPlayerManager.DECODER_MODE_SOFT: modeName = "软解（兼容性好）"; break;
            default: modeName = "自动（推荐）";
        }
        SettingsActivity.logOperation("【设置】解码器模式：" + modeName);
        if (remoteManager != null) remoteManager.setNumberChannelEnable(number_channel_enable);
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }
        if (pipManager != null) pipManager.setPipEnabled(pipEnable);
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
        try { appConfig.addRecentChannel(channel.getName()); } catch (Exception e) {}
        appCoreManager.resetSourceFailedCount();
        if (pipManager != null && pipManager.isInPipMode()) {
            try {
                pipManager.updateChannelInfo(index + 1,
                        channel.getName() != null ? channel.getName() : "",
                        live != null ? live.bitrate : "");
            } catch (Exception e) {
                log("【画中画】同步频道信息失败：" + e.getMessage());
            }
        }
        // 切台同步面板信息
        refreshAllPanelInfo();
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
            if (isInPictureInPictureMode) {
                pipManager.handleEnterPip(this, channelPanelController, infoDisplayManager, mPlayerManager, playerView);
            } else {
                pipManager.handleExitPip(() -> SettingsActivity.logOperation("【画中画】应用已关闭，释放播放器"));
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
            pipManager.handleOnPause(() -> {
                if (mPlayerManager != null) {
                    mPlayerManager.resume();
                    SettingsActivity.logOperation("【画中画】✅ onPause后立即恢复播放（防止暂停）");
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
        if (pipManager != null) pipManager.setStopCalled(false);
        loadSettings();
        screenRatioManager.apply();
        displayManager.reapplyFullScreen();
        if (pipManager == null || !pipManager.isInPipMode()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (pipManager != null) pipManager.resumePlayback(mPlayerManager);
            }, 200);
        }
        remoteManager.syncMode();
        // 页面恢复，面板打开则重启时间刷新
        if (channelPanelController.isPanelOpen()) {
            startTimeTask();
        }
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
        // 停止定时任务，释放handler防止内存泄漏
        stopTimeTask();
        timeHandler.removeCallbacksAndMessages(null);

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
