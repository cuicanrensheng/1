package com.tv.live;

import android.app.Activity;
import android.content.pm.ActivityInfo;
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

/**
 * UI 初始化器
 * 作用：统一管理 MainActivity 的 UI 初始化逻辑
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的各种 initXxx() 方法和初始化逻辑抽离到这里，
 * 统一管理 UI 和各个 Manager 的初始化顺序。
 *
 * 【职责】
 * - 初始化基础配置（屏幕方向、全屏、保持屏幕常亮等）
 * - 初始化各个 Manager
 * - 绑定各个 Manager 之间的关系
 * - 设置各种监听器和回调
 */
public class UiInitializer {

    private static UiInitializer instance;

    private Activity activity;

    private DisplayManager displayManager;
    private InfoDisplayManager infoDisplayManager;
    private AppConfig appConfig;
    private SettingsManager settingsManager;
    private AutoSkipManager autoSkipManager;
    private PanelAutoHideManager panelAutoHideManager;
    private ChannelPlayManager channelPlayManager;
    private DirectionKeyHandler directionKeyHandler;
    private LifecycleManager lifecycleManager;
    private KeyDispatcher keyDispatcher;
    private BackPressHandler backPressHandler;
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
    private LogHelper logHelper;

    private OnInitCompleteListener listener;

    private UiInitializer() {
    }

    public static synchronized UiInitializer getInstance() {
        if (instance == null) {
            instance = new UiInitializer();
        }
        return instance;
    }

    public interface OnInitCompleteListener {
        void onInitComplete();
    }

    public void setOnInitCompleteListener(OnInitCompleteListener listener) {
        this.listener = listener;
    }

    public void setActivity(Activity activity) {
        this.activity = activity;
    }

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

    public AutoSkipManager getAutoSkipManager() {
        return autoSkipManager;
    }

    public PanelAutoHideManager getPanelAutoHideManager() {
        return panelAutoHideManager;
    }

    public ChannelPlayManager getChannelPlayManager() {
        return channelPlayManager;
    }

    public DirectionKeyHandler getDirectionKeyHandler() {
        return directionKeyHandler;
    }

    public LifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    public KeyDispatcher getKeyDispatcher() {
        return keyDispatcher;
    }

    public BackPressHandler getBackPressHandler() {
        return backPressHandler;
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

    public LogHelper getLogHelper() {
        return logHelper;
    }

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
        logHelper = LogHelper.getInstance();
        SettingsActivity.logOperation("【初始化】配置管理器完成");
    }

