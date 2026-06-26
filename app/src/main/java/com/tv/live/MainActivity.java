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
 * 【2026-06-26 修改：面板自动隐藏逻辑移到 ChannelPanelController】
 * 【修改说明】
 * 把原来 MainActivity 中的面板自动隐藏逻辑（Handler + Runnable）
 * 合并到 ChannelPanelController 中统一管理，MainActivity 只负责调用。
 * 
 * 【2026-06-26 修改：方向键处理逻辑移到 KeyEventManager】
 * 【修改说明】
 * 把原来 MainActivity 中的 handleDirectionKey() 方法合并到 KeyEventManager 中，
 * 统一管理播放模式下的方向键处理，MainActivity 只负责调用。
 * 
 * 【2026-06-26 修改：按键分发逻辑合并到 TvRemoteManager】
 * 【修改说明】
 * 把原来 MainActivity.onKeyDown() 中的按键分发逻辑（画中画判断、自动隐藏、
 * 数字选台、面板按键、方向键等）全部合并到 TvRemoteManager 中，
 * MainActivity.onKeyDown() 简化为只调用 remoteManager.dispatchKeyEvent() 一行。
 *
 * 【2026-06-26 修改：返回键全量合并至 TvRemoteManager】
 * 【修改说明】
 * 移除 MainActivity 本地所有返回判断，onBackPressed 全权委托 remoteManager.handleBackPressed()
 *
 * 【2026-06-26 修改：画中画辅助方法全部合并到 PictureInPictureManager】
 * 【修改说明】
 * 移除 MainActivity 本地的画中画辅助方法：
 * - hideAllUiForPip() → 改用 pipManager.hideAllUi()
 * - keepPlayingInPip() → 改用 pipManager.keepPlaying()
 * - resumeCurrentChannel() → 改用 pipManager.resumePlayback()
 * - logPipViewSize() → 改用 pipManager.logViewSize()
 * - logPipWindowSize() → 改用 pipManager.logWindowSize()
 * - 画中画参数构建 → 改用 pipManager.buildDefaultPipParams()
 * - 进入画中画流程 → 改用 pipManager.enterPip()
 * MainActivity 只保留生命周期回调，画中画逻辑全部集中在 PictureInPictureManager。
 */
