package com.tv.live;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.pm.ActivityInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.ui.PlayerView;

import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.*;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.List;

/**
 * 生命周期管理器
 * 作用：统一管理 MainActivity 的生命周期逻辑和 UI 初始化逻辑
 *
 * 【2026-06-25 合并：UiInitializer】
 * 【合并说明】把 UiInitializer 的 UI 初始化逻辑合并到这里。
 *
 * 【2026-06-25 修复：更新所有已合并类的引用】
 * 【修复说明】
 * - AutoSkipManager → ChannelPlayManager（已合并）
 * - PanelAutoHideManager → ChannelPanelController（已合并）
 * - DirectionKeyHandler → KeyDispatcher（已合并）
 * - BackPressHandler → KeyDispatcher（已合并）
 * - LogHelper → AppCoreManager（已合并）
 */
public class LifecycleManager {

    // ====================== 单例模式 ======================
    private static LifecycleManager instance;

    private LifecycleManager() {
    }

    public static synchronized LifecycleManager getInstance() {
        if (instance == null) {
            instance = new LifecycleManager();
        }
        return instance;
    }

    // ====================================================================
    // 接口定义
    // ====================================================================
    public interface OnInitCompleteListener {
        void onInitComplete();
    }

    // ====================== 基础成员变量 ======================
    private Activity activity;

    // ====================================================================
    // 各个 Manager 引用（已合并去重）
    // ====================================================================
    private DisplayManager displayManager;
    private InfoDisplayManager infoDisplayManager;
    private AppConfig appConfig;
    private SettingsManager settingsManager;
    private ChannelPlayManager channelPlayManager;
    private KeyDispatcher keyDispatcher;
    private PlayerView playerView;
    private ChannelPanelController channelPanelController;
    private TvRemoteManager remoteManager;
    private PictureInPictureManager pipManager;
    private TVPlayerManager playerManager;
    private PlayerStateListenerImpl playerStateListener;
    private ScreenRatioManager screenRatioManager;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private ChannelNumberManager channelNumberManager;
    private AppCoreManager appCoreManager;

    // ====================== 生命周期相关 ======================
    private boolean isOpeningSettings = false;

    // ====================== 监听器 ======================
    private OnInitCompleteListener initCompleteListener;

    // ====================================================================
    // Setter 方法
    // ====================================================================

    public void setActivity(Activity activity) {
        this.activity = activity;
    }

    public void setAppCoreManager(AppCoreManager manager) {
        this.appCoreManager = manager;
    }

    public void setPipManager(PictureInPictureManager manager) {
        this.pipManager = manager;
    }

    public void setPlayerManager(TVPlayerManager manager) {
        this.playerManager = manager;
    }

    public void setSettingsManager(SettingsManager manager) {
        this.settingsManager = manager;
    }

    public void setScreenRatioManager(ScreenRatioManager manager) {
        this.screenRatioManager = manager;
    }

    public void setDisplayManager(DisplayManager manager) {
        this.displayManager = manager;
    }

    public void setRemoteManager(TvRemoteManager manager) {
        this.remoteManager = manager;
    }

    public void setChannelPanelController(ChannelPanelController controller) {
        this.channelPanelController = controller;
    }

    public void setInfoDisplayManager(InfoDisplayManager manager) {
        this.infoDisplayManager = manager;
    }

    public void setChannelNumberManager(ChannelNumberManager manager) {
        this.channelNumberManager = manager;
    }

    public void setChannelPlayManager(ChannelPlayManager manager) {
        this.channelPlayManager = manager;
    }

    // ✅ 2026-06-25 修复：PanelAutoHideManager 已合并到 ChannelPanelController
    @Deprecated
    public void setPanelAutoHideManager(Object manager) {
        // 空实现，向后兼容
    }

    // ✅ 2026-06-25 修复：AutoSkipManager 已合并到 ChannelPlayManager
    @Deprecated
    public void setAutoSkipManager(Object manager) {
        // 空实现，向后兼容
    }

    public void setOpeningSettings(boolean opening) {
        this.isOpeningSettings = opening;
    }

