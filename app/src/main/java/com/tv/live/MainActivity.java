package com.tv.live;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

// ====================================================================
// ✅ 2026-06-23 修改：升级到 Media3 1.10.1
// ====================================================================
// PlayerView 的包名从 com.google.android.exoplayer2.ui.PlayerView
// 改成 androidx.media3.ui.PlayerView
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
 * 主播放页面 Activity
 * 
 * 功能清单：
 * 1. 直播播放（基于 Media3 ExoPlayer 封装的 TVPlayerManager）
 * 2. 频道列表管理（分组、切换、收藏、最近观看）
 * 3. EPG 节目指南
 * 4. 画中画（PIP）后台小窗播放
 * 5. 遥控器/触控双模式交互
 * 6. 手势控制（上下滑动切台、左右滑动快进）
 * 7. 数字选台
 * 8. 屏幕比例调整
 * 9. 自动更新直播源
 * 
 * 画中画相关说明：
 * - 使用 PictureInPictureManager 统一管理画中画状态
 * - 所有画中画日志接入 SettingsActivity.logOperation，可在设置页面查看
 * - 退出画中画时记录详细尺寸日志，用于排查"返回播放界面变小窗"问题
 */
public class MainActivity extends AppCompatActivity {

    // ====================== 单例 ======================
    public static MainActivity mInstance;

    // ====================================================================
    // 兼容层：保留旧的 public 变量
    // ====================================================================
    public List<Channel> channelSourceList = new ArrayList<>();
    public int currentPlayIndex = 0;

    // ====================== 视图相关 ======================
    private PlayerView playerView;

    // ====================== 管理器相关 ======================
    public TVPlayerManager mPlayerManager;
    private AppConfig appConfig;
    private ScreenRatioManager screenRatioManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private PlayerStateListenerImpl playerStateListener;

    // ====================================================================
    // 拆分新增：各个 Manager
    // ====================================================================
    private ChannelNumberManager channelNumberManager;
    private DisplayManager displayManager;
    private InfoDisplayManager infoDisplayManager;
    private ChannelPanelController channelPanelController;
    private AppCoreManager appCoreManager;

    // ====================================================================
    // 遥控器统一管理器
    // ====================================================================
    private TvRemoteManager remoteManager;

    // ====================================================================
    // 画中画相关变量
    // ====================================================================
    private PictureInPictureManager pipManager;
    private boolean pipEnable = false;      // 画中画开关状态

    // ====================== 状态标志 ======================
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean isOpeningSettings = false;

    // ====================================================================
    // 频道面板自动隐藏
    // ====================================================================
    private Handler mPanelAutoHideHandler = new Handler(Looper.getMainLooper());
    private Runnable mPanelAutoHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (channelPanelController != null) {
                channelPanelController.hidePanel();
            }
        }
    };

    private boolean mIsFirstLaunch = true;

    // ====================== 其他 ======================
    public static List<String> logList = new ArrayList<>();

    // ====================== onCreate 生命周期 ======================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsActivity.logOperation("【主页】onCreate -> 页面创建");
        SettingsActivity.logOperation("【系统】APP启动");
        mInstance = this;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        displayManager = new DisplayManager(this);
        displayManager.applyFullScreen();
        setContentView(R.layout.activity_main);
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

        // ====================================================================
        // ✅ 2026-06-23 修改：修复 setControllerVisibilityListener 歧义问题
        // ====================================================================
        //
        // 【为什么会有歧义？】
        // Media3 的 PlayerView 有两个重载的 setControllerVisibilityListener 方法：
        // 1. setControllerVisibilityListener(ControllerVisibilityListener) - 新API
        // 2. setControllerVisibilityListener(VisibilityListener) - 旧API，已废弃
        //
        // 传 null 的时候，编译器不知道该调用哪个，所以报错：
        // "reference to setControllerVisibilityListener is ambiguous"
        //
        // 【修复方案】
        // 强制类型转换为 ControllerVisibilityListener（新API），
        // 这样编译器就知道该调用哪个重载方法了。
        //
        // 【为什么传 null？】
        // 因为我们用的是自定义的频道面板，不需要 PlayerView 自带的控制器，
        // 所以把控制器可见性监听器设为 null，避免不必要的回调。
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);

        initChannelPanelController();
        initRemoteManager();
        initPictureInPicture();
        if (mIsFirstLaunch) {
            mPanelAutoHideHandler.postDelayed(mPanelAutoHideRunnable, 3000);
            mIsFirstLaunch = false;
        }
        initPlayer();
        screenRatioManager = new ScreenRatioManager(mPlayerManager, appConfig);
        screenRatioManager.apply();
        gestureManager = new GestureManager(this);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });
        keyEventManager = new KeyEventManager(this);
        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        SettingsActivity.logOperation("【播放】记录上次播放索引：" + currentPlayIndex);
        initChannelNumberManager();
        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        }
        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();
    }

    // ====================================================================
    // 画中画初始化
    // ====================================================================
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
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持：" + pipManager.isPipSupported());
        } catch (Exception e) {
            log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
    }

    // ====================================================================
    // 画中画模式下隐藏所有UI
    // ====================================================================
    private void hideAllUiForPip() {
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            channelPanelController.hidePanel();
        }
        if (infoDisplayManager != null) {
            infoDisplayManager.hideInfoBar();
            infoDisplayManager.hideChannelNum();
        }
    }

    private void keepPlayingInPip() {
    try {
        if (mPlayerManager != null) {
            // 先尝试直接恢复
            mPlayerManager.resume();
            log("【画中画】✅ 调用 resume() 恢复播放");
            
            // 如果 resume 不行，再尝试重新绑定
            if (playerView != null) {
                mPlayerManager.attachPlayerView(playerView);
                mPlayerManager.resume();
                log("【画中画】✅ 重新绑定后再次恢复");
            }
        }
    } catch (Exception e) {
        log("【画中画】恢复播放失败：" + e.getMessage());
        
        // 最后兜底：重新播放当前频道
        try {
            if (channelSourceList != null 
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel channel = channelSourceList.get(currentPlayIndex);
                if (channel != null && channel.getPlayUrl() != null) {
                    mPlayerManager.playUrl(channel.getPlayUrl());
                    log("【画中画】兜底：重新加载当前频道");
                }
            }
        } catch (Exception
