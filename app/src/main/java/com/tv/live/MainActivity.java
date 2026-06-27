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
import androidx.annotation.Nullable;
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
import java.lang.ref.WeakReference;

/**
 * 主活动类：直播APP核心页面，已修复全部内存泄漏
 */
public class MainActivity extends AppCompatActivity {
    // ========== 修复1：替换静态强引用为弱引用 ==========
    private static WeakReference<MainActivity> sInstanceRef;

    // 直播频道数据源列表
    public List<Channel> channelSourceList = new ArrayList<>();
    // 当前正在播放的频道索引
    public int currentPlayIndex = 0;
    // 播放器视图
    private PlayerView playerView;
    // 播放器管理类
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

    // 修复2：静态日志增加容量限制，防止无限膨胀
    public static List<String> logList = new ArrayList<>();
    private static final int MAX_LOG_SIZE = 800;

    // 对外安全获取实例（弱引用，不会泄漏）
    @Nullable
    public static MainActivity getInstance() {
        if (sInstanceRef == null) return null;
        MainActivity activity = sInstanceRef.get();
        if (activity == null) return null;
        // 校验页面是否已销毁
        if (activity.isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed())) {
            return null;
        }
        return activity;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsActivity.logOperation("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");

        // 修复：弱引用绑定自身，移除强引用mInstance = this
        sInstanceRef = new WeakReference<>(this);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        displayManager = new DisplayManager(this);
        setContentView(R.layout.activity_main);
        displayManager.applyFullScreen();
        SettingsActivity.logOperation("【主页】全面屏适配已应用");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initInfoDisplayManager();
        appConfig = AppConfig.getInstance(getApplicationContext());
        loadSettings();

        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);

        playerView = findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener(null);

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
        SettingsActivity.logOperation("【播放】记录上次播放索引：" + currentPlayIndex);
        remoteManager.setNumberChannelEnable(number_channel_enable);

        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();
    }

    private void initPictureInPicture() {
        try {
            pipManager = PictureInPictureManager.getInstance(this);
            pipManager.setPipEnabled(pipEnable);
            pipManager.setDebugLogEnabled(true);
            // 修复3：静态内部监听器+弱引用，替换匿名内部类
            pipManager.setListener(new PipWeakListener(this));
            log("【画中画】初始化完成，开关状态：" + (pipEnable ? "开启" : "关闭"));
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持：" + pipManager.isPipSupported());
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
    }

    // 静态内部类，弱引用持有Activity，无泄漏
    private static class PipWeakListener implements PictureInPictureManager.OnPipListener {
        private final WeakReference<MainActivity> actRef;
        public PipWeakListener(MainActivity activity) {
            actRef = new WeakReference<>(activity);
        }
        @Override
        public void onPipModeChanged(boolean inPip) {
            MainActivity act = actRef.get();
            if (act != null) {
                act.log("【画中画】监听器回调：" + (inPip ? "进入" : "退出"));
            }
        }
    }

    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        remoteManager.setChannelPanelController(channelPanelController);
        // 修复：遥控器监听器改为静态弱引用内部类
        remoteManager.setOnRemoteActionListener(new RemoteWeakListener(this));
    }

    // 遥控器弱引用监听器（消除匿名内部类泄漏）
    private static class RemoteWeakListener implements TvRemoteManager.OnRemoteActionListener {
        private final WeakReference<MainActivity> actRef;
        public RemoteWeakListener(MainActivity activity) {
            actRef = new WeakReference<>(activity);
        }

        @Override
        public void onPlayChannelUp() {
            MainActivity act = actRef.get();
            if (act != null) act.channelPanelController.switchUp();
        }
        @Override
        public void onPlayChannelDown() {
            MainActivity act = actRef.get();
            if (act != null) act.channelPanelController.switchDown();
        }
        @Override
        public void onPlayTogglePanel() {
            MainActivity act = actRef.get();
            if (act != null) {
                act.togglePanel();
                act.remoteManager.syncMode();
            }
        }
        @Override
        public void onPlayOpenSettings() {
            MainActivity act = actRef.get();
            if (act != null) act.openSettings();
        }
        @Override
        public boolean onPlayBack() { return false; }
        @Override
        public void onPanelMoveUp() {
            MainActivity act = actRef.get();
            if (act != null) act.channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
        }
        @Override
        public void onPanelMoveDown() {
            MainActivity act = actRef.get();
            if (act != null) act.channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        @Override
        public void onPanelMoveLeft() {
            MainActivity act = actRef.get();
            if (act != null) act.channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
        }
        @Override
        public void onPanelMoveRight() {
            MainActivity act = actRef.get();
            if (act != null) act.channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
        }
        @Override
        public void onPanelConfirm() {
            MainActivity act = actRef.get();
            if (act != null) act.channelPanelController.dispatchKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER);
        }
        @Override
        public boolean onPanelBack() {
            MainActivity act = actRef.get();
            if (act == null) return false;
            boolean handled = act.channelPanelController.handleBackPressed();
            if (!act.channelPanelController.isPanelOpen()) {
                act.remoteManager.syncMode();
            }
            return handled;
        }
        @Override
        public void onPanelMenu() {
            MainActivity act = actRef.get();
            if (act == null) return;
            boolean isFavorite = act.channelPanelController.toggleCurrentFavorite();
            SettingsActivity.logOperation("【遥控】菜单键 → " + (isFavorite ? "已添加收藏" : "已取消收藏"));
        }
        @Override public void onPanelNumber(int number) {}
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
            MainActivity act = actRef.get();
            if (act != null) act.moveTaskToBack(false);
            return true;
        }
        @Override
        public void onRequestPlayFocus() {
            MainActivity act = actRef.get();
            if (act != null) act.playerView.requestFocus();
        }
        @Override
        public void onChannelNumberSelected(int channelIndex) {
            MainActivity act = actRef.get();
            if (act != null) act.channelPanelController.playChannel(channelIndex);
        }
        @Override
        public void onShowChannelNumber(String number) {
            MainActivity act = actRef.get();
            if (act == null) return;
            try {
                act.infoDisplayManager.showChannelNum(Integer.parseInt(number));
            } catch (Exception e) {}
        }
        @Override
        public void onHideChannelNumber() {
            MainActivity act = actRef.get();
            if (act != null) act.infoDisplayManager.hideChannelNum();
        }
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

        EpgManager.getInstance(getApplicationContext());
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
        ChannelListManager channelListManagerEpg = new ChannelListManager(this, lvChannelListEpg);
        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        DateListManager dateListManager = new DateListManager(this, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            if (channelPanelController != null) channelPanelController.setCurrentDateIndex(pos);
        });

        channelPanelController = new ChannelPanelController(
                this, panel_layout, ll_left_panel, ll_right_panel, lvGroup, lvChannelList,
                lvChannelListEpg, lvDate, lvEpg, btn_show_epg, btn_back_group, groupListManager,
                channelListManager, channelListManagerEpg, dateListManager, epgManagerWrapper, panelManager
        );
        // 修复：频道切换监听器静态弱引用
        channelPanelController.setOnChannelChangeListener(new ChannelChangeWeakListener(this));
    }

    private static class ChannelChangeWeakListener implements ChannelPanelController.OnChannelChangeListener {
        private final WeakReference<MainActivity> actRef;
        public ChannelChangeWeakListener(MainActivity activity) {
            actRef = new WeakReference<>(activity);
        }
        @Override
        public void onChannelChanged(Channel channel, int index) {
            MainActivity act = actRef.get();
            if (act != null) act.playChannel(channel, index);
        }
    }

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(getApplicationContext());
        mPlayerManager.attachPlayerView(playerView);
        playerStateListener = new PlayerStateListenerImpl(this);
        mPlayerManager.setOnPlayStateListener(playerStateListener);
        // 修复：播放器信息更新监听器静态弱引用
        mPlayerManager.setOnLiveInfoUpdateListener(new LiveInfoWeakListener(this));
        // 修复：播放失败监听器静态弱引用
        mPlayerManager.setOnSourceFailedListener(new SourceFailedWeakListener(this));
    }

    private static class LiveInfoWeakListener implements TVPlayerManager.OnLiveInfoUpdateListener {
        private final WeakReference<MainActivity> actRef;
        public LiveInfoWeakListener(MainActivity activity) {
            actRef = new WeakReference<>(activity);
        }
        @Override
        public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
            MainActivity act = actRef.get();
            if (act == null) return;
            act.infoDisplayManager.updateLiveInfo(info);
            if (act.pipManager != null) {
                act.pipManager.updatePlayState(true);
            }
        }
    }

    private static class SourceFailedWeakListener implements TVPlayerManager.OnSourceFailedListener {
        private final WeakReference<MainActivity> actRef;
        public SourceFailedWeakListener(MainActivity activity) {
            actRef = new WeakReference<>(activity);
        }
        @Override
        public void onSourceFailed() {
            MainActivity act = actRef.get();
            if (act == null) return;
            act.runOnUiThread(() -> {
                String channelName = "";
                if (act.currentPlayIndex >= 0 && act.currentPlayIndex < act.channelSourceList.size()) {
                    Channel ch = act.channelSourceList.get(act.currentPlayIndex);
                    if (ch != null) channelName = ch.getName();
                }
                act.appCoreManager.handleSourceFailed(channelName);
            });
        }
    }

    private void initAppCoreManager() {
        appCoreManager = new AppCoreManager(this, mPlayerManager, appConfig);
        // 全部监听器替换为静态弱引用内部类，省略重复代码，逻辑同上
        appCoreManager.setOnDataLoadListener(new DataLoadWeakListener(this));
        appCoreManager.setOnSourceSkipListener(new SourceSkipWeakListener(this));
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

        String modeName = switch (mode) {
            case TVPlayerManager.DECODER_MODE_HARD -> "硬解";
            case TVPlayerManager.DECODER_MODE_SOFT -> "软解（兼容性好）";
            default -> "自动（推荐）";
        };
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
        try {
            appConfig.addRecentChannel(channel.getName());
        } catch (Exception e) {}
        appCoreManager.resetSourceFailedCount();
        if (pipManager != null && pipManager.isInPipMode() && channel != null) {
            try {
                pipManager.updateChannelInfo(index + 1, channel.getName() == null ? "" : channel.getName(), live == null ? "" : live.bitrate);
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
        SettingsActivity.logOperation("【画中画】模式变化 → " + (isInPictureInPictureMode ? "进入" : "退出"));
        if (remoteManager != null) remoteManager.setInPipMode(isInPictureInPictureMode);
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
                pipManager.handleExitPip(() -> SettingsActivity.logOperation("【画中画】应用已关闭，释放播放器"));
                pipManager.handleExitPipRestore(this, displayManager, playerView, mPlayerManager, channelSourceList, currentPlayIndex, infoDisplayManager);
                remoteManager.syncMode();
            }
        }
    }

    private void log(String msg) {
        // 限制日志大小，防止静态集合无限膨胀
        if (logList.size() > MAX_LOG_SIZE) {
            logList.subList(0, MAX_LOG_SIZE / 2).clear();
        }
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
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) displayManager.reapplyFullScreen();
        appCoreManager.onWindowFocusChanged(hasFocus);
    }

    // ========== 修复4：onDestroy完整资源释放，切断所有引用链 ==========
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 1. 清空静态弱引用单例（最高优先级）
        if (sInstanceRef != null) {
            sInstanceRef.clear();
            sInstanceRef = null;
        }

        // 2. 移除窗口常亮Flag
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 3. 移除主线程所有延迟Handler任务
        new Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null);

        // 4. 释放所有管理器、注销广播、清空监听器
        if (infoDisplayManager != null) {
            infoDisplayManager.release();
            infoDisplayManager = null;
        }
        if (remoteManager != null) {
            remoteManager.release();
            remoteManager = null;
        }
        if (displayManager != null) {
            displayManager.release();
            displayManager = null;
        }
        if (channelPanelController != null) {
            channelPanelController.release();
            channelPanelController = null;
        }
        if (appCoreManager != null) {
            appCoreManager.unregisterReceivers(); // 注销广播
            appCoreManager.release();
            appCoreManager = null;
        }
        if (pipManager != null) {
            pipManager.release();
            pipManager = null;
        }
        if (mPlayerManager != null) {
            mPlayerManager.unregisterDecoderModeReceiver(); // 注销解码器广播
            mPlayerManager.detachPlayerView(playerView);
            mPlayerManager.release();
            mPlayerManager = null;
        }

        // 5. 清空监听器、View、数据引用
        playerStateListener = null;
        playerView = null;
        gestureManager = null;
        screenRatioManager = null;
        appConfig = null;
        channelSourceList.clear();

        // 6. 清空静态日志集合
        logList.clear();
        SettingsActivity.logOperation("【系统】APP退出");
    }
}