    public void initFeatureManagers() {
        autoSkipManager = AutoSkipManager.getInstance(activity);
        autoSkipManager.setOnAutoSkipListener(new AutoSkipManager.OnAutoSkipListener() {
            @Override
            public void onSkipNext() {
                if (channelPanelController != null) {
                    channelPanelController.switchDown();
                }
            }
        });

        panelAutoHideManager = PanelAutoHideManager.getInstance();
        channelPlayManager = ChannelPlayManager.getInstance(activity);
        directionKeyHandler = DirectionKeyHandler.getInstance();
        lifecycleManager = LifecycleManager.getInstance();
        keyDispatcher = KeyDispatcher.getInstance();
        backPressHandler = BackPressHandler.getInstance();

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

        panelAutoHideManager.setPanelController(channelPanelController);
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
                    logHelper.log("【画中画】监听器回调：" + (inPip ? "进入" : "退出"));
                }
            });
            logHelper.log("【画中画】初始化完成，开关状态："
                    + (settingsManager.isPipEnabled() ? "开启" : "关闭"));
            SettingsActivity.logOperation("【画中画】初始化完成，设备支持："
                    + pipManager.isPipSupported());
        } catch (Exception e) {
            logHelper.log("【画中画】初始化失败：" + e.getMessage());
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
                        autoSkipManager.handleSourceFailed(channelName);
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
        channelPlayManager.setAutoSkipManager(autoSkipManager);
        channelPlayManager.setPipManager(pipManager);
        SettingsActivity.logOperation("【初始化】频道播放管理器完成");
    }

    public void initDirectionKeyHandler() {
        directionKeyHandler.setPanelController(channelPanelController);
        directionKeyHandler.setChannelNumberManager(channelNumberManager);
        directionKeyHandler.setPanelToggleCallback(new DirectionKeyHandler.PanelToggleCallback() {
            @Override
            public void onTogglePanel() {
                togglePanel();
            }
        });
        directionKeyHandler.setChannelReverse(settingsManager.isChannelReverse());
        SettingsActivity.logOperation("【初始化】方向键处理器完成");
    }

    public void initLifecycleManager() {
        lifecycleManager.setActivity(activity);
        lifecycleManager.setAppCoreManager(appCoreManager);
        lifecycleManager.setPipManager(pipManager);
        lifecycleManager.setPlayerManager(playerManager);
        lifecycleManager.setSettingsManager(settingsManager);
        lifecycleManager.setScreenRatioManager(screenRatioManager);
        lifecycleManager.setDisplayManager(displayManager);
        lifecycleManager.setRemoteManager(remoteManager);
        lifecycleManager.setChannelPanelController(channelPanelController);
        lifecycleManager.setInfoDisplayManager(infoDisplayManager);
        lifecycleManager.setChannelNumberManager(channelNumberManager);
        lifecycleManager.setChannelPlayManager(channelPlayManager);
        lifecycleManager.setPanelAutoHideManager(panelAutoHideManager);
        lifecycleManager.setAutoSkipManager(autoSkipManager);
        SettingsActivity.logOperation("【初始化】生命周期管理器完成");
    }

    public void initKeyDispatcher() {
        keyDispatcher.setPipManager(pipManager);
        keyDispatcher.setPanelAutoHideManager(panelAutoHideManager);
        keyDispatcher.setRemoteManager(remoteManager);
        keyDispatcher.setChannelNumberManager(channelNumberManager);
        keyDispatcher.setChannelPanelController(channelPanelController);
        keyDispatcher.setDirectionKeyHandler(directionKeyHandler);
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

    public void initBackPressHandler() {
        backPressHandler.setPipManager(pipManager);
        backPressHandler.setChannelNumberManager(channelNumberManager);
        backPressHandler.setRemoteManager(remoteManager);
        backPressHandler.setChannelPanelController(channelPanelController);
        backPressHandler.setPlayerView(playerView);
        backPressHandler.setOnBackPressListener(new BackPressHandler.OnBackPressListener() {
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
                        logHelper.log("【" + (fromCache ? "缓存" : "网络") + "】直播源加载完成，频道数：" + channels.size());
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
                            logHelper.log("【缓存】使用缓存数据继续播放");
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
                        logHelper.log("【加载】超时，自动隐藏加载动画");
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

    private void togglePanel() {
        channelPanelController.togglePanel();
        syncRemoteMode();
    }

    private void syncRemoteMode() {
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            remoteManager.setMode(TvRemoteManager.Mode.CHANNEL_PANEL_MODE);
            remoteManager.setRightPanelOpen(channelPanelController.isRightPanelOpen());
        } else {
            remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
        }
    }

    public void release() {
        activity = null;
        displayManager = null;
        infoDisplayManager = null;
        appConfig = null;
        settingsManager = null;
        autoSkipManager = null;
        panelAutoHideManager = null;
        channelPlayManager = null;
        directionKeyHandler = null;
        lifecycleManager = null;
        keyDispatcher = null;
        backPressHandler = null;
        playerView = null;
        channelPanelController = null;
        remoteManager = null;
        pipManager = null;
        playerManager = null;
        playerStateListener = null;
        screenRatioManager = null;
        gestureManager = null;
        keyEventManager = null;
        channelNumberManager = null;
        appCoreManager = null;
        logHelper = null;
        listener = null;
    }
}
