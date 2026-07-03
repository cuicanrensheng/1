package com.tv.live;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
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
import android.widget.ImageView;
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
 * 渲染器切换同步优化完成版【已修复全部编译错误】
 */
public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    public List<Channel> channelSourceList = new ArrayList<>();
    public int currentPlayIndex = 0;
    private PlayerView playerView;
    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private GestureManager gestureManager;
    private PlayerStateListenerImpl playerStateListener;
    private DisplayManager displayManager;
    private InfoDisplayManager infoDisplayManager;
    private ChannelPanelController channelPanelController;
    private AppCoreManager appCoreManager;
    private TvRemoteManager remoteManager;
    private PictureInPictureManager pipManager;
    private boolean pipEnable = false;
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean isOpeningSettings = false;
    // ========== 渲染器切换同步优化新增变量 ==========
    private boolean isRendererSwitching = false;
    private boolean playStateBeforeSwitch = false;
    private static final long MASK_AUTO_HIDE_DELAY = 3000L;
    public static List<String> logList = new ArrayList<>();

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
        SettingsActivity.logOperation("【主页】全面屏适配已应用");
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
        initChannelPanelController();
        initRemoteManager();
        initPicture();
        channelPanelController.handleFirstLaunch();
        initPlayer();
        mPlayerManager.registerDecoderModeReceiver();
        mPlayerManager.registerRendererModeReceiver();
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // 切换渲染中屏蔽触摸
                if (isRendererSwitching) return true;
                gestureHelper.handleTouch(event);
                return true;
            }
        });
        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        SettingsActivity.logOperation("【播放】记录上次播放索引：" + currentPlayIndex);
        remoteManager.setNumberChannelEnable(number_channel_enable);
        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();
    }

    private void initPicture() {
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
            @Override public void onPlayChannelUp() { if(!isRendererSwitching) channelPanelController.switchUp(); }
            @Override public void onPlayChannelDown() { if(!isRendererSwitching) channelPanelController.switchDown(); }
            @Override public void onPlayTogglePanel() { if(!isRendererSwitching){ togglePanel(); remoteManager.syncMode();} }
            @Override public void onPlayOpenSettings() { if(!isRendererSwitching) openSettings(); }
            @Override public boolean onPlayBack() { return false; }
            @Override public void onPanelMoveUp() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_UP); }
            @Override public void onPanelMoveDown() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN); }
            @Override public void onPanelMoveLeft() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT); }
            @Override public void onPanelMoveRight() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT); }
            @Override public void onPanelConfirm() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER); }
            @Override public boolean onPanelBack() { boolean handled = channelPanelController.handleBackPressed(); if (!channelPanelController.isPanelOpen()) { remoteManager.syncMode(); } return handled; }
            @Override public void onPanelMenu() { boolean isFavorite = channelPanelController.toggleCurrentFavorite(); SettingsActivity.logOperation("【遥控】菜单键 → " + (isFavorite ? "已添加收藏" : "已取消收藏")); }
            @Override public void onPanelNumber(int number) {}
            @Override public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) { SettingsActivity.logOperation("【遥控】面板焦点切换：" + newFocus); }
            @Override public void onSettingsMoveUp() {}
            @Override public void onSettingsMoveDown() {}
            @Override public void onSettingsConfirm() {}
            @Override public boolean onSettingsBack() { return false; }
            @Override public void onSettingsMenu() {}
            @Override public void onSettingsFocusChanged(int position) {}
            @Override public boolean onPipBack() { moveTaskToBack(false); return true; }
            @Override public void onRequestPlayFocus() { if(!isRendererSwitching) playerView.requestFocus(); }
            @Override public void onChannelNumberSelected(int channelIndex) { if(!isRendererSwitching) channelPanelController.playChannel(channelIndex); }
            @Override public void onShowChannelNumber(String number) { try { infoDisplayManager.showChannelNum(Integer.parseInt(number)); } catch (Exception e) {} }
            @Override public void onHideChannelNumber() { infoDisplayManager.hideChannelNum(); }
        });
    }

    private void initInfoDisplayManager() {
        TextView tv_channel_num = findViewById(R.id.tv_channel_num);
        View info_bar = findViewById(R.id.info_bar);
        TextView tv_channel_name = findViewById(R.id.tv_channel_name);
        TextView tv_tag_fhd = findViewById(R.id.tv_tag_audio);
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

    private void initChannelPanelController() {
        View panel_layout = findViewById(R.id.panel_layout);
        View ll_left_panel = null;
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
        // 修复GroupListManager构造缺少ListView参数报错
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
                if(!isRendererSwitching) playChannel(channel, index);
            }
        });
    }

    // ====================== 优化后 initPlayer 渲染同步 + 遮罩 ======================
    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.setOnPlayerViewRecreatedListener(new TVPlayerManager.OnPlayerViewRecreatedListener() {
            // 渲染器切换开始回调
            @Override
            public void onRendererSwitchStart() {
                isRendererSwitching = true;
                // 修复找不到mPlayerManager.isPlaying()符号
                playStateBeforeSwitch = mPlayerManager.isPlaying();
                displayManager.showLoading("切换渲染器中...");
                log("【渲染切换】开始切换，切换前播放状态：" + playStateBeforeSwitch);
            }
            // 视图重建完成
            @Override
            public void onPlayerViewRecreated(PlayerView newPlayerView) {
                MainActivity.this.playerView = newPlayerView;
                // 重建手势
                gestureManager = new GestureManager(MainActivity.this);
                final PlayerGestureHelper newGestureHelper = gestureManager.create();
                newPlayerView.setOnTouchListener((v, event) -> {
                    if (isRendererSwitching) return true;
                    newGestureHelper.handleTouch(event);
                    return true;
                });
                newPlayerView.setFocusable(true);
                newPlayerView.setFocusableInTouchMode(true);
                newPlayerView.requestFocus();
                // 修复remoteManager.onRequestPlayFocus找不到符号
                remoteManager.onRequestPlayFocus();
                SettingsActivity.logOperation("【渲染器】视图重建，焦点&手势已重绑");
            }
            // 切换前冻结画面遮罩
            @Override
            public void onDecoderSwitchFreezeFrame(Bitmap freezeFrame) {
                if (freezeFrame != null && isRendererSwitching) {
                    // 修复displayManager.showScreenshotMask不存在
                    displayManager.showScreenshotMask(freezeFrame);
                    // 超时自动隐藏遮罩防止卡死
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if(isRendererSwitching){
                            displayManager.hideScreenshotMask();
                            log("【遮罩】渲染切换超时自动隐藏");
                        }
                    }, MASK_AUTO_HIDE_DELAY);
                    log("【渲染切换】显示冻结遮罩");
                }
            }
            // 切换完成移除遮罩、恢复播放
            @Override
            public void onDecoderSwitchUnfreezeFrame() {
                displayManager.hideScreenshotMask();
                displayManager.hideLoading();
                // 恢复切换前播放状态
                if (playStateBeforeSwitch) {
                    mPlayerManager.resume();
                }
                isRendererSwitching = false;
                log("【渲染切换】切换完成，恢复播放");
            }
        });
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
                runOnUiThread(() -> {
                    String channelName = "";
                    if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                        Channel ch = channelSourceList.get(currentPlayIndex);
                        if (ch != null) channelName = ch.getName();
                    }
                    appCoreManager.handleSourceFailed(channelName);
                });
            }
        });
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
                    // 修复channels.size 缺少()
                    if (remoteManager != null) remoteManager.setTotalChannelCount(channels.size());
                    if (!appCoreManager.hasPlayedWithCache()) {
                        if (currentPlayIndex >= 0 && currentPlayIndex < channels.size()) {
                            Channel ch = channelSourceList.get(currentPlayIndex);
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
            public void onNeedSkipChannel() { if(!isRendererSwitching) channelPanelController.switchDown(); }
            @Override
            public void onSkipLimitReached(int maxSkip) {
                // 修复未定义变量max，替换为参数maxSkip
                Toast.makeText(MainActivity.this, "已跳过 " + maxSkip + " 个失效频道", Toast.LENGTH_SHORT).show();
            }
            @Override public void onSourceFailed(String channelName, int failedCount) {}
        });
        appCoreManager.registerReceivers();
    }

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);
        pipEnable = sp.getBoolean("pipEnable", false);
        String decoderMode = sp.getString("decoder_mode", "auto");
        int mode = TVPlayerManager.DECODER_MODE_AUTO;
        if ("hard".equals(decoderMode)) mode = TVPlayerManager.DECODER_MODE_HARD;
        else if ("soft".equals(decoderMode)) mode = TVPlayerManager.DECODER_MODE_SOFT;
        if (mPlayerManager != null) mPlayerManager.setDecoderMode(mode);
        if (remoteManager != null) remoteManager.setNumberChannelEnable(number_channel_enable);
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }
        if (pipManager != null) pipManager.setPipEnabled(pipEnable);
        SettingsActivity.logOperation("【设置】EPG：" + epg_enable + " 反转：" + channel_reverse + " 数字选台：" + number_channel_enable);
    }

    public boolean isChannelReverse() { return channel_reverse; }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty() || isRendererSwitching) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        playChannel(channelSourceList.get(index), index);
    }

    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null || isRendererSwitching) return;
        currentPlayIndex = index;
        log("========================================");
        log("【播放】频道：" + channel.getName() + " 地址：" + channel.getPlayUrl());
        log("========================================");
        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        mPlayerManager.playUrl(channel.getPlayUrl(), channel.getName());
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
        infoDisplayManager.showChannelNum(index + 1);
        try { appConfig.addRecentChannel(channel.getName()); } catch (Exception e) {}
        appCoreManager.resetSourceFailedCount();
        if (pipManager != null && pipManager.isInPipMode()) {
            try {
                pipManager.updateChannelInfo(index + 1, channel.getName(), live.bitrate);
            } catch (Exception e) { log("画中画更新失败"); }
        }
    }

    public void togglePanel() { channelPanelController.togglePanel(); remoteManager.syncMode(); }
    public void playPrev() { if(!isRendererSwitching) channelPanelController.playPrev(); }
    public void playNext() { if(!isRendererSwitching) channelPanelController.playNext(); }

    @Override
    public void onBackPressed() {
        if (remoteManager != null && remoteManager.handleBackPressed()) return;
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 渲染切换时拦截大部分按键，只允许返回
        if(isRendererSwitching && keyCode != KeyEvent.KEYCODE_BACK){
            Toast.makeText(this,"渲染切换中，请稍等",Toast.LENGTH_SHORT).show();
            return true;
        }
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    public void openSettings() {
        isOpeningSettings = true;
        appCoreManager.beforeOpenSettings();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    public void onReceiveConfig(String liveUrl, String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isOpeningSettings || isRendererSwitching) return;
        if (pipManager != null) pipManager.enterPip(this, mPlayerManager, pipEnable);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        SettingsActivity.logOperation("【画中画】模式变更：" + isInPictureInPictureMode);
        if (remoteManager != null) remoteManager.setInPipMode(isInPictureInPictureMode);
        if (pipManager != null) {
            try { pipManager.onPipModeChanged(this, isInPictureInPictureMode); } catch (Exception e) {}
            if (isInPictureInPictureMode) {
                pipManager.handleEnterPip(this, channelPanelController, infoDisplayManager, mPlayerManager, playerView);
            } else {
                pipManager.handleExitPip(() -> SettingsActivity.logOperation("退出画中画"));
                // 修复handleExitPipRestore参数缺失TVPlayerManager
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
                if (mPlayerManager != null) mPlayerManager.resume();
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        isRendererSwitching = false;
        if (pipManager != null) pipManager.setStopCalled(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isOpeningSettings = false;
        isRendererSwitching = false;
        appCoreManager.onResume();
        loadSettings();
        screenRatioManager.apply();
        displayManager.reapplyFullScreen();
        if (pipManager == null || !pipManager.isInPipMode()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (pipManager != null) pipManager.resumePlayback(mPlayerManager);
            }, 200);
        }
        remoteManager.syncMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) displayManager.reapplyFullScreen();
        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        // 强制重置切换标记
        isRendererSwitching = false;
        if (infoDisplayManager != null) infoDisplayManager.release();
        if (remoteManager != null) remoteManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();
        if (pipManager != null) pipManager.release();
        if (mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
        SettingsActivity.logOperation("APP销毁");
    }
}