public class MainActivity extends AppCompatActivity {
    // ====================== 单例 ======================
    public static MainActivity mInstance;
    // 兼容层：保留旧的 public 变量
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
    // 拆分新增：各个 Manager
    private ChannelNumberManager channelNumberManager;
    private DisplayManager displayManager;
    private InfoDisplayManager infoDisplayManager;
    private ChannelPanelController channelPanelController;
    private AppCoreManager appCoreManager;
    // 遥控器统一管理器
    private TvRemoteManager remoteManager;
    // 画中画相关变量
    private PictureInPictureManager pipManager;
    private boolean pipEnable = false;
    // 解码器模式广播接收器
    private BroadcastReceiver decoderModeReceiver;
    // ====================== 状态标志 ======================
    private boolean channel_reverse;
    private boolean number_channel_enable;
    private boolean isOpeningSettings = false;
    // 源失效自动切台相关变量
    private int mConsecutiveFailedCount = 0;
    private static final int MAX_CONSECUTIVE_SKIP = 10;
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
        initPictureInPicture();
        initDecoderModeReceiver();
        // ✅ 2026-06-26 修改：首次启动延迟隐藏面板（改用 ChannelPanelController）
        if (mIsFirstLaunch) {
            channelPanelController.setAutoHideDelay(3000); // 首次启动 3 秒后隐藏
            channelPanelController.resetAutoHide();
            channelPanelController.setAutoHideDelay(5000); // 恢复默认 5 秒
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
        keyEventManager.setChannelPanelController(channelPanelController);
        currentPlayIndex = appConfig.getLastPlayIndex();
        channelPanelController.setCurrentPlayIndex(currentPlayIndex);
        SettingsActivity.logOperation("【播放】记录上次播放索引：" + currentPlayIndex);
        initChannelNumberManager();
        if (channelNumberManager != null) {
            channelNumberManager.setEnable(number_channel_enable);
        }
        // ✅ 2026-06-26 新增：给 TvRemoteManager 设置剩余依赖
        remoteManager.setChannelNumberManager(channelNumberManager);
        remoteManager.setKeyEventManager(keyEventManager);
        initAppCoreManager();
        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();
    }
    // ====================================================================
    // 初始化解码器模式广播接收器
    // ====================================================================
    private void initDecoderModeReceiver() {
        decoderModeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.tv.live.DECODER_MODE_CHANGED".equals(intent.getAction())) {
                    SharedPreferences sp = getSharedPreferences("app_settings", MODE_PRIVATE);
                    String modeStr = sp.getString("decoder_mode", "auto");
                    int mode = TVPlayerManager.DECODER_MODE_AUTO;
                    if ("hard".equals(modeStr)) {
                        mode = TVPlayerManager.DECODER_MODE_HARD;
                    } else if ("soft".equals(modeStr)) {
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
                        case TVPlayerManager.DECODER_MODE_AUTO:
                        default:
                            modeName = "自动（推荐）";
                            break;
                    }
                    SettingsActivity.logOperation("【解码器】收到广播，切换到：" + modeName);
                }
            }
        };
        IntentFilter filter = new IntentFilter("com.tv.live.DECODER_MODE_CHANGED");
        registerReceiver(decoderModeReceiver, filter);
        SettingsActivity.logOperation("【解码器】广播接收器已注册");
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
    // 初始化遥控器管理器
    // ====================================================================
    private void initRemoteManager() {
        remoteManager = new TvRemoteManager();
        remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        // ✅ 2026-06-26 新增：设置频道面板控制器（自动隐藏重置 + 面板按键兜底）
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
            // ✅ 2026-06-26 新增：画中画模式返回键回调
            @Override
            public boolean onPipBack() {
                moveTaskToBack(false);
                return true;
            }
            @Override
            public void onRequestPlayFocus() {
                playerView.requestFocus();
            }
        });
    }
    // 同步遥控器模式
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
        // 设置源失效监听器（自动切台）
        mPlayerManager.setOnSourceFailedListener(new TVPlayerManager.OnSourceFailedListener() {
            @Override
            public void onSourceFailed() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleSourceFailed();
                    }
                });
            }
        });
    }
    // 处理源失效（自动切台）
    private void handleSourceFailed() {
        mConsecutiveFailedCount++;
        String channelName = "";
        if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel ch = channelSourceList.get(currentPlayIndex);
            if (ch != null) {
                channelName = ch.getName();
            }
        }
        SettingsActivity.logOperation("【自动切台】频道「" + channelName
                + "」源失效，连续失效第 " + mConsecutiveFailedCount + " 个");
        if (mConsecutiveFailedCount >= MAX_CONSECUTIVE_SKIP) {
            SettingsActivity.logOperation("【自动切台】已连续跳过 "
                    + MAX_CONSECUTIVE_SKIP + " 个失效频道，停止自动跳过");
            Toast.makeText(MainActivity.this, "已跳过 " + MAX_CONSECUTIVE_SKIP
                    + " 个失效频道，请检查直播源", Toast.LENGTH_SHORT).show();
            return;
        }
        SettingsActivity.logOperation("【自动切台】自动切换到下一个频道");
        channelPanelController.switchDown();
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
        // ✅ 2026-06-26 新增：设置给 KeyEventManager（OK键确认数字选台需要）
        if (keyEventManager != null) {
            keyEventManager.setChannelNumberManager(channelNumberManager);
        }
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
        // 读取解码器模式并应用
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
            case TVPlayerManager.DECODER_MODE_AUTO:
            default:
                modeName = "自动（推荐）";
                break;
        }
        SettingsActivity.logOperation("【设置】解码器模式：" + modeName);
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
    // 获取反转状态
    public boolean isChannelReverse() {
        return channel_reverse;
    }
    // 兼容层：旧的 playChannel(int) 方法
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }
    // 播放频道（内部方法）
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
        // 成功切换频道，重置连续失效计数
        mConsecutiveFailedCount = 0;
        // 画中画模式下同步频道信息到管理器
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
    // 兼容层：旧的 togglePanel() 方法
    public void togglePanel() {
        channelPanelController.togglePanel();
        syncRemoteMode();
    }
    // 兼容层：旧的 playPrev() 方法
    public void playPrev() {
        channelPanelController.playPrev();
    }
    // 兼容层：旧的 playNext() 方法
    public void playNext() {
        channelPanelController.playNext();
    }
    // ====================== 返回键处理（完全合并到 TvRemoteManager） ======================
    @Override
    public void onBackPressed() {
        if (remoteManager != null && remoteManager.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }
    // ====================== 按键分发 ======================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ✅ 2026-06-26 修改：按键分发统一走 TvRemoteManager
        // TvRemoteManager 内部处理：画中画判断、自动隐藏重置、模式化按键、数字选台、面板按键、方向键兜底
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
    // 画中画：用户按 Home 键时自动进入画中画
    // ====================================================================
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        SettingsActivity.logOperation("【画中画排查】========== 开始 ==========");
        SettingsActivity.logOperation("【画中画排查】onUserLeaveHint 被调用");
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
        boolean shouldEnter = pipManager.shouldEnterPip();
        SettingsActivity.logOperation("【画中画排查】MainActivity开关状态：" + pipEnable);
        SettingsActivity.logOperation("【画中画排查】设备支持：" + pipManager.isPipSupported());
        SettingsActivity.logOperation("【画中画排查】PIP管理器开关：" + pipManager.isPipEnabled());
        SettingsActivity.logOperation("【画中画排查】已在画中画模式：" + pipManager.isInPipMode());
        SettingsActivity.logOperation("【画中画排查】正在进入画中画：" + pipManager.isPipEntering());
        if (shouldEnter) {
            SettingsActivity.logOperation("【画中画排查】所有条件满足，尝试进入画中画...");
            // ✅ 2026-06-26 修改：改用 PictureInPictureManager 的便捷方法 enterPip
            boolean result = pipManager.enterPip(this, mPlayerManager);
            SettingsActivity.logOperation("【画中画排查】进入结果：" + (result ? "✅ 成功" : "❌ 失败"));
        } else {
            SettingsActivity.logOperation("【画中画排查】❌ 条件不满足，不进入画中画");
        }
        SettingsActivity.logOperation("【画中画排查】========== 结束 ==========");
    }
    // ====================================================================
    // 画中画模式变化回调
    // ====================================================================
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        SettingsActivity.logOperation("【画中画】模式变化 → " + (isInPictureInPictureMode ? "进入" : "退出"));
        // ✅ 2026-06-26 新增：同步画中画状态给 TvRemoteManager
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
        if (isInPictureInPictureMode) {
            SettingsActivity.logOperation("【画中画】========== 进入画中画 ==========");
            // ✅ 2026-06-26 修改：改用 PictureInPictureManager 的 hideAllUi
            pipManager.hideAllUi(channelPanelController, infoDisplayManager);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // ✅ 2026-06-26 修改：改用 PictureInPictureManager 的 resumePlayback
            pipManager.resumePlayback(mPlayerManager);
            // ✅ 2026-06-26 修改：改用 PictureInPictureManager 的 logViewSize
            pipManager.logViewSize("进入画中画时", playerView);
            SettingsActivity.logOperation("【画中画】================================");
        } else {
            SettingsActivity.logOperation("【画中画】========== 退出画中画 ==========");
            if (pipManager != null) {
                pipManager.handleExitPip(new Runnable() {
                    @Override
                    public void run() {
                        SettingsActivity.logOperation("【画中画】应用已关闭，释放播放器");
                    }
                });
            }
            // 日志点1：刚退出画中画时的初始尺寸
            SettingsActivity.logOperation("【画中画尺寸】===== 1. 刚退出画中画（初始状态） =====");
            // ✅ 2026-06-26 修改：改用 PictureInPictureManager 的 logViewSize
            pipManager.logViewSize("PlayerView", playerView);
            if (playerView != null && playerView.getParent() instanceof View) {
                pipManager.logViewSize("父布局", (View) playerView.getParent());
            }
            // ✅ 2026-06-26 修改：改用 PictureInPictureManager 的 logWindowSize
            pipManager.logWindowSize(this);
            // 1. 重新应用全屏设置
            if (displayManager != null) {
                SettingsActivity.logOperation("【画中画尺寸】执行 displayManager.reapplyFullScreen()");
                displayManager.reapplyFullScreen();
            }
            // 日志点2：reapplyFullScreen 后的尺寸
            SettingsActivity.logOperation("【画中画尺寸】===== 2. reapplyFullScreen 后 =====");
            pipManager.logViewSize("PlayerView", playerView);
            // 2. 强制刷新 PlayerView 布局
            if (playerView != null) {
                playerView.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            playerView.requestLayout();
                            playerView.invalidate();
                            SettingsActivity.logOperation("【画中画】✅ 立即刷新 PlayerView 布局");
                            // 日志点3：立即刷新后的尺寸
                            SettingsActivity.logOperation("【画中画尺寸】===== 3. 立即 requestLayout 后 =====");
                            pipManager.logViewSize("PlayerView", playerView);
                        } catch (Exception e) {
                            SettingsActivity.logOperation("【画中画】刷新 PlayerView 失败：" + e.getMessage());
                        }
                    }
                });
                playerView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            playerView.requestLayout();
                            playerView.invalidate();
                            // ✅ 2026-06-26 修改：改用 PictureInPictureManager 的 keepPlaying
                            pipManager.keepPlaying(mPlayerManager, playerView, channelSourceList, currentPlayIndex);
                            SettingsActivity.logOperation("【画中画】✅ 延迟刷新 PlayerView + 重新绑定");
                            // 日志点4：延迟刷新 + 重新绑定后的尺寸
                            SettingsActivity.logOperation("【画中画尺寸】===== 4. 延迟200ms刷新 + 重新绑定后 =====");
                            pipManager.logViewSize("PlayerView", playerView);
                            if (playerView.getParent() instanceof View) {
                                pipManager.logViewSize("父布局", (View) playerView.getParent());
                            }
                            SettingsActivity.logOperation("【画中画尺寸】========================================");
                        } catch (Exception e) {
                            SettingsActivity.logOperation("【画中画】延迟刷新失败：" + e.getMessage());
                        }
                    }
                }, 200);
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
            // ✅ 2026-06-26 修改：改用 PictureInPictureManager 的 resumePlayback
            pipManager.resumePlayback(mPlayerManager);
            SettingsActivity.logOperation("【画中画】退出画中画完成");
            SettingsActivity.logOperation("【画中画】================================");
        }
    }
    // 日志方法
    private void log(String msg) {
        logList.add(msg);
        Log.d("MainActivity", msg);
    }
    // ====================== 生命周期方法 ======================
    @Override
    protected void onPause() {
        super.onPause();
        appCoreManager.onPause();
        // ✅ 2026-06-26 修改：改用 PictureInPictureManager 的 handleOnPause
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
            // ✅ 2026-06-26 修改：改用 PictureInPictureManager 的 resumePlayback
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (pipManager != null) {
                        pipManager.resumePlayback(mPlayerManager);
                    }
                }
            }, 200);
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
        // 注销解码器模式广播接收器
        if (decoderModeReceiver != null) {
            try {
                unregisterReceiver(decoderModeReceiver);
                decoderModeReceiver = null;
                SettingsActivity.logOperation("【解码器】广播接收器已注销");
            } catch (Exception e) {
                // 忽略，可能已经被注销了
            }
        }
        if (mPlayerManager != null) {
            mPlayerManager.release();
        }
        mInstance = null;
        SettingsActivity.logOperation("【系统】APP退出");
    }
}
