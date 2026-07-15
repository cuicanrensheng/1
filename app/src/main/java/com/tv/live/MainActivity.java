package com.tv.live;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.ui.PlayerView;

import com.tv.live.Channel;
import com.tv.live.UrlConfig;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.*;
import com.tv.live.util.LogCollector;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

@SuppressLint("UnsafeOptInUsageError")
public class MainActivity extends AppCompatActivity {
    private static WeakReference<MainActivity> mInstanceRef;
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
    private View panelLayout;
    private PlayerControlManager playerControlManager;
    private boolean pipEnable = false;
    private boolean channel_reverse;
    private boolean number_channel_enable;
    public boolean isOpeningSettings = false;
    private long lastSettingsOpenTime = 0;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private View logWindowContainer;
    private ScrollView logScrollView;
    private TextView tvLogContent;
    private boolean logWindowVisible = false;
    private Runnable logUpdateRunnable;
    private boolean isInCatchUpMode = false;
    private AlertDialog exitMenuDialog = null;
    private BroadcastReceiver unlockReceiver;

    public static MainActivity getRunningInstance() {
        return mInstanceRef != null ? mInstanceRef.get() : null;
    }

    public boolean isInCatchUpMode() {
        return isInCatchUpMode;
    }

    public PictureInPictureManager getPipManager() {
        return pipManager;
    }

    private PlayerTouchListener touchListener;

    public PlayerTouchListener getTouchListener() {
        return touchListener;
    }

