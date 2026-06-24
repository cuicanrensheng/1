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
import android.widget.FrameLayout;
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
 * 
 * 【2026-06-23 新增：画中画与全面屏冲突解决方案】
 * 方案一 + 方案四 组合使用：
 * - 方案一：进入画中画前临时关闭全面屏，退出后延迟恢复
 * - 方案四：给 PlayerView 套一层父容器，监听尺寸变化主动刷新
 * 双重保险，彻底解决画中画和全面屏的冲突问题。
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

    // ====================================================================
    // ✅ 2026-06-23 新增：PlayerView 父容器（方案四）
    // ====================================================================
    /**
     * PlayerView 的父容器
     * 
     * 【作用】
     * 监听父容器的尺寸变化，当尺寸变化时（比如画中画模式切换），
     * 主动触发 PlayerView 重新布局，确保播放器尺寸正确。
     * 
     * 【为什么需要？】
     * 画中画模式变化时，系统会改变窗口尺寸，然后重新布局。
     * 但有时候 PlayerView（尤其是内部的 SurfaceView）会"慢半拍"，
     * 导致尺寸不同步。
     * 
     * 【和方案一的关系】
     * 方案一从系统层面避免冲突，方案四从布局层面兜底。
     * 两者组合，双重保险。
     */
    private FrameLayout playerContainer;

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

        // ====================================================================
        // ✅ 2026-06-23 新增：父容器布局监听（方案四）
        // ====================================================================
        // 【作用】
        // 监听父容器的尺寸变化，当尺寸变化时（比如画中画模式切换），
        // 主动触发 PlayerView 重新布局，确保播放器尺寸正确。
        //
        // 【为什么需要？】
        // 画中画模式变化时，系统会改变窗口尺寸，然后重新布局。
        // 但有时候 PlayerView（尤其是内部的 SurfaceView）会"慢半拍"，
        // 导致尺寸不同步。
        //
        // 【和方案一的关系】
        // 方案一从系统层面避免冲突，方案四从布局层面兜底。
        // 两者组合，双重保险。
        // ====================================================================
        playerContainer = findViewById(R.id.player_container);
        if (playerContainer != null) {
            playerContainer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, 
                        int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    
                    // 计算尺寸变化量
                    int widthChanged = (right - left) - (oldRight - oldLeft);
                    int heightChanged = (bottom - top) - (oldBottom - oldTop);
                    
                    // 只有尺寸真的变了才处理（避免不必要的刷新）
                    if (widthChanged != 0 || heightChanged != 0) {
                        log("【画中画】父容器尺寸变化：" 
                            + (oldRight - oldLeft) + "×" + (oldBottom - oldTop)
                            + " → " 
                            + (right - left) + "×" + (bottom - top));
                        
                        // 父容器尺寸变化时，主动刷新 PlayerView
                        refreshPlayerViewLayout();
                    }
                }
            });
            log("【画中画】✅ 父容器布局监听已设置（方案四）");
        }

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
    // ✅ 2026-06-23 新增：刷新 PlayerView 布局（方案四）
    // ====================================================================
    /**
     * 刷新 PlayerView 布局（三重刷新机制）
     * 
     * 【调用场景】
     * 1. 父容器尺寸变化时（画中画模式切换）
     * 2. 退出画中画后
     * 3. 从后台切回前台时
     * 
     * 【三重刷新机制】
     * 1. 立即 requestLayout + invalidate
     * 2. 延迟 100ms 再刷新一次（等 Surface 准备好）
     * 3. 延迟 200ms 重新绑定播放器（确保 Surface 尺寸同步）
     * 
     * 【为什么需要三重？】
     * SurfaceView 的 Surface 创建和尺寸变化是异步的，
     * 一次刷新可能不够，三重刷新确保万无一失。
     * 
     * 【和方案一的关系】
     * 方案一从系统层面避免冲突，方案四从布局层面兜底。
     * 两者组合，双重保险。
     */
    private void refreshPlayerViewLayout() {
        if (playerView == null) return;
        
        log("【画中画】开始刷新 PlayerView 布局");
        
        // ================================================================
        // 第一重：立即刷新
        // ================================================================
        try {
            playerView.requestLayout();
            playerView.invalidate();
            log("【画中画】第一重：立即 requestLayout + invalidate");
        } catch (Exception e) {
            log("【画中画】立即刷新失败：" + e.getMessage());
        }
        
        // ================================================================
        // 第二重：延迟 100ms 再刷新
        // ================================================================
        // 为什么延迟 100ms？
        // SurfaceView 的 Surface 创建和尺寸变化是异步的，
        // 立即 requestLayout 可能 Surface 还没准备好。
        // 延迟 100ms 再刷一次，确保 Surface 已经更新。
        playerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (playerView == null) return;
                try {
                    playerView.requestLayout();
                    playerView.invalidate();
                    log("【画中画】第二重：延迟100ms刷新");
                    
                    // 打印当前尺寸，方便调试
                    log("【画中画】当前尺寸：" 
                        + playerView.getWidth() + "×" + playerView.getHeight());
                    
                } catch (Exception e) {
                    log("【画中画】延迟刷新失败：" + e.getMessage());
                }
            }
        }, 100);
        
        // ================================================================
        // 第三重：延迟 200ms + 重新绑定播放器
        // ================================================================
        // 为什么要重新绑定？
        // 有时候 Surface 尺寸变了，但播放器不知道，
        // 重新 attachPlayerView 可以让播放器重新关联 Surface，
        // 确保渲染尺寸正确。
        playerView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (playerView == null || mPlayerManager == null) return;
                try {
                    mPlayerManager.attachPlayerView(playerView);
                    log("【画中画】第三重：重新绑定播放器");
                    
                    // 打印最终尺寸
                    log("【画中画】最终尺寸：" 
                        + playerView.getWidth() + "×" + playerView.getHeight());
                    
                } catch (Exception e) {
                    log("【画中画】重新绑定失败：" + e.getMessage());
                }
            }
        }, 200);
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
                    } catch (Exception e2) {
            log("【画中画】兜底播放也失败：" + e2.getMessage());
        }
    }
}

    // ====================================================================
    // 恢复当前频道播放
    // ====================================================================
    private void resumeCurrentChannel() {
        try {
            if (mPlayerManager != null) {
                mPlayerManager.resume();
            }
        } catch (Exception e) {
            log("【画中画】恢复播放失败：" + e.getMessage());
        }
    }

    // ====================================================================
    // 初始化遥控器管理器
    // ====================================================================
    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
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
                syncRemoteMode();
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
                    syncRemoteMode();
                }
                return handled;
            }
            @Override
            public void onPanelMenu() {
                boolean isFavorite = channelPanelController.toggleCurrentFavorite();
                SettingsActivity.logOperation("【遥控】菜单键 → "
                        + (isFavorite ? "已添加收藏" : "已取消收藏"));
            }
            @Override
            public void onPanelNumber(int number) {
                int keyCode = KeyEvent.KEYCODE_0 + number;
                channelNumberManager.handleNumberKey(keyCode);
            }
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
        });
    }

    // ====================================================================
    // 同步遥控器模式
    // ====================================================================
    private void syncRemoteMode() {
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            remoteManager.setMode(TvRemoteManager.Mode.CHANNEL_PANEL_MODE);
            remoteManager.setRightPanelOpen(channelPanelController.isRightPanelOpen());
        } else {
            remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        }
    }

    // ====================================================================
    // 信息展示管理器初始化
    // ====================================================================
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

    // ====================================================================
    // 频道面板控制器初始化
    // ====================================================================
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

    // ====================================================================
    // 播放器初始化
    // ====================================================================
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
    }

    // ====================================================================
    // 数字选台管理器初始化
    // ====================================================================
    private void initChannelNumberManager() {
        channelNumberManager = new ChannelNumberManager(
                new ChannelNumberManager.OnChannelNumberListener() {
                    @Override
                    public void onChannelSelected(int channelIndex) {
                        channelPanelController.playChannel(channelIndex);
                    }
                    @Override
                    public void showChannelNumber(String number) {
                        try {
                            infoDisplayManager.showChannelNum(Integer.parseInt(number));
                        } catch (Exception e) {
                        }
                    }
                    @Override
                    public void hideChannelNumber() {
                        infoDisplayManager.hideChannelNum();
                    }
                },
                number_channel_enable
        );
    }

    // ====================================================================
    // 应用核心管理器初始化
    // ====================================================================
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
        appCoreManager.registerReceivers();
    }

    // ====================== 设置加载 ======================
    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean epg_enable = sp.getBoolean("epg_enable", true);
        channel_reverse = sp.getBoolean("channel_reverse", false);
        number_channel_enable = sp.getBoolean("number_channel_enable", true);
        boolean auto_update_source = sp.getBoolean("auto_update_source", true);
        pipEnable = sp.getBoolean("pip_enable", false);

        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
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

    // ====================================================================
    // 获取反转状态
    // ====================================================================
    public boolean isChannelReverse() {
        return channel_reverse;
    }

    // ====================================================================
    // 兼容层：旧的 playChannel(int) 方法
    // ====================================================================
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    // ====================== 播放频道（内部方法） ======================
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
        } catch (Exception e) {
        }

        // ✅ 画中画模式下同步频道信息到管理器
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

    // ====================================================================
    // 兼容层：旧的 togglePanel() 方法
    // ====================================================================
    public void togglePanel() {
        channelPanelController.togglePanel();
        syncRemoteMode();
    }

    // ====================================================================
    // 兼容层：旧的 playPrev() 方法
    // ====================================================================
    public void playPrev() {
        channelPanelController.playPrev();
    }

    // ====================================================================
    // 兼容层：旧的 playNext() 方法
    // ====================================================================
    public void playNext() {
        channelPanelController.playNext();
    }

    // ====================== 返回键处理 ======================
    @Override
    public void onBackPressed() {
        // ✅ 画中画模式下按返回键：退到后台
        if (pipManager != null && pipManager.isInPipMode()) {
            moveTaskToBack(false);
            return;
        }

        if (channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            return;
        }

        if (remoteManager != null) {
            if (remoteManager.dispatchKeyEvent(KeyEvent.KEYCODE_BACK)) {
                return;
            }
        }

        if (channelPanelController.handleBackPressed()) {
            playerView.requestFocus();
            syncRemoteMode();
            return;
        }

        super.onBackPressed();
    }

    // ====================== 方向键处理（保留，备用） ======================
    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                SettingsActivity.logOperation("【按键】handleDirectionKey 上键 → 反转状态："
                        + (channel_reverse ? "开启" : "关闭"));
                channelPanelController.switchUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                SettingsActivity.logOperation("【按键】handleDirectionKey 下键 → 反转状态："
                        + (channel_reverse ? "开启" : "关闭"));
                channelPanelController.switchDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (channelNumberManager.isInputting()) {
                    channelNumberManager.confirmChannelNum();
                    return true;
                }
                togglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                togglePanel();
                return true;
            default:
                return false;
        }
    }

    // ====================== 按键分发 ======================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ✅ 画中画模式下：只处理返回键，其他按键交给系统
        if (pipManager != null && pipManager.isInPipMode()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                moveTaskToBack(false);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        cancelPanelAutoHide();

        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }

        if (channelNumberManager.handleNumberKey(keyCode)) return true;

        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        if (handleDirectionKey(keyCode)) return true;

        if (keyEventManager.dispatchKey(keyCode)) return true;

        return super.onKeyDown(keyCode, event);
    }

    // ====================================================================
    // 取消频道面板自动隐藏
    // ====================================================================
    private void cancelPanelAutoHide() {
        if (mPanelAutoHideHandler != null && mPanelAutoHideRunnable != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        }
    }

    // ====================== 打开设置页面 ======================
    public void openSettings() {
        isOpeningSettings = true;
        appCoreManager.beforeOpenSettings();
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ====================== 接收远程配置 ======================
    public void onReceiveConfig(final String liveUrl, final String epgUrl) {
        appCoreManager.onReceiveConfig(liveUrl, epgUrl);
    }

    // ====================================================================
    // ✅ 画中画：用户按 Home 键时自动进入画中画（集成版）
    // 
    // 触发时机：用户按 Home 键、最近任务键、来电等
    // 判断逻辑：
    // 1. 打开设置页面时不进入
    // 2. pipManager 不为 null
    // 3. 调用 pipManager.shouldEnterPip() 统一判断所有条件
    // 
    // 【2026-06-23 修改】
    // 新增方案一：进入画中画前临时关闭全面屏，避免冲突。
    // ====================================================================
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        SettingsActivity.logOperation("【画中画排查】========== 开始 ==========");
        SettingsActivity.logOperation("【画中画排查】onUserLeaveHint 被调用");

        // 打开设置页面时不进入画中画（避免从主页面进入设置时触发）
        if (isOpeningSettings) {
            SettingsActivity.logOperation("【画中画排查】打开设置页面，跳过");
            SettingsActivity.logOperation("【画中画排查】========== 结束 ==========");
            return;
        }

        if (pipManager == null) {
            SettingsActivity.logOperation("【画中画排查】❌ pipManager 为 null");
            SettingsActivity.logOperation("【画中画排查】========== 结束 ==========");
            return;
        }

        // ✅ 使用 PictureInPictureManager 的统一判断
        boolean shouldEnter = pipManager.shouldEnterPip();

        // 输出详细状态（用于排查）
        SettingsActivity.logOperation("【画中画排查】MainActivity开关状态：" + pipEnable);
        SettingsActivity.logOperation("【画中画排查】设备支持：" + pipManager.isPipSupported());
        SettingsActivity.logOperation("【画中画排查】PIP管理器开关：" + pipManager.isPipEnabled());
        SettingsActivity.logOperation("【画中画排查】已在画中画模式：" + pipManager.isInPipMode());
        SettingsActivity.logOperation("【画中画排查】正在进入画中画：" + pipManager.isPipEntering());

        if (shouldEnter) {
            SettingsActivity.logOperation("【画中画排查】所有条件满足，尝试进入画中画...");
            try {
                // ================================================================
                // ✅ 方案一：进入画中画前，先临时关闭全面屏
                // ================================================================
                // 【为什么要在这里关？】
                // 必须在调用 enterPictureInPictureMode() 之前关闭，
                // 这样系统计算画中画窗口尺寸时，用的是默认的布局方式，
                // 不会和全面屏适配冲突。
                //
                // 【冲突表现】
                // 如果不关全面屏直接进入画中画，可能会出现：
                // 1. 画中画小窗黑屏
                // 2. 退出画中画后播放器尺寸不对
                // 3. 退出画中画后全面屏失效
                //
                // 【和 restoreFullScreenAfterPip 的关系】
                // - disableFullScreenForPip()：进入画中画前调用，关闭全面屏
                // - restoreFullScreenAfterPip()：退出画中画后调用，恢复全面屏
                // 两者配对使用。
                if (displayManager != null) {
                    displayManager.disableFullScreenForPip();
                    SettingsActivity.logOperation("【画中画排查】✅ 已临时关闭全面屏（方案一）");
                }

                // 构建画中画参数（16:9 比例，符合视频播放比例）
                PictureInPictureParams pipParams = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
                    pipBuilder.setAspectRatio(new Rational(16, 9));
                    pipParams = pipBuilder.build();
                }

                // 同步播放状态到管理器
                if (mPlayerManager != null) {
                    pipManager.updatePlayState(true);
                }

                // ✅ 调用管理器进入画中画
                boolean result = pipManager.enterPictureInPicture(this, pipParams);
                SettingsActivity.logOperation("【画中画排查】进入结果：" + (result ? "✅ 成功" : "❌ 失败"));

            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画排查】❌ 异常：" + e.getMessage());
                e.printStackTrace();
            }
        } else {
            SettingsActivity.logOperation("【画中画排查】❌ 条件不满足，不进入画中画");
        }

        SettingsActivity.logOperation("【画中画排查】========== 结束 ==========");
    }

    // ====================================================================
    // ✅ 画中画模式变化回调（集成版 + 详细尺寸日志 + 方案一+四）
    // 
    // 作用：系统回调画中画模式变化时，更新UI和播放状态
    // 
    // 进入画中画时：
    // 1. 隐藏所有UI（频道面板、信息栏）
    // 2. 保持屏幕常亮
    // 3. 确保播放器在播放
    // 
    // 退出画中画时：
    // 1. 调用 pipManager.handleExitPip() 处理退出逻辑
    // 2. ✅ 方案一：恢复全面屏（延迟 200ms）
    // 3. ✅ 方案四：父布局监听会自动触发刷新
    // 4. ✅ 手动再刷一次（双重保险）
    // 5. 同步遥控器模式
    // 6. 恢复信息栏显示
    // 7. 恢复播放
    // 
    // 尺寸日志说明：
    // - 记录4个时间点的尺寸变化，用于排查"返回播放界面变小窗"问题
    // - 日志点1：刚退出画中画时的初始尺寸
    // - 日志点2：reapplyFullScreen 后的尺寸
    // - 日志点3：立即 requestLayout 后的尺寸
    // - 日志点4：延迟200ms刷新 + 重新绑定后的尺寸
    // ====================================================================
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        SettingsActivity.logOperation("【画中画】模式变化 → " + (isInPictureInPictureMode ? "进入" : "退出"));

        // ✅ 通知管理器状态变化
        if (pipManager != null) {
            try {
                pipManager.onPipModeChanged(this, isInPictureInPictureMode);
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画】模式变化回调失败：" + e.getMessage());
            }
        }

        if (isInPictureInPictureMode) {
            // ================================================================
            // 进入画中画
            // ================================================================
            SettingsActivity.logOperation("【画中画】========== 进入画中画 ==========");

            // 隐藏所有UI，避免小窗显示多余内容
            hideAllUiForPip();

            // 保持屏幕常亮
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            // 确保播放器在播放（防止 onPause 暂停了）
            if (mPlayerManager != null) {
                try {
                    mPlayerManager.resume();
                    SettingsActivity.logOperation("【画中画】✅ 恢复播放");
                } catch (Exception e) {
                    SettingsActivity.logOperation("【画中画】恢复播放失败：" + e.getMessage());
                }
            }

            // 记录进入画中画时的尺寸
            logPipViewSize("进入画中画时", playerView);
            
            // 【方案四】父布局监听会自动触发刷新
            // （不需要手动调用，因为窗口尺寸变化会触发父布局的 onLayoutChange）

            SettingsActivity.logOperation("【画中画】================================");

        } else {
            // ================================================================
            // ✅ 退出画中画：方案一 + 方案四 组合（双重保险）
            // ================================================================
            SettingsActivity.logOperation("【画中画】========== 退出画中画 ==========");

            // ✅ 使用管理器处理退出逻辑（判断是否需要释放播放器）
            if (pipManager != null) {
                pipManager.handleExitPip(new Runnable() {
                    @Override
                    public void run() {
                        // 用户关闭应用时释放播放器（当前场景一般不需要，保留作为扩展）
                        SettingsActivity.logOperation("【画中画】应用已关闭，释放播放器");
                    }
                });
            }

            // ================================================================
            // 📊 日志点1：刚退出画中画时的初始尺寸
            // 作用：记录系统刚回调退出时，PlayerView 还是小窗尺寸
            // ================================================================
            SettingsActivity.logOperation("【画中画尺寸】===== 1. 刚退出画中画（初始状态） =====");
            logPipViewSize("PlayerView", playerView);
            
            // 打印父布局尺寸（对比父布局是否正常）
            if (playerView != null && playerView.getParent() instanceof View) {
                logPipViewSize("父布局", (View) playerView.getParent());
            }
            
            // 打印窗口和屏幕尺寸（作为参考基准）
            logPipWindowSize();

            // ================================================================
            // ✅ 方案一：恢复全面屏（延迟 200ms）
            // ================================================================
            // 【为什么延迟？】
            // 退出画中画后，系统需要一点时间来恢复窗口布局。
            // 如果立即调用 applyFullScreen()，可能会和系统的布局计算冲突。
            // 所以延迟 200ms 再恢复，等系统布局稳定后再应用。
            //
            // 【和方案四的关系】
            // 方案一从系统层面恢复全面屏状态，
            // 方案四从布局层面确保 PlayerView 尺寸正确。
            // 两者组合，双重保险。
            if (displayManager != null) {
                SettingsActivity.logOperation("【画中画尺寸】执行 restoreFullScreenAfterPip()（方案一）");
                displayManager.restoreFullScreenAfterPip();
            }

            // ================================================================
            // 📊 日志点2：reapplyFullScreen 后的尺寸
            // 作用：检查全屏设置是否生效
            // ================================================================
            SettingsActivity.logOperation("【画中画尺寸】===== 2. 恢复全面屏后 =====");
            logPipViewSize("PlayerView", playerView);

            // ================================================================
            // ✅ 方案四：手动再刷一次（双重保险）
            // ================================================================
            // 【为什么还要手动刷？】
            // 虽然父布局监听会自动触发刷新，
            // 但为了保险起见，还是手动再调用一次 refreshPlayerViewLayout()。
            //
            // 【延迟 300ms 的原因】
            // 等全面屏恢复后再刷，确保布局已经稳定。
            //
            // 【三重刷新机制】
            // 1. 立即 requestLayout + invalidate
            // 2. 延迟 100ms 再刷新一次
            // 3. 延迟 200ms 重新绑定播放器
            if (playerView != null) {
                playerView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        SettingsActivity.logOperation("【画中画】✅ 手动调用 refreshPlayerViewLayout()（方案四）");
                        refreshPlayerViewLayout();
                    }
                }, 300);
            }

            // 3. 同步遥控器模式
            syncRemoteMode();

            // 4. 恢复信息栏显示
            if (infoDisplayManager != null && channelSourceList.size() > currentPlayIndex) {
                Channel currChannel = channelSourceList.get(currentPlayIndex);
                TVPlayerManager.LiveInfo liveInfo = mPlayerManager.getLiveInfo();
                infoDisplayManager.showInfoBar(currChannel, liveInfo);
                infoDisplayManager.showChannelNum(currentPlayIndex + 1);
            }

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            resumeCurrentChannel();

            SettingsActivity.logOperation("【画中画】退出画中画完成");
            SettingsActivity.logOperation("【画中画】================================");
        }
    }

    // ====================================================================
    // ✅ 辅助方法：打印 View 的详细尺寸信息（接入操作日志）
    // 
    // 作用：记录 View 的位置、尺寸、布局参数、可见性，用于排查布局问题
    // 输出内容：
    // - 位置：left、top、right、bottom
    // - 尺寸：宽、高
    // - 布局参数：width、height（MATCH_PARENT=-1，WRAP_CONTENT=-2）
    // - 可见性：VISIBLE / INVISIBLE / GONE
    // 
    // @param tag 日志标签，标识当前是哪个时间点
    // @param view 要打印尺寸的 View
    // ====================================================================
    private void logPipViewSize(String tag, View view) {
        if (view == null) {
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "：View 为 null");
            return;
        }
        try {
            // 打印位置和尺寸
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "位置：left=" + view.getLeft() 
                + "，top=" + view.getTop()
                + "，right=" + view.getRight() 
                + "，bottom=" + view.getBottom());
            
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "尺寸：宽=" + view.getWidth() 
                + "，高=" + view.getHeight());

            // 打印布局参数
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                                String heightStr = lp.height == ViewGroup.LayoutParams.MATCH_PARENT ? "MATCH_PARENT(-1)" :
                                   lp.height == ViewGroup.LayoutParams.WRAP_CONTENT ? "WRAP_CONTENT(-2)" :
                                   String.valueOf(lp.height);
                
                SettingsActivity.logOperation("【画中画尺寸】" + tag + "布局参数：width=" + widthStr 
                    + "，height=" + heightStr);
            }

            // 打印可见性
            int visibility = view.getVisibility();
            String visStr = visibility == View.VISIBLE ? "VISIBLE" :
                            visibility == View.INVISIBLE ? "INVISIBLE" : "GONE";
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "可见性：" + visStr);
        } catch (Exception e) {
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "获取尺寸失败：" + e.getMessage());
        }
    }
        // ====================================================================
    // ✅ 辅助方法：打印 View 的详细尺寸信息（接入操作日志）
    // 
    // 作用：记录 View 的位置、尺寸、布局参数、可见性，用于排查布局问题
    // 输出内容：
    // - 位置：left、top、right、bottom
    // - 尺寸：宽、高
    // - 布局参数：width、height（MATCH_PARENT=-1，WRAP_CONTENT=-2）
    // - 可见性：VISIBLE / INVISIBLE / GONE
    // 
    // @param tag 日志标签，标识当前是哪个时间点
    // @param view 要打印尺寸的 View
    // ====================================================================
    private void logPipViewSize(String tag, View view) {
        if (view == null) {
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "：View 为 null");
            return;
        }
        try {
            // 1. 打印位置和尺寸
            int left = view.getLeft();
            int top = view.getTop();
            int right = view.getRight();
            int bottom = view.getBottom();
            int width = view.getWidth();
            int height = view.getHeight();
            
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "位置：left=" + left 
                + "，top=" + top
                + "，right=" + right 
                + "，bottom=" + bottom);
            
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "尺寸：宽=" + width 
                + "，高=" + height);

            // 2. 打印布局参数
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                String widthStr;
                String heightStr;
                
                // 处理 width
                if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                    widthStr = "MATCH_PARENT(-1)";
                } else if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    widthStr = "WRAP_CONTENT(-2)";
                } else {
                    widthStr = String.valueOf(lp.width);
                }
                
                // 处理 height
                if (lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                    heightStr = "MATCH_PARENT(-1)";
                } else if (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    heightStr = "WRAP_CONTENT(-2)";
                } else {
                    heightStr = String.valueOf(lp.height);
                }
                
                SettingsActivity.logOperation("【画中画尺寸】" + tag + "布局参数：width=" + widthStr 
                    + "，height=" + heightStr);
            }

            // 3. 打印可见性
            int visibility = view.getVisibility();
            String visStr;
            if (visibility == View.VISIBLE) {
                visStr = "VISIBLE";
            } else if (visibility == View.INVISIBLE) {
                visStr = "INVISIBLE";
            } else {
                visStr = "GONE";
            }
            
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "可见性：" + visStr);
            
        } catch (Exception e) {
            SettingsActivity.logOperation("【画中画尺寸】" + tag + "获取尺寸失败：" + e.getMessage());
        }
    }

    // ====================================================================
    // 日志方法
    // ====================================================================
    private void log(String msg) {
        logList.add(msg);
        Log.d("MainActivity", msg);
    }

    // ====================== 生命周期方法 ======================
    // ====================================================================
    // ✅ onPause（集成版：画中画模式下保持播放）
    // 
    // 逻辑：
    // 1. 先调用 appCoreManager.onPause()（让其他逻辑正常执行）
    // 2. 如果是画中画模式，立即恢复播放（防止被 onPause 暂停了）
    // 
    // 注意：
    // - 画中画模式下不能暂停播放器，否则小窗会黑屏
    // - 这里用"先暂停再恢复"的方式，而不是直接跳过 onPause，
    //   因为 appCoreManager.onPause() 可能还有其他重要逻辑（如注销广播等）
    // ====================================================================
    @Override
    protected void onPause() {
        super.onPause();

        // 先调用 onPause（让其他逻辑正常执行，如注销广播、停止加载等）
        appCoreManager.onPause();

        // ✅ 画中画模式下，立即恢复播放（防止被 onPause 暂停了）
        if (pipManager != null && (pipManager.isInPipMode() || pipManager.isPipEntering())) {
            try {
                if (mPlayerManager != null) {
                    mPlayerManager.resume();
                    SettingsActivity.logOperation("【画中画】✅ onPause后立即恢复播放（防止暂停）");
                }
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画】onPause恢复播放失败：" + e.getMessage());
            }
        }
    }

    // ====================================================================
    // ✅ 新增：onStop（标记停止状态，参考 TVBox 实现）
    // 
    // 作用：标记 onStop 已被调用，用于判断用户是返回应用还是关闭应用
    // 
    // 使用场景：
    // - 用户按 Home 键 → onPause → onStop → onStopCalled = true
    // - 用户点击小窗返回应用 → onStart → onResume → onStopCalled = false
    // - 用户关闭应用 → onPause → onStop → onDestroy → 释放播放器
    // 
    // 参考 TVBox PlayActivity 的实现，用 onStopCalled 判断是否需要释放播放器
    // ====================================================================
    @Override
    protected void onStop() {
        super.onStop();

        // ✅ 通知管理器：onStop 已被调用
        if (pipManager != null) {
            pipManager.setStopCalled(true);
            SettingsActivity.logOperation("【画中画】onStop 被调用");
        }
    }

    // ====================================================================
    // ✅ onResume（集成版：重置停止标记 + 方案一+四）
    // 
    // 作用：
    // 1. 重置 isOpeningSettings 标记
    // 2. 调用 appCoreManager.onResume()
    // 3. 重置 onStopCalled 标记
    // 4. 从设置页返回时重新加载设置
    // 5. 应用屏幕比例
    // 6. ✅ 方案一：重新应用全屏设置（画中画模式下跳过）
    // 7. 非画中画模式下恢复播放
    // 8. 同步遥控器模式
    // 9. ✅ 方案四：刷新 PlayerView 布局（兜底）
    // ====================================================================
    @Override
    protected void onResume() {
        super.onResume();
        isOpeningSettings = false;
        appCoreManager.onResume();

        // ✅ 重置 onStop 标记（用户返回应用了）
        if (pipManager != null) {
            pipManager.setStopCalled(false);
        }

        // 从设置页返回，重新加载开关
        loadSettings();
        screenRatioManager.apply();

        // ================================================================
        // ✅ 方案一：重新应用全面屏（画中画模式下不恢复）
        // ================================================================
        // 【为什么这里也要调用？】
        // 有时候从后台切回前台，全面屏可能会失效（比如被系统重置了）。
        // 这里重新应用一下，确保全屏正常。
        // 
        // 【注意】
        // reapplyFullScreen() 内部会判断是否在画中画模式，
        // 如果在画中画模式，会自动跳过，不会冲突。
        if (displayManager != null) {
            displayManager.reapplyFullScreen();
        }

        // 非画中画模式下恢复播放
        if (pipManager == null || !pipManager.isInPipMode()) {
            new Handler(Looper.getMainLooper()).postDelayed(this::resumeCurrentChannel, 200);
            
            // ============================================================
            // ✅ 方案四：刷新 PlayerView 布局（兜底）
            // ============================================================
            // 从后台切回前台时，有时候布局会乱，刷一下保险。
            // 延迟 100ms，等页面恢复后再刷。
            if (playerView != null) {
                playerView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshPlayerViewLayout();
                    }
                }, 100);
            }
        }

        syncRemoteMode();
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

        if (mPanelAutoHideHandler != null) {
            mPanelAutoHideHandler.removeCallbacks(mPanelAutoHideRunnable);
        }

        if (infoDisplayManager != null) {
            infoDisplayManager.release();
        }

        if (channelNumberManager != null) {
            channelNumberManager.release();
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

        mInstance = null;
    }
}
                
 
