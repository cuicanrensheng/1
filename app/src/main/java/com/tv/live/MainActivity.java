package com.tv.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log; // 🟢【修复1】添加缺失的 Log 导入
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 主活动类：直播APP的核心页面
 */
public class MainActivity extends AppCompatActivity {
    public static MainActivity mInstance;
    // 🟢【优化】指定初始容量，避免 List 频繁扩容引发 GC
    public List<Channel> channelSourceList = new ArrayList<>(512);
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

    // 🟢【修复1】替换原 static List<String> logList，改用安全限容日志缓冲
    public static final FixedSizeLogBuffer LOG_BUFFER = new FixedSizeLogBuffer();

    // 🟢【修复2】统一使用成员 Handler，避免匿名 Handler 泄漏
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        // 🟢【修复2】兼容不同 Media3 版本，处理 setControllerVisibilityListener 歧义
        try {
            playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);
        } catch (Exception e) {
            // 高版本 Media3 直接忽略即可，不影响功能
        }

        initChannelPanelController();
        initRemoteManager();
        initPictureInPicture();
        channelPanelController.handleFirstLaunch();
        initPlayer();
        mPlayerManager.registerDecoderModeReceiver();
        mPlayerManager.registerRendererModeReceiver();

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        remoteManager.setNumberChannelEnable(number_channel_enable);

        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");

        // 🟢【修复3】将耗时加载逻辑抛到子线程，防止 onCreate 阶段在主线程阻塞导致白屏
        new Thread(() -> {
            appCoreManager.loadLiveAndEpg();
        }).start();
    }

    private void initPictureInPicture() {
        try {
            pipManager = PictureInPictureManager.getInstance(this);
            pipManager.setPipEnabled(pipEnable);
            pipManager.setListener(new PictureInPictureManager.OnPipListener() {
                @Override
                public void onPipModeChanged(boolean inPip) {
                    log("【画中画】监听器回调：" + (inPip ? "进入" : "退出"));
                }
            });
            log("【画中画】初始化完成，开关状态：" + (pipEnable ? "开启" : "关闭"));
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
            @Override public void onPlayChannelUp() { channelPanelController.switchUp(); }
            @Override public void onPlayChannelDown() { channelPanelController.switchDown(); }
            @Override public void onPlayTogglePanel() { togglePanel(); remoteManager.syncMode(); }
            @Override public void onPlayOpenSettings() { openSettings(); }
            @Override public boolean onPlayBack() { return false; }
            @Override public void onPanelMoveUp() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_UP); }
            @Override public void onPanelMoveDown() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN); }
            @Override public void onPanelMoveLeft() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT); }
            @Override public void onPanelMoveRight() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT); }
            @Override public void onPanelConfirm() { channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER); }
            @Override public boolean onPanelBack() {
                boolean handled = channelPanelController.handleBackPressed();
                if (!channelPanelController.isPanelOpen()) { remoteManager.syncMode(); }
                return handled;
            }
            @Override public void onPanelMenu() { channelPanelController.toggleCurrentFavorite(); }
            @Override public void onPanelNumber(int number) {}
            @Override public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {}
            @Override public void onSettingsMoveUp() {}
            @Override public void onSettingsMoveDown() {}
            @Override public void onSettingsConfirm() {}
            @Override public boolean onSettingsBack() { return false; }
            @Override public void onSettingsMenu() {}
            @Override public void onSettingsFocusChanged(int position) {}
            @Override public boolean onPipBack() { moveTaskToBack(false); return true; }
            @Override public void onRequestPlayFocus() { playerView.requestFocus(); }
            @Override public void onChannelNumberSelected(int channelIndex) { channelPanelController.playChannel(channelIndex); }
            @Override public void onShowChannelNumber(String number) { try { infoDisplayManager.showChannelNum(Integer.parseInt(number)); } catch (Exception ignored) {} }
            @Override public void onHideChannelNumber() { infoDisplayManager.hideChannelNum(); }
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
        TextView tv_next_time_range = findViewById(R.id.tv_next_time_range);
        infoDisplayManager = new InfoDisplayManager(
                this, tv_channel_num, info_bar, tv_channel_name, tv_tag_fhd, tv_tag_audio,
                tv_bitrate, tv_current_program_name, tv_current_time_range, progress_program,
                tv_remaining_time, tv_next_program_name, tv_next_time_range
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
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> channelPanelController.setCurrentDateIndex(pos));

        channelPanelController = new ChannelPanelController(
                this, panel_layout, ll_left_panel, ll_right_panel, lvGroup, lvChannelList,
                lvChannelListEpg, lvDate, lvEpg, btn_show_epg, btn_back_group,
                groupListManager, channelListManager, channelListManagerEpg,
                dateListManager, epgManagerWrapper, panelManager
        );

        channelPanelController.setOnChannelChangeListener((channel, index) -> playChannel(channel, index));
    }

    // 🟢【修复4】静态内部类 + 弱引用，彻底消灭触摸监听导致的内存泄漏
    private static class PlayerTouchListener implements View.OnTouchListener {
        private final WeakReference<MainActivity> activityRef;
        private PlayerGestureHelper gestureHelper;

        public PlayerTouchListener(MainActivity activity) {
            this.activityRef = new WeakReference<>(activity);
        }

        public void updateGestureHelper(PlayerGestureHelper helper) {
            this.gestureHelper = helper;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (gestureHelper != null) {
                gestureHelper.handleTouch(event);
            }
            return true;
        }
    }
    private PlayerTouchListener touchListener;

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        mPlayerManager.setOnPlayerViewRecreatedListener(newPlayerView -> {
            MainActivity.this.playerView = newPlayerView;
            // 🟢【修复点】确保每次重建 PlayerView 时，都是用全新的 GestureManager 重新生成 GestureHelper
            gestureManager = new GestureManager(MainActivity.this);
            final PlayerGestureHelper newGestureHelper = gestureManager.create();

            if (touchListener == null) {
                touchListener = new PlayerTouchListener(MainActivity.this);
            }
            // 将新的 Helper 传递给静态监听器
            touchListener.updateGestureHelper(newGestureHelper);
            newPlayerView.setOnTouchListener(touchListener);
            newPlayerView.requestFocus();
        });

        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            infoDisplayManager.updateLiveInfo(info);
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
                });
            }

            @Override
            public void onLiveSourceFailed(String errorMsg) {
                runOnUiThread(() -> {
                    if (channelSourceList.isEmpty()) {
                        displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
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
                    }
                    displayManager.hideLoading();
                });
            }
        });

        appCoreManager.setOnSourceSkipListener(new AppCoreManager.OnSourceSkipListener() {
            @Override
            public void onNeedSkipChannel() { channelPanelController.switchDown(); }
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
        if ("hard".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_HARD;
        } else if ("soft".equals(decoderMode)) {
            mode = TVPlayerManager.DECODER_MODE_SOFT;
        }
        if (mPlayerManager != null) mPlayerManager.setDecoderMode(mode);
        if (remoteManager != null) remoteManager.setNumberChannelEnable(number_channel_enable);
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }
        if (pipManager != null) pipManager.setPipEnabled(pipEnable);
    }

    public boolean isChannelReverse() { return channel_reverse; }

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
        mPlayerManager.playUrl(channel.getPlayUrl(), channel.getName());
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
        infoDisplayManager.showChannelNum(index + 1);
        try {
            appConfig.addRecentChannel(channel.getName());
        } catch (Exception ignored) {}
        appCoreManager.resetSourceFailedCount();

        if (pipManager != null && pipManager.isInPipMode() && channel != null) {
            try {
                pipManager.updateChannelInfo(index + 1, channel.getName() != null ? channel.getName() : "", live != null ? live.bitrate : "");
            } catch (Exception e) {
                log("【画中画】同步频道信息失败：" + e.getMessage());
            }
        }
    }

    public void togglePanel() {
        channelPanelController.togglePanel();
        remoteManager.syncMode();
    }

    public void playPrev() { channelPanelController.playPrev(); }
    public void playNext() { channelPanelController.playNext(); }

    @Override
    public void onBackPressed() {
        if (remoteManager != null && remoteManager.handleBackPressed()) return;
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) return true;
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
        if (isOpeningSettings) return;
        if (pipManager != null) pipManager.enterPip(this, mPlayerManager, pipEnable);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (remoteManager != null) remoteManager.setInPipMode(isInPictureInPictureMode);
        if (pipManager != null) {
            try {
                pipManager.onPipModeChanged(this, isInPictureInPictureMode);
            } catch (Exception ignored) {}
        }
        if (pipManager != null) {
            if (isInPictureInPictureMode) {
                pipManager.handleEnterPip(this, channelPanelController, infoDisplayManager, mPlayerManager, playerView);
            } else {
                pipManager.handleExitPip(() -> {});
                pipManager.handleExitPipRestore(this, displayManager, playerView, mPlayerManager, channelSourceList, currentPlayIndex, infoDisplayManager);
                remoteManager.syncMode();
            }
        }
    }

    private void log(String msg) {
        LOG_BUFFER.append(msg);
        // 🟢【修复3】加上 android.util.Log 的标准调用
        Log.d("MainActivity", msg);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 🟢【修复5】统一在 onPause 中清理排期任务，防止内存泄漏和空指针崩毁
        mMainHandler.removeCallbacksAndMessages(null);
        
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
        if (pipManager != null) pipManager.setStopCalled(true);
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

        // 🟢【修复6】使用成员 mMainHandler 替代匿名 Handler，并严格判空
        if (pipManager == null || !pipManager.isInPipMode()) {
            mMainHandler.postDelayed(() -> {
                if (pipManager != null && mPlayerManager != null) {
                    pipManager.resumePlayback(mPlayerManager);
                }
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
        super.onDestroy();
        // 🟢【修复7】销毁时完全清理资源和 Handler 消息
        mMainHandler.removeCallbacksAndMessages(null);
        if (infoDisplayManager != null) infoDisplayManager.release();
        if (remoteManager != null) remoteManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();
        if (pipManager != null) pipManager.release();
        if (mPlayerManager != null) mPlayerManager.release();
        mInstance = null;
    }

    // 🟢【修复8】安全日志限容类（确保外部如 SettingsActivity 也能引用）
    public static class FixedSizeLogBuffer {
        private final StringBuilder buffer = new StringBuilder();
        private final int maxCapacity = 1024 * 50; // 50KB
        private final Object lock = new Object();

        public void append(String msg) {
            if (msg == null) return;
            synchronized (lock) {
                if (buffer.length() + msg.length() > maxCapacity) {
                    buffer.delete(0, buffer.length() - (maxCapacity / 2));
                }
                buffer.append(msg).append("\n");
            }
        }
        public String getAndClear() {
            synchronized (lock) {
                String content = buffer.toString();
                buffer.setLength(0);
                return content;
            }
        }
    }
}