    public PlayerView getPlayerView() {
        return playerView;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstanceRef = new WeakReference<>(this);
        sp = getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        displayManager = new DisplayManager(this);
        setContentView(R.layout.activity_main);
        displayManager.applyFullScreen();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        logWindowContainer = findViewById(R.id.log_window_container);
        logScrollView = findViewById(R.id.log_scroll_view);
        tvLogContent = findViewById(R.id.tv_log_content);
        initInfoDisplayManager();
        appConfig = App.getInstance(this);
        String customLive = appConfig.getCustomLiveUrl();
        String customEpg = appConfig.getCustomEpgUrl();
        if (customLive != null) UrlConfig.LIVE_URL = customLive;
        if (customEpg != null) UrlConfig.EPG_URL = customEpg;
        log("【配置】直播源地址：" + UrlConfig.LIVE_URL);
        log("【配置】EPG地址：" + UrlConfig.EPG_URL);
        playerView = findViewById(R.id.player_view);
        playerView.setBackgroundColor(Color.BLACK);
        try {
            playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);
        } catch (Exception e) {
        }
        initChannelPanelController();
        initRemoteManager();
        initPictureInPicture();
        channelPanelController.handleFirstLaunch();
        initPlayer();
        loadSettings();
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");
        new Thread(() -> appCoreManager.loadLiveAndEpg()).start();
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.UNLOCK_SETTINGS".equals(intent.getAction())) {
                    isOpeningSettings = false;
                    Log.d("MainActivity", "📡 收到解锁广播，isOpeningSettings 已重置");
                }
            }
        };
        ContextCompat.registerReceiver(
                this,
                unlockReceiver,
                new IntentFilter("com.tv.live.UNLOCK_SETTINGS"),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    public void showLogWindow() {
        if (logWindowVisible) return;
        logWindowVisible = true;
        logWindowContainer.setVisibility(View.VISIBLE);
        startLogUpdate();
    }

    public void hideLogWindow() {
        if (!logWindowVisible) return;
        logWindowVisible = false;
        logWindowContainer.setVisibility(View.GONE);
        stopLogUpdate();
    }

    private void startLogUpdate() {
        if (logUpdateRunnable != null) return;
        logUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!logWindowVisible) {
                    stopLogUpdate();
                    return;
                }
                String logs = LogCollector.getInstance().getAllLogs();
                tvLogContent.setText(logs);
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
                mMainHandler.postDelayed(this, 300);
            }
        };
        mMainHandler.post(logUpdateRunnable);
    }

    private void stopLogUpdate() {
        if (logUpdateRunnable != null) {
            mMainHandler.removeCallbacks(logUpdateRunnable);
            logUpdateRunnable = null;
        }
    }

    public static void toggleLogWindow(boolean enable) {
        MainActivity activity = getRunningInstance();
        if (activity != null) {
            if (enable) {
                activity.showLogWindow();
            } else {
                activity.hideLogWindow();
            }
        }
    }

    public void setCatchUpMode(boolean enabled) {
        this.isInCatchUpMode = enabled;
    }

    public ChannelPanelController getChannelPanelController() {
        return channelPanelController;
    }

    public void showExoController() {
        if (playerControlManager != null) {
            playerControlManager.showExoController();
        }
    }

    public void hideExoController() {
        if (playerControlManager != null) {
            playerControlManager.hideExoController();
        }
    }

    private void exitPlaybackMode() {
        if (isInCatchUpMode) {
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel ch = channelSourceList.get(currentPlayIndex);
                if (ch != null && mPlayerManager != null) {
                    mPlayerManager.playUrl(ch.getPlayUrl(), ch.getName(), ch);
                    TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
                    if (infoDisplayManager != null && live != null) {
                        infoDisplayManager.showInfoBar(ch, live);
                    }
                }
            }
            hideExoController();
            isInCatchUpMode = false;
        } else {
            if (playerControlManager != null && playerControlManager.isControllerShowing()) {
                hideExoController();
            }
        }
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
        remoteManager.setOnRemoteActionListener(new TvRemoteManager.OnRemoteActionListener() {
            @Override
            public void onPlayChannelUp() {
                exitPlaybackMode();
                channelPanelController.switchUp();
            }

            @Override
            public void onPlayChannelDown() {
                exitPlaybackMode();
                channelPanelController.switchDown();
            }

            @Override
            public void onPlayTogglePanel() {
                togglePanel();
            }

            @Override
            public void onPlayOpenSettings() {
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
                return handled;
            }

            @Override
            public void onPanelMenu() {
                channelPanelController.toggleCurrentFavorite();
            }

            @Override
            public void onPanelNumber(int number) {
            }

            @Override
            public void onPanelFocusChanged(TvRemoteManager.PanelFocus newFocus) {
            }

            @Override
            public void onSettingsMoveUp() {
            }

            @Override
            public void onSettingsMoveDown() {
            }

            @Override
            public void onSettingsConfirm() {
            }

            @Override
            public boolean onSettingsBack() {
                return false;
            }

            @Override
            public void onSettingsMenu() {
            }

            @Override
            public void onSettingsFocusChanged(int position) {
            }
        });
        remoteManager.setSettingsItemCount(0);
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
        infoDisplayManager.setOnChannelNumberSelectedListener(channelIndex -> {
            if (channelPanelController != null) {
                channelPanelController.playChannel(channelIndex);
            }
        });
    }

    private void initChannelPanelController() {
        panelLayout = findViewById(R.id.panel_layout);
        View ll_left_panel = findViewById(R.id.ll_left_panel);
        View ll_right_panel = findViewById(R.id.ll_right_panel);
        ListView lvGroup = findViewById(R.id.lv_group);
        ListView lvChannelList = findViewById(R.id.lv_channel_list);
        ListView lvChannelListEpg = findViewById(R.id.lv_channel_list_epg);
        ListView lvDate = findViewById(R.id.lv_date);
        ListView lvEpg = findViewById(R.id.lv_epg);
        TextView btnShowEpg = findViewById(R.id.btn_show_epg);
        TextView btnBackGroup = findViewById(R.id.btn_back_group);
        EpgManager.getInstance(this);
        ChannelListManager channelListManager = new ChannelListManager(this, lvChannelList);
        ChannelListManager channelListManagerEpg = new ChannelListManager(this, lvChannelListEpg);
        GroupListManager groupListManager = new GroupListManager(this, lvGroup);
        DateListManager dateListManager = new DateListManager(this, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(this, lvEpg);
        PanelManager panelManager = new PanelManager(panelLayout, channelListManager, epgManagerWrapper);
        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> channelPanelController.setCurrentDateIndex(pos));
        channelPanelController = new ChannelPanelController(
                this, panelLayout, ll_left_panel, ll_right_panel, lvGroup, lvChannelList,
                lvChannelListEpg, lvDate, lvEpg, btnShowEpg, btnBackGroup,
                groupListManager, channelListManager, channelListManagerEpg, dateListManager, epgManagerWrapper, panelManager
        );
        channelPanelController.setOnChannelChangeListener((channel, index) -> playChannel(channel, index));
    }

    public static class PlayerTouchListener implements View.OnTouchListener {
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

    private void initPlayer() {
        mPlayerManager = TVPlayerManager.getInstance(this);
        gestureManager = new GestureManager(this);
        playerControlManager = new PlayerControlManager(this, gestureManager, infoDisplayManager);
        mPlayerManager.setOnPlayerViewRecreatedListener(newPlayerView -> {
            MainActivity.this.playerView = newPlayerView;
            gestureManager = new GestureManager(this);
            final PlayerGestureHelper newGestureHelper = gestureManager.create();
            if (playerControlManager != null) {
                playerControlManager.updateGestureManager(gestureManager);
            }
            if (touchListener == null) {
                touchListener = new PlayerTouchListener(MainActivity.this);
            }
            touchListener.updateGestureHelper(newGestureHelper);
            newPlayerView.setOnTouchListener(touchListener);
            newPlayerView.requestFocus();
            if (playerControlManager != null) {
                newPlayerView.setUseController(false);
                playerControlManager.hideExoController();
            }
            newPlayerView.setFocusable(true);
            newPlayerView.setFocusableInTouchMode(true);
            newPlayerView.requestFocus();
            Log.d("MainActivity", "PlayerView 重建完成，焦点已强制恢复");
        });
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
                    synchronized (channelSourceList) {
                        channelSourceList.clear();
                        channelSourceList.addAll(channels);
                    }
                    channelPanelController.setChannels(channelSourceList);
                    if (channelPanelController != null) {
                        String currentGroup = "";
                        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                            Channel ch = channelSourceList.get(currentPlayIndex);
                            if (ch != null) currentGroup = ch.getGroup();
                        }
                        if (currentGroup != null && !currentGroup.isEmpty()) {
                            channelPanelController.playChannel(currentPlayIndex);
                        }
                    }
                    if (currentPlayIndex >= channelSourceList.size()) {
                        currentPlayIndex = 0;
                        Log.d("MainActivity", "currentPlayIndex 越界，已自动重置为 0");
                    }
                    appCoreManager.setHasPlayedWithCache(true);
                    if (!appCoreManager.hasPlayedWithCache()) {
                        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                            Channel ch = channelSourceList.get(currentPlayIndex);
                            playChannel(ch, currentPlayIndex);
                            appCoreManager.setHasPlayedWithCache(true);
                        }
                    }
                    displayManager.hideLoading();
                    log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channelSourceList.size());
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
            public void onNeedSkipChannel() {
                channelPanelController.switchDown();
            }

            @Override
            public void onSkipLimitReached(int maxSkip) {
                Toast.makeText(MainActivity.this, "已跳过 " + maxSkip + " 个失效频道，请检查直播源", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onSourceFailed(String channelName, int failedCount) {
            }
        });
        appCoreManager.registerReceivers();
    }

    private void loadSettings() {
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);
        pipEnable = sp.getBoolean("pip_enable", false);
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epg_enable);
            channelPanelController.setReverse(channel_reverse);
        }
        if (pipManager != null) pipManager.setPipEnabled(pipEnable);
    }

    public boolean isChannelReverse() {
        return channel_reverse;
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    private void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;
        log("【播放】频道名称：" + channel.getName());
        if (isInCatchUpMode) {
            exitPlaybackMode();
        }
        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        mPlayerManager.playUrl(channel.getPlayUrl(), channel.getName(), channel);
        TVPlayerManager.LiveInfo live = mPlayerManager.getLiveInfo();
        if (infoDisplayManager != null) {
            infoDisplayManager.showInfoBar(channel, live);
            infoDisplayManager.showChannelNum(index + 1);
        } else {
            Log.e("MainActivity", "infoDisplayManager is null, cannot show info bar");
        }
        try {
            appConfig.addRecentChannel(channel.getName());
        } catch (Exception ignored) {
        }
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
        if (isInCatchUpMode) {
            return;
        }
        channelPanelController.togglePanel();
        if (channelPanelController != null) {
            if (channelPanelController.isPanelOpen()) {
                remoteManager.setMode(TvRemoteManager.Mode.CHANNEL_PANEL_MODE);
                remoteManager.setRightPanelOpen(channelPanelController.isRightPanelOpen());
            } else {
                remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
            }
        }
    }

    public void playPrev() {
        channelPanelController.playPrev();
    }

    public void playNext() {
        channelPanelController.playNext();
    }

    public void showExitMenu() {
        if (exitMenuDialog != null && exitMenuDialog.isShowing()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_exit_menu, null);
        builder.setView(view);
        Button btnRest = view.findViewById(R.id.btn_rest);
        Button btnSettings = view.findViewById(R.id.btn_settings);
        exitMenuDialog = builder.create();
        if (exitMenuDialog != null) {
            exitMenuDialog.setOnShowListener(dialog -> {
                btnRest.requestFocus();
            });
            if (exitMenuDialog.getWindow() != null) {
                exitMenuDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                WindowManager.LayoutParams lp = exitMenuDialog.getAttributes();
                lp.dimAmount = 0.5f;
                exitMenuDialog.getWindow().setAttributes(lp);
                exitMenuDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            exitMenuDialog.setOnDismissListener(dialog -> exitMenuDialog = null);
            exitMenuDialog.show();
        }
        btnRest.setOnClickListener(v -> {
            if (exitMenuDialog != null) {
                exitMenuDialog.dismiss();
            }
            finishAffinity();
        });
        btnSettings.setOnClickListener(v -> {
            if (exitMenuDialog != null) {
                exitMenuDialog.dismiss();
            }
            isOpeningSettings = true;
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMainHandler.removeCallbacksAndMessages(null);
        //跳转设置页面，视频音频保持播放，完全不暂停
        if (isOpeningSettings) {
            return;
        }
        //仅退至后台桌面才暂停，画中画模式继续播放
        if ((pipManager == null || !pipManager.isInPipMode()) && mPlayerManager != null) {
            mPlayerManager.pause();
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
        displayManager.reapplyFull();

        if (mPlayerManager != null && playerView != null) {
            mPlayerManager.attachPlayerView(playerView);
            String decoderModeStr = sp.getString("decoder_mode", "auto");
            int mode = TVPlayerManager.DECODER_MODE_AUTO;
            if ("hard".equals(decoderModeStr)) mode = TVPlayerManager.DECODER_MODE_HARD;
            else if ("soft".equals(decoderModeStr)) mode = TVPlayerManager.DECODER_MODE_SOFT;
            mPlayerManager.setDecoderMode(mode);

            String renderType = sp.getString("renderer_type", "surface");
            mPlayerManager.switchRenderer("texture".equals(renderType));
            mPlayerManager.resume();
        }

        if (playerControlManager != null) {
            playerControlManager.onResume();
        }
        if (channelPanelController != null) {
            channelPanelController.clearPanelFocus();
            if (!channelPanelController.isPanelOpen() && playerView != null) {
                playerView.setFocusable(true);
                playerView.setFocusableInTouchMode(true);
                playerView.requestFocus();
            }
        }
        if (playerControlManager != null) {
            playerControlManager.onSettingsClosed();
        }
    }

    private void log(String msg) {
        if (sp.getBoolean("log_enable", false)) {
            Log.d("MainActivity", msg);
            com.tv.live.util.LogCollector.getInstance().addLog("MainActivity", msg);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mMainHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(unlockReceiver);
        if (channelPanelController != null) {
            channelPanelController.release();
        }
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }
        mInstanceRef.clear();
    }
}
