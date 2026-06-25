package com.tv.live;

import android.app.Activity;
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
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 onPause、onStop、onResume、onWindowFocusChanged、onDestroy
 * 等生命周期方法里的逻辑抽离到这里，统一管理生命周期相关的逻辑。
 *
 * 【2026-06-25 合并：UiInitializer】
 * 【合并说明】
 * 把 UiInitializer 的 UI 初始化逻辑合并到这里，减少文件数量。
 * 原来的 UiInitializer 是独立的单例，现在直接作为 LifecycleManager 的一部分。
 * 生命周期和初始化本来就是紧密相关的，放在一起更合理。
 *
 * 【职责】
 * - 初始化基础配置（屏幕方向、全屏、保持屏幕常亮等）
 * - 初始化各个 Manager
 * - 绑定各个 Manager 之间的关系
 * - 设置各种监听器和回调
 * - onPause：暂停相关逻辑（画中画保持播放等）
 * - onStop：停止相关逻辑
 * - onResume：恢复相关逻辑（重新加载设置、恢复播放等）
 * - onWindowFocusChanged：窗口焦点变化逻辑
 * - onDestroy：释放资源
 */
public class LifecycleManager {

    private static LifecycleManager instance;

    private Activity activity;

    private AppCoreManager appCoreManager;
    private PictureInPictureManager pipManager;
    private TVPlayerManager playerManager;
    private SettingsManager settingsManager;
    private ScreenRatioManager screenRatioManager;
    private DisplayManager displayManager;
    private TvRemoteManager remoteManager;
    private ChannelPanelController channelPanelController;
    private InfoDisplayManager infoDisplayManager;
    private ChannelNumberManager channelNumberManager;
    private ChannelPlayManager channelPlayManager;
    private PanelAutoHideManager panelAutoHideManager;

    private boolean isOpeningSettings = false;

    // ====================================================================
    // ✅ 2026-06-25 合并：UiInitializer - 初始化相关
    // ====================================================================
    private AppConfig appConfig;
    private PlayerStateListenerImpl playerStateListener;
    private GestureManager gestureManager;
    private KeyEventManager keyEventManager;
    private KeyDispatcher keyDispatcher;
    private PlayerView playerView;

    /**
     * 初始化完成监听器
     * 【2026-06-25 合并：从 UiInitializer 移过来】
     */
    public interface OnInitCompleteListener {
        void onInitComplete();
    }

    private OnInitCompleteListener initCompleteListener;

    /**
     * 设置初始化完成监听器
     * 【2026-06-25 合并：从 UiInitializer 移过来】
     */
    public void setOnInitCompleteListener(OnInitCompleteListener listener) {
        this.initCompleteListener = listener;
    }

    // ===== 各种 getter 方法 =====
    public DisplayManager getDisplayManager() { return displayManager; }
    public InfoDisplayManager getInfoDisplayManager() { return infoDisplayManager; }
    public AppConfig getAppConfig() { return appConfig; }
    public SettingsManager getSettingsManager() { return settingsManager; }
    public ChannelPlayManager getChannelPlayManager() { return channelPlayManager; }
    public KeyDispatcher getKeyDispatcher() { return keyDispatcher; }
    public PlayerView getPlayerView() { return playerView; }
    public ChannelPanelController getChannelPanelController() { return channelPanelController; }
    public TvRemoteManager getRemoteManager() { return remoteManager; }
    public PictureInPictureManager getPipManager() { return pipManager; }
    public TVPlayerManager getPlayerManager() { return playerManager; }
    public ScreenRatioManager getScreenRatioManager() { return screenRatioManager; }
    public GestureManager getGestureManager() { return gestureManager; }
    public KeyEventManager getKeyEventManager() { return keyEventManager; }
    public ChannelNumberManager getChannelNumberManager() { return channelNumberManager; }
    public AppCoreManager getAppCoreManager() { return appCoreManager; }
    public PanelAutoHideManager getPanelAutoHideManager() { return panelAutoHideManager; }

    /**
     * 初始化基础配置
     * 【2026-06-25 合并：从 UiInitializer 移过来】
     */
    public void initBaseConfig() {
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        displayManager = new DisplayManager(activity);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        SettingsActivity.logOperation("【初始化】基础配置完成");
    }

    /**
     * 初始化信息展示管理器
     * 【2026-06-25 合并：从 UiInitializer 移过来】
     */
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

    /**
     * 初始化配置管理器
     * 【2026-06-25 合并：从 UiInitializer 移过来】
     */
    public void initConfigManagers() {
        appConfig = AppConfig.getInstance(activity);
        settingsManager = SettingsManager.getInstance(activity);
        SettingsActivity.logOperation("【初始化】配置管理器完成");
    }

    /**
     * 初始化功能管理器
     * 【2026-06-25 合并：从 UiInitializer 移过来】
     */
    public void initFeatureManagers() {
        panelAutoHideManager = PanelAutoHideManager.getInstance();
        channelPlayManager = ChannelPlayManager.getInstance(activity);
        keyDispatcher = KeyDispatcher.getInstance();
        SettingsActivity.logOperation("【初始化】功能管理器完成");
    }

    /**
     * 初始化播放器视图
     * 【2026-06-25 合并：从 UiInitializer 移过来】
     */
    public void initPlayerView() {
        playerView = activity.findViewById(R.id.player_view);
        playerView.setUseController(false);
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) null);
        SettingsActivity.logOperation("【初始化】播放器视图完成");
    }

    /**
     * 初始化频道面板
     * 【2026-06-25 合并：从 UiInitializer 移过来】
     */
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

    /**
     * 初始化遥控器管理器
     * 【2026-06-25 合并：从 UiInitializer 移过来】
     */
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
           
