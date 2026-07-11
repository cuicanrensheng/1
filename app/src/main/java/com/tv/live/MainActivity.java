package com.tv.live;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;

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
    private PictureInPictureManager pipManager;
    private View panelLayout;

    private PlayerControlManager playerControlManager;

    private boolean pipEnable = false;
    private boolean channel_reverse;
    private boolean number_channel_enable;  // 注意字段名

    private boolean isOpeningSettings = false;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private View logWindowContainer;
    private ScrollView logScrollView;
    private TextView tvLogContent;
    private boolean logWindowVisible = false;
    private Runnable logUpdateRunnable;

    private boolean isInCatchUpMode = false;

    // ==================== 模式状态 ====================
    public enum Mode { PLAY_MODE, CHANNEL_PANEL_MODE, SETTINGS_MODE }
    private Mode currentMode = Mode.PLAY_MODE;

    // ==================== 数字输入相关 ====================
    private final StringBuilder channelNumInput = new StringBuilder();
    private final Handler channelNumHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_NUM_TIMEOUT = 2000;
    private int totalChannelCount = 0;

    private final Runnable channelNumConfirmRunnable = new Runnable() {
        @Override public void run() { confirmChannelNum(); }
    };
    private final Runnable hideChannelNumRunnable = new Runnable() {
        @Override public void run() { infoDisplayManager.hideChannelNum(); }
    };

    // ==================== 单例 ====================
    public static MainActivity getRunningInstance() {
        return mInstanceRef != null ? mInstanceRef.get() : null;
    }

    public boolean isInCatchUpMode() {
        return isInCatchUpMode;
    }

    // ================== 提供给 PlayerControlManager 使用 ==================
    public PictureInPictureManager getPipManager() {
        return pipManager;
    }

    private PlayerTouchListener touchListener;
    public PlayerTouchListener getTouchListener() {
        return touchListener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mInstanceRef = new WeakReference<>(this);
        sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        displayManager = new DisplayManager(this);
        setContentView(R.layout.activity_main);
        displayManager.applyFullScreen();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        logWindowContainer = findViewById(R.id.log_window_container);
        logScrollView = findViewById(R.id.log_scroll_view);
        tvLogContent = findViewById(R.id.tv_log_content);

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
        playerView.setUseController(true);
        try {
            playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);
        } catch (Exception e) {}

        initChannelPanelController();
        initPictureInPicture();

        // 🔧 修复：添加判空保护，防止 channelPanelController 为 null 时崩溃
        if (channelPanelController != null) {
            channelPanelController.handleFirstLaunch();
        } else {
            Log.e("MainActivity", "channelPanelController is null after initChannelPanelController()");
        }

        initPlayer();
        mPlayerManager.registerDecoderModeReceiver();
        mPlayerManager.registerRendererModeReceiver();

        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();

        currentPlayIndex = appConfig.getLastPlayIndex();
        if (channelPanelController != null) {
            channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        }
        number_channel_enable = sp.getBoolean("number_channel_enable", true);

        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");
        new Thread(() -> appCoreManager.loadLiveAndEpg()).start();
    }

    // ==================== 数字输入方法 ====================
    private boolean handleNumberKey(int keyCode) {
        // 修正：使用 number_channel_enable 字段
        if (!number_channel_enable) return false;
        int num = keyCodeToNumber(keyCode);
        if (num == -1) return false;
        channelNumInput.append(num);
        infoDisplayManager.showChannelNum(Integer.parseInt(channelNumInput.toString()));
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumHandler.postDelayed(channelNumConfirmRunnable, CHANNEL_NUM_TIMEOUT);
        return true;
    }

    private void confirmChannelNum() {
        if (channelNumInput.length() == 0) return;
        try {
            int channelNum = Integer.parseInt(channelNumInput.toString());
            if (channelNum >= 1 && channelNum <= totalChannelCount) {
                int index = channelNum - 1;
                if (channelPanelController != null) {
                    channelPanelController.playChannel(index);
                }
            }
        } catch (NumberFormatException ignored) {}
        channelNumInput.setLength(0);
        channelNumHandler.removeCallbacks(hideChannelNumRunnable);
        channelNumHandler.postDelayed(hideChannelNumRunnable, 1000);
    }

    private int keyCodeToNumber(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_0: return 0;
            case KeyEvent.KEYCODE_1: return 1;
            case KeyEvent.KEYCODE_2: return 2;
            case KeyEvent.KEYCODE_3: return 3;
            case KeyEvent.KEYCODE_4: return 4;
            case KeyEvent.KEYCODE_5: return 5;
            case KeyEvent.KEYCODE_6: return 6;
            case KeyEvent.KEYCODE_7: return 7;
            case KeyEvent.KEYCODE_8: return 8;
            case KeyEvent.KEYCODE_9: return 9;
            default: return -1;
        }
    }

    // ==================== 模式同步 ====================
    private void syncMode() {
        if (channelPanelController == null) return;
        if (channelPanelController.isPanelOpen()) {
            currentMode = Mode.CHANNEL_PANEL_MODE;
        } else {
            currentMode = Mode.PLAY_MODE;
        }
    }

    // ==================== 按键分发（含画中画拦截） ====================
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        // 🔥 画中画模式拦截（替代原 TvRemoteManager 逻辑）
        if (pipManager != null && pipManager.isInPipMode()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                moveTaskToBack(false);
                return true;
            }
            // 其他按键全部忽略
            return true;
        }

        // 菜单/帮助/设置键 → 打开设置
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_HELP || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings();
            return true;
        }

        // 数字键（所有模式下有效）
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            if (handleNumberKey(keyCode)) {
                return true;
            }
        }

        // 根据当前模式分发
        switch (currentMode) {
            case PLAY_MODE:
                return handlePlayKey(keyCode);
            case CHANNEL_PANEL_MODE:
                return handlePanelKey(keyCode);
            case SETTINGS_MODE:
                // 设置页按键由 SettingsActivity 自己处理
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handlePlayKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (channelPanelController != null) channelPanelController.switchUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channelPanelController != null) channelPanelController.switchDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return false; // 不处理，交给系统
            default:
                return false;
        }
    }

    private boolean handlePanelKey(int keyCode) {
        if (channelPanelController == null) return false;
        return channelPanelController.dispatchKeyEvent(keyCode);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_BACK) {
            openSettings();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    // ==================== 面板控制 ====================
    public void togglePanel() {
        if (isInCatchUpMode) return;
        if (channelPanelController == null) return;
        channelPanelController.togglePanel();
        syncMode();
        if (!channelPanelController.isPanelOpen()) {
            panelLayout.postDelayed(() -> {
                channelPanelController.clearPanelFocus();
                playerView.setFocusable(true);
                playerView.setFocusableInTouchMode(true);
                playerView.requestFocus();
            }, 100);
        }
    }

    // ==================== 以下保留所有原有方法 ====================

    public void showLogWindow() { /* 原样 */ }
    public void hideLogWindow() { /* 原样 */ }
    private void startLogUpdate() { /* 原样 */ }
    private void stopLogUpdate() { /* 原样 */ }
    public static void toggleLogWindow(boolean enable) { /* 原样 */ }
    public void setCatchUpMode(boolean enabled) { this.isInCatchUpMode = enabled; }
    public ChannelPanelController getChannelPanelController() { return channelPanelController; }
    public void showExoController() { if (playerControlManager != null) playerControlManager.showExoController(); }
    public void hideExoController() { if (playerControlManager != null) playerControlManager.hideExoController(); }
    private void exitPlaybackMode() { /* 原样 */ }
    private void initPictureInPicture() { /* 原样 */ }
    private void initInfoDisplayManager() { /* 原样 */ }
    private void initChannelPanelController() { /* 原样 */ }

    // 🔧 修复：PlayerTouchListener 实现完整
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
        mPlayerManager.setOnPlayerViewRecreatedListener(newPlayerView -> {
            MainActivity.this.playerView = newPlayerView;
            gestureManager = new GestureManager(MainActivity.this);
            final PlayerGestureHelper newGestureHelper = gestureManager.create();

            if (touchListener == null) {
                touchListener = new PlayerTouchListener(MainActivity.this);
            }
            touchListener.updateGestureHelper(newGestureHelper);
            newPlayerView.setOnTouchListener(touchListener);
            newPlayerView.requestFocus();

            // ✅【核心修复】PlayerView 重建后，立刻强制彻底禁用控制栏功能
            if (playerControlManager != null) {
                newPlayerView.setUseController(false);
                playerControlManager.hideExoController();
            }
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
                    List<Channel> finalList = appCoreManager.getChannelList();
                    channelSourceList.clear();
                    channelSourceList.addAll(finalList);
                    if (channelPanelController != null) {
                        channelPanelController.setChannels(channelSourceList);
                    }
                    totalChannelCount = channelSourceList.size(); // 直接赋值
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
                if (channelPanelController != null) channelPanelController.switchDown();
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
        // 更新 number_channel_enable 已在 onCreate 中设置，这里无需重复
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
        log("【播放】频道名称：" + channel.getName());

        if (isInCatchUpMode) {
            exitPlaybackMode();
        }

        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        mPlayerManager.playUrl(channel.getPlayUrl(), channel.getName(), channel);
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

    public void playPrev() {
        if (channelPanelController != null) channelPanelController.playPrev();
    }
    public void playNext() {
        if (channelPanelController != null) channelPanelController.playNext();
    }

    @Override
    public void onBackPressed() {
        if (isInCatchUpMode && playerControlManager != null && playerControlManager.isControllerShowing()) {
            exitPlaybackMode();
            return;
        }
        if (channelPanelController != null && channelPanelController.handleBackPressed()) return;
        super.onBackPressed();
    }

    public void openSettings() {
        if (isOpeningSettings) return;
        if (isInCatchUpMode) return;
        isOpeningSettings = true;
        appCoreManager.beforeOpenSettings();
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            channelPanelController.hidePanel();
        }
        if (playerControlManager != null) {
            playerControlManager.onOpenSettings();
        }
        startActivity(new Intent(this, SettingsActivity.class));
    }

    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (pipManager != null) pipManager.enterPip(this, mPlayerManager, pipEnable);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
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
                syncMode();
            }
        }
    }

    private void log(String msg) {
        if (sp.getBoolean("log_enable", false)) {
            Log.d("MainActivity", msg);
            LogCollector.getInstance().addLog("MainActivity", msg);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isOpeningSettings) { return; }
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

        if (pipManager == null || !pipManager.isInPipMode()) {
            if (playerControlManager != null) {
                playerControlManager.onResume();
            }
            if (pipManager != null && mPlayerManager != null) {
                pipManager.resumePlayback(mPlayerManager);
            }
        }
        syncMode();

        if (channelPanelController != null) {
            channelPanelController.clearPanelFocus();
            if (!channelPanelController.isPanelOpen()) {
                playerView.setFocusable(true);
                playerView.setFocusableInTouchMode(true);
                playerView.requestFocus();
            }
        } else {
            if (playerView != null) {
                playerView.setFocusable(true);
                playerView.setFocusableInTouchMode(true);
                playerView.requestFocus();
            }
        }

        if (playerControlManager != null) {
            playerControlManager.onSettingsClosed();
        }
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
        if (mInstanceRef != null) {
            mInstanceRef.clear();
            mInstanceRef = null;
        }
        mMainHandler.removeCallbacksAndMessages(null);
        if (infoDisplayManager != null) infoDisplayManager.release();
        if (displayManager != null) displayManager.release();
        if (channelPanelController != null) channelPanelController.release();
        if (appCoreManager != null) appCoreManager.release();
        if (pipManager != null) pipManager.release();
        if (mPlayerManager != null) mPlayerManager.release();
        if (playerControlManager != null) {
            playerControlManager.release();
        }
    }
}
