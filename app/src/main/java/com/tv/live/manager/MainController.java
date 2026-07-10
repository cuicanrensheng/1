package com.tv.live.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import com.tv.live.Channel;
import com.tv.live.SettingsActivity;
import com.tv.live.TVPlayerManager;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;

import java.util.ArrayList;
import java.util.List;

public class MainController {

    private static final int MAX_LOG_COUNT = 100;

    private Context context;
    private ChannelPanelController channelPanelController;
    private InfoDisplayManager infoDisplayManager;
    private TVPlayerManager playerManager;
    private AppConfig appConfig;
    private PlayerStateListenerImpl playerStateListener;

    private boolean channelReverse = false;
    private boolean numberChannelEnable = true;
    private boolean epgEnable = true;
    private boolean autoUpdateSource = true;

    private int currentPlayIndex = 0;

    private static List<String> logList = new ArrayList<>();

    private OnPlayControlListener playControlListener;

    public interface OnPlayControlListener {
        void onPlayChannel(Channel channel, int index);
    }

    // 构造函数移除 remoteManager 和 panelControlListener 相关
    public MainController(
            Context context,
            ChannelPanelController channelPanelController,
            InfoDisplayManager infoDisplayManager,
            TVPlayerManager playerManager,
            AppConfig appConfig,
            PlayerStateListenerImpl playerStateListener
    ) {
        this.context = context.getApplicationContext();
        this.channelPanelController = channelPanelController;
        this.infoDisplayManager = infoDisplayManager;
        this.playerManager = playerManager;
        this.appConfig = appConfig;
        this.playerStateListener = playerStateListener;
    }

    // ====================================================================
    // 1. 播放控制（核心业务）
    // ====================================================================

    public void playPrev() {
        channelPanelController.playPrev();
    }

    public void playNext() {
        channelPanelController.playNext();
    }

    public void playChannel(int index) {
        channelPanelController.playChannel(index);
    }

    public void doPlayChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;

        log("========================================");
        log("【播放】频道名称：" + channel.getName());
        log("【播放】播放地址：" + channel.getPlayUrl());
        log("【播放】当前索引：" + index);
        log("========================================");

        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        playerManager.playUrl(channel.getPlayUrl());

        TVPlayerManager.LiveInfo live = playerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);

        if (playControlListener != null) {
            playControlListener.onPlayChannel(channel, index);
        }
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
        channelPanelController.setCurrentPlayIndex(index);
    }

    // ====================================================================
    // 2. 设置管理（仅保留数据读取，移除遥控器相关调用）
    // ====================================================================

    public void loadSettings() {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epgEnable = sp.getBoolean("epg_enable", true);
        channelReverse = sp.getBoolean("channel_reverse", false);
        numberChannelEnable = sp.getBoolean("number_channel_enable", true);
        autoUpdateSource = sp.getBoolean("auto_update_source", true);

        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epgEnable);
        }

        log("【设置】EPG开关：" + epgEnable);
        log("【设置】切台反转：" + channelReverse);
        log("【设置】数字选台：" + numberChannelEnable);
        log("【设置】自动更新源：" + autoUpdateSource);
    }

    public boolean isChannelReverse() {
        return channelReverse;
    }

    public boolean isNumberChannelEnable() {
        return numberChannelEnable;
    }

    public boolean isEpgEnable() {
        return epgEnable;
    }

    public boolean isAutoUpdateSource() {
        return autoUpdateSource;
    }

    // ====================================================================
    // 3. 日志管理（修复：使用 Log.d 替代 SettingsActivity.log）
    // ====================================================================

    public static void log(String msg) {
        logList.add(0, msg);
        while (logList.size() > MAX_LOG_COUNT) {
            logList.remove(logList.size() - 1);
        }
        // 原为 SettingsActivity.log(msg)，因 SettingsActivity 无此静态方法，改为标准 Log
        Log.d("MainController", msg);
    }

    public static List<String> getLogList() {
        return logList;
    }

    public static void clearLog() {
        logList.clear();
    }

    // ====================================================================
    // 4. 监听器设置（仅保留播放回调）
    // ====================================================================

    public void setOnPlayControlListener(OnPlayControlListener listener) {
        this.playControlListener = listener;
    }

    // ====================================================================
    // 5. 资源释放
    // ====================================================================

    public void release() {
        context = null;
        channelPanelController = null;
        infoDisplayManager = null;
        playerManager = null;
        appConfig = null;
        playerStateListener = null;
        playControlListener = null;
    }
}