    public boolean isOpeningSettings() {
        return isOpeningSettings;
    }

    // ✅ 2026-06-25 修复：UiInitializer 已合并到 LifecycleManager
    @Deprecated
    public void setUiInitializer(Object initializer) {
        // 空实现，向后兼容
    }

    public void setOnInitCompleteListener(OnInitCompleteListener listener) {
        this.initCompleteListener = listener;
    }

    // ====================================================================
    // Getter 方法（已更新，去掉已删除的类）
    // ====================================================================

    public DisplayManager getDisplayManager() {
        return displayManager;
    }

    public InfoDisplayManager getInfoDisplayManager() {
        return infoDisplayManager;
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public SettingsManager getSettingsManager() {
        return settingsManager;
    }

    // ✅ 2026-06-25 修复：AutoSkipManager → ChannelPlayManager
    @Deprecated
    public Object getAutoSkipManager() {
        return channelPlayManager;
    }

    // ✅ 2026-06-25 修复：PanelAutoHideManager → ChannelPanelController
    @Deprecated
    public Object getPanelAutoHideManager() {
        return channelPanelController;
    }

    public ChannelPlayManager getChannelPlayManager() {
        return channelPlayManager;
    }

    // ✅ 2026-06-25 修复：DirectionKeyHandler → KeyDispatcher
    @Deprecated
    public Object getDirectionKeyHandler() {
        return keyDispatcher;
    }

    public KeyDispatcher getKeyDispatcher() {
        return keyDispatcher;
    }

    // ✅ 2026-06-25 修复：BackPressHandler → KeyDispatcher
    @Deprecated
    public Object getBackPressHandler() {
        return keyDispatcher;
    }

    public PlayerView getPlayerView() {
        return playerView;
    }

    public ChannelPanelController getChannelPanelController() {
        return channelPanelController;
    }

    public TvRemoteManager getRemoteManager() {
        return remoteManager;
    }

    public PictureInPictureManager getPipManager() {
        return pipManager;
    }

    public TVPlayerManager getPlayerManager() {
        return playerManager;
    }

    public ScreenRatioManager getScreenRatioManager() {
        return screenRatioManager;
    }

    public GestureManager getGestureManager() {
        return gestureManager;
    }

    public KeyEventManager getKeyEventManager() {
        return keyEventManager;
    }

    public ChannelNumberManager getChannelNumberManager() {
        return channelNumberManager;
    }

    public AppCoreManager getAppCoreManager() {
        return appCoreManager;
    }

    // ✅ 2026-06-25 修复：LogHelper → AppCoreManager
    @Deprecated
    public Object getLogHelper() {
        return appCoreManager;
    }

    // ====================================================================
    // 初始化方法（全部从 UiInitializer 移过来，已更新引用）
    // ====================================================================

    public void initBaseConfig() {
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        displayManager = new DisplayManager(activity);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SettingsActivity.logOperation("【初始化】基础配置完成");
    }

    public void initInfoDisplayManager() {
        TextView tv_channel_num = activity.findViewById(R.id.tv_channel_num);
        View info_bar = activity.findViewById(R.id.info_bar);
        TextView tv_channel_name = activity.findViewById(R.id.tv_channel_name);
        TextView tv_tag_fhd = activity.findViewById(R.id.tv_tag_fhd);
        TextView tv_tag_audio = activity.findViewById(R.id.tv_tag_audio);
        TextView tv_bitrate = activity.findViewById(R.id.tv_bitrate);
        TextView tv_current_program_name = activity.findViewById(R.id.tv_current_program_name);
        TextView tv_current_time_range = activity.findViewById(R.id.tv_current_time_range);
        ProgressBar progress_program = activity.findViewById(R.id.progress_program);
        TextView tv_remaining_time = activity.findViewById(R.id.tv_remaining_time);
        TextView tv_next_program_name = activity.findViewById(R.id.tv_next_program_name);
        TextView tv_next_time_range = activity.findViewById(R.id.tv_next_time_range);

        infoDisplayManager = new InfoDisplayManager(
                activity,
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
        SettingsActivity.logOperation("【初始化】信息展示管理器完成");
    }

    public void initConfigManagers() {
        appConfig = AppConfig.getInstance(activity);
        settingsManager = SettingsManager.getInstance(activity);
        // ✅ 修复：LogHelper 已合并到 AppCoreManager，这里不需要单独初始化了
        SettingsActivity.logOperation("【初始化】配置管理器完成");
    }

    public void initFeatureManagers() {
        // ✅ 修复：AutoSkipManager 已合并到 ChannelPlayManager
        channelPlayManager = ChannelPlayManager.getInstance(activity);
        channelPlayManager.setOnAutoSkipListener(new ChannelPlayManager.OnAutoSkipListener() {
            @Override
            public void onSkipNext() {
                if (channelPanelController != null) {
                    channelPanelController.switchDown();
                }
            }
        });

        // ✅ 修复：PanelAutoHideManager 已合并到 ChannelPanelController
        // ✅ 修复：DirectionKeyHandler 已合并到 KeyDispatcher
        keyDispatcher = KeyDispatcher.getInstance();
        // ✅ 修复：BackPressHandler 已合并到 KeyDispatcher

        SettingsActivity.logOperation("【初始化】功能管理器完成");
    }

    public void initPlayerView() {
        playerView = activity.findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);
        SettingsActivity.logOperation("【初始化】播放器视图完成");
    }

    public void initChannelPanel() {
        View panel_layout = activity.findViewById(R.id.panel_layout);
        View ll_left_panel = activity.findViewById(R.id.ll_left_panel);
        View ll_right_panel = activity.findViewById(R.id.ll_right_panel);
        ListView lvGroup = activity.findViewById(R.id.lv_group);
        ListView lvChannelList = activity.findViewById(R.id.lv_channel_list);
        ListView lvChannelListEpg = activity.findViewById(R.id.lv_channel_list_epg);
        ListView lvDate = activity.findViewById(R.id.lv_date);
        ListView lvEpg = activity.findViewById(R.id.lv_epg);
        TextView btn_show_epg = activity.findViewById(R.id.btn_show_epg);
        TextView btn_back_group = activity.findViewById(R.id.btn_back_group);

        EpgManager.getInstance(activity);

        ChannelListManager channelListManager = new ChannelListManager(activity, lvChannelList);
        ChannelListManager channelListManagerEpg = new ChannelListManager(activity, lvChannelListEpg);
        GroupListManager groupListManager = new GroupListManager(activity, lvGroup);
        DateListManager dateListManager = new DateListManager(activity, lvDate);
        EpgManagerWrapper epgManagerWrapper = new EpgManagerWrapper(activity, lvEpg);
        PanelManager panelManager = new PanelManager(panel_layout, channelListManager, epgManagerWrapper);

        dateListManager.initDate();
        dateListManager.setOnDateSelectedListener(pos -> {
            channelPanelController.setCurrentDateIndex(pos);
        });

        channelPanelController = new ChannelPanelController(
                activity,
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
                channelPlayManager.playChannel(channel, index);
            }
        });

        // ✅ 修复：PanelAutoHideManager 已合并到 ChannelPanelController，不需要单独设置了

        SettingsActivity.logOperation("【初始化】频道面板完成");
    }

    public void initRemoteManager() {
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

        SettingsActivity.logOperation("【初始化】遥控器管理器完成");
    }

    public void initPipManager() {
        try {
            pipManager = PictureInPictureManager.getInstance(activity);
            pipManager.setPipEnabled(settingsManager.isPipEnabled());
            pipManager.setListener(new PictureInPictureManager.OnPipListener() {
                @Override
                public void onPipModeChanged(boolean inPip) {
                    // ✅ 修复：LogHelper → AppCoreManager
                    appCoreManager.log("【画中画】监听器回调：" + (inPip ? "进入" : "退出"));
                }
            });

            // ✅ 修复：LogHelper → AppCoreManager
            appCoreManager.log("【画中画】初始化完成，开关状态："
                    + (settingsManager.isPipEnabled() ? "开启" : "关闭"));
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持："
                    + pipManager.isPipSupported());
        } catch (Exception e) {
            // ✅ 修复：LogHelper → AppCoreManager
            appCoreManager.log("【画中画】初始化失败：" + e.getMessage());
            pipManager = null;
        }
    }

    public void initPlayer() {
        playerManager = TVPlayerManager.getInstance(activity);
        playerManager.attachPlayerView(playerView);

        playerStateListener = new PlayerStateListenerImpl(activity);
        playerManager.setOnPlayStateListener(playerStateListener);

        playerManager.setOnLiveInfoUpdateListener(new TVPlayerManager.OnLiveInfoUpdateListener() {
            @Override
            public void onLiveInfoUpdate(TVPlayerManager.LiveInfo info) {
                infoDisplayManager.updateLiveInfo(info);
                if (pipManager != null) {
                    pipManager.updatePlayState(true);
                }
            }
        });

        playerManager.setOnSourceFailedListener(new TVPlayerManager.OnSourceFailedListener() {
            @Override
            public void onSourceFailed() {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String channelName = "";
                        Channel curr = channelPlayManager.getCurrentChannel();
                        if (curr != null) {
                            channelName = curr.getName();
                        }
                        // ✅ 修复：AutoSkipManager → ChannelPlayManager
                        channelPlayManager.handleSourceFailed(channelName);
                    }
                });
            }
        });

        SettingsActivity.logOperation("【初始化】播放器完成");
    }

    public void initChannelPlayManager() {
        channelPlayManager.setPlayerManager(playerManager);
        channelPlayManager.setInfoDisplayManager(infoDisplayManager);
        channelPlayManager.setAppConfig(appConfig);
        channelPlayManager.setPlayerStateListener(playerStateListener);
        // ✅ 修复：AutoSkipManager 已合并到 ChannelPlayManager，不需要单独设置了
        channelPlayManager.setPipManager(pipManager);
        SettingsActivity.logOperation("【初始化】频道播放管理器完成");
    }

    // ✅ 2026-06-25 修复：DirectionKeyHandler → KeyDispatcher
    public void initDirectionKeyHandler() {
        keyDispatcher.setPanelController(channelPanelController);
        keyDispatcher.setChannelNumberManager(channelNumberManager);
        keyDispatcher.setPanelToggleCallback(new KeyDispatcher.PanelToggleCallback() {
            @Override
            public void onTogglePanel() {
                togglePanel();
            }
        });
        keyDispatcher.setChannelReverse(settingsManager.isChannelReverse());
        SettingsActivity.logOperation("【初始化】方向键处理器完成");
    }

    public void initLifecycleManager() {
        SettingsActivity.logOperation("【初始化】生命周期管理器完成");
    }

    public void initKeyDispatcher() {
        keyDispatcher.setPipManager(pipManager);
        // ✅ 修复：PanelAutoHideManager 已合并到 ChannelPanelController
        keyDispatcher.setRemoteManager(remoteManager);
        keyDispatcher.setChannelNumberManager(channelNumberManager);
        keyDispatcher.setChannelPanelController(channelPanelController);
        // ✅ 修复：DirectionKeyHandler 已合并到 KeyDispatcher
        keyDispatcher.setKeyEventManager(keyEventManager);

        keyDispatcher.setOnKeyDispatcherListener(new KeyDispatcher.OnKeyDispatcherListener() {
            @Override
            public boolean onPipBackKey() {
                activity.moveTaskToBack(false);
                return true;
            }

            @Override
            public void onSuperKeyDown(int keyCode, android.view.KeyEvent event) {
            }
        });

        SettingsActivity.logOperation("【初始化】按键分发器完成");
    }

    // ✅ 2026-06-25 修复：BackPressHandler → KeyDispatcher
    public void initBackPressHandler() {
        keyDispatcher.setPlayerView(playerView);
        keyDispatcher.setOnBackPressListener(new KeyDispatcher.OnBackPressListener() {
            @Override
            public void onMoveTaskToBack() {
                activity.moveTaskToBack(false);
            }

            @Override
            public void onSyncRemoteMode() {
                syncRemoteMode();
            }

            @Override
            public void onSuperBackPressed() {
            }
        });

        SettingsActivity.logOperation("【初始化】返回键处理器完成");
    }

    public void initScreenRatioManager() {
        screenRatioManager = new ScreenRatioManager(playerManager, appConfig);
        screenRatioManager.apply();
        SettingsActivity.logOperation("【初始化】屏幕比例管理器完成");
    }

    public void initGestureManager() {
        gestureManager = new GestureManager(activity);
        final PlayerGestureHelper gestureHelper = gestureManager.create();
        playerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                gestureHelper.handleTouch(event);
                return true;
            }
        });
        SettingsActivity.logOperation("【初始化】手势管理器完成");
    }

    public void initKeyEventManager() {
        keyEventManager = new KeyEventManager(activity);
        SettingsActivity.logOperation("【初始化】按键事件管理器完成");
    }

    public void initChannelNumberManager() {
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
                settingsManager.isNumberChannelEnabled()
        );
        SettingsActivity.logOperation("【初始化】数字选台管理器完成");
    }

    public void initAppCoreManager() {
        appCoreManager = new AppCoreManager(activity, playerManager, appConfig);

        appCoreManager.setOnDataLoadListener(new AppCoreManager.OnDataLoadListener() {
            @Override
            public void onLiveSourceLoaded(java.util.List<Channel> channels, boolean fromCache) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        channelPlayManager.updateChannelSourceList(channels);
                        channelPanelController.setChannels(channels);

                        if (!appCoreManager.hasPlayedWithCache()) {
                            int lastIndex = channelPlayManager.getCurrentPlayIndex();
                            if (lastIndex >= 0 && lastIndex < channels.size()) {
                                Channel ch = channels.get(lastIndex);
                                channelPlayManager.playChannel(ch, lastIndex);
                                appCoreManager.setHasPlayedWithCache(true);
                            }
                        }

                        displayManager.hideLoading();
                        // ✅ 修复：LogHelper → AppCoreManager
                        appCoreManager.log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channels.size());
                    }
                });
            }

            @Override
            public void onLiveSourceFailed(String errorMsg) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        java.util.List<Channel> list = channelPlayManager.getChannelSourceList();
                        if (list == null || list.isEmpty()) {
                            displayManager.updateLoadingText("加载失败，请检查网络或稍后重试");
                            SettingsActivity.logOperation("【加载】直播源加载失败：" + errorMsg);
                        } else {
                            // ✅ 修复：LogHelper → AppCoreManager
                            appCoreManager.log("【缓存】使用缓存数据继续播放");
                            displayManager.hideLoading();
                        }
                    }
                });
            }

            @Override
            public void onEpgLoaded() {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Channel curr = channelPlayManager.getCurrentChannel();
                        if (curr != null) {
                            infoDisplayManager.updateEpgInfo(curr);
                        }
                    }
                });
            }

            @Override
            public void onLoadTimeout(boolean hasData) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // ✅ 修复：LogHelper → AppCoreManager
                        appCoreManager.log("【加载】超时，自动隐藏加载动画");
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
        SettingsActivity.logOperation("【初始化】应用核心管理器完成");
    }

    public void loadLastPlayIndex() {
        int lastPlayIndex = appConfig.getLastPlayIndex();
        channelPlayManager.setCurrentPlayIndex(lastPlayIndex);
        channelPanelController.setCurrentPlayIndex(lastPlayIndex);
        SettingsActivity.logOperation("【播放】记录上次播放索引：" + lastPlayIndex);
    }

    public void startLoading() {
        displayManager.showLoading("正在加载直播源...");
        appCoreManager.loadLiveAndEpg();
    }

    // ====================================================================
    // 生命周期方法
    // ====================================================================

    public void onPause() {
        if (appCoreManager != null) {
            appCoreManager.onPause();
        }
        if (pipManager != null) {
            pipManager.handleOnPause(new Runnable() {
                @Override
                public void run() {
                    if (playerManager != null) {
                        playerManager.resume();
                        SettingsActivity.logOperation("【画中画】✅ onPause后立即恢复播放（防止暂停）");
                    }
                }
            });
        }
    }

    public void onStop() {
        if (pipManager != null) {
            pipManager.setStopCalled(true);
            SettingsActivity.logOperation("【画中画】onStop 被调用");
        }
    }

    public void onResume() {
        isOpeningSettings = false;
        if (appCoreManager != null) {
            appCoreManager.onResume();
        }
        if (pipManager != null) {
            pipManager.setStopCalled(false);
        }
        applySettings();
        if (screenRatioManager != null) {
            screenRatioManager.apply();
        }
        if (displayManager != null) {
            displayManager.reapplyFullScreen();
        }
        if (pipManager == null || !pipManager.isInPipMode()) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    resumeCurrentChannel();
                }
            }, 200);
        }
        syncRemoteMode();
    }

    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus && displayManager != null) {
            displayManager.reapplyFullScreen();
        }
        if (appCoreManager != null) {
            appCoreManager.onWindowFocusChanged(hasFocus);
        }
    }

    // ✅ 2026-06-25 修复：加上 BroadcastReceiver 类型
    public void onDestroy(BroadcastReceiver decoderModeReceiver) {
        // ✅ 修复：PanelAutoHideManager 已合并到 ChannelPanelController
        if (channelPlayManager != null) {
            channelPlayManager.release();
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
        // ✅ 修复：AutoSkipManager 已合并到 ChannelPlayManager
        if (decoderModeReceiver != null && activity != null) {
            try {
                activity.unregisterReceiver(decoderModeReceiver);
                decoderModeReceiver = null;
                SettingsActivity.logOperation("【解码器】广播接收器已注销");
            } catch (Exception e) {
            }
        }
        if (playerManager != null) {
            playerManager.release();
        }
        SettingsActivity.logOperation("【系统】APP退出");
    }

    // ====================================================================
    // 私有方法
    // ====================================================================

    private void applySettings() {
        if (settingsManager == null) return;

        boolean epg_enable = settingsManager.isEpgEnabled();
        boolean channel_reverse = settingsManager.isChannelReverse();
        boolean number_channel_enable = settingsManager.isNumberChannelEnabled();
        boolean auto_update_source = settingsManager.isAutoUpdateSource();
        boolean pipEnable = settingsManager.isPipEnabled();
        int decoderMode = settingsManager.getDecoderModeInt();

        if (playerManager != null) {
            playerManager.setDecoderMode(decoderMode);
        }
        String modeName = SettingsManager.getDecoderModeName(decoderMode);
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

    private void syncRemoteMode() {
        if (remoteManager == null || channelPanelController == null) return;
        if (channelPanelController.isPanelOpen()) {
            remoteManager.setMode(TvRemoteManager.Mode.CHANNEL_PANEL_MODE);
            remoteManager.setRightPanelOpen(channelPanelController.isRightPanelOpen());
        } else {
            remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        }
    }

    private void resumeCurrentChannel() {
        try {
            if (playerManager != null) {
                playerManager.resume();
            }
        } catch (Exception e) {
            SettingsActivity.logOperation("【画中画】恢复播放失败：" + e.getMessage());
        }
    }

    private void togglePanel() {
        channelPanelController.togglePanel();
        syncRemoteMode();
    }

    // ====================================================================
    // 资源释放
    // ====================================================================
    public void release() {
        activity = null;
        appCoreManager = null;
        pipManager = null;
        playerManager = null;
        settingsManager = null;
        screenRatioManager = null;
        displayManager = null;
        remoteManager = null;
        channelPanelController = null;
        infoDisplayManager = null;
        channelNumberManager = null;
        channelPlayManager = null;
        keyDispatcher = null;
        playerView = null;
        playerStateListener = null;
        gestureManager = null;
        keyEventManager = null;
        appConfig = null;
        initCompleteListener = null;
    }
}
