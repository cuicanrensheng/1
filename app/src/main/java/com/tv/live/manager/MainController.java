package com.tv.live.manager;
import android.view.KeyEvent;
import com.tv.live.Channel;
import java.util.List;
public class MainController {
    private Context context;
    private ChannelPanelController channelPanelController;
    private TvRemoteManager remoteManager;
    private InfoDisplayManager infoDisplayManager;
    private TVPlayerManager playerManager;
    private AppConfig appConfig;
    private PlayerStateListenerImpl playerStateListener;
    private boolean channelReverse = false;
    private boolean numberChannelEnable = true;
    private boolean epgEnable = true;
    private boolean autoUpdateSource = true;
    private int currentPlayIndex = 0;
    private OnPlayControlListener playControlListener;
    private OnPanelControlListener panelControlListener;
    public interface OnPlayControlListener {
        void onPlayChannel(Channel channel, int index);
    }
    public interface OnPanelControlListener {
        void onTogglePanel();
        void onRequestFocus();
    }
    public MainController(
            Context context,
            ChannelPanelController channelPanelController,
            TvRemoteManager remoteManager,
            InfoDisplayManager infoDisplayManager,
            TVPlayerManager playerManager,
            AppConfig appConfig,
            PlayerStateListenerImpl playerStateListener
    ) {
        this.context = context.getApplicationContext();
        this.channelPanelController = channelPanelController;
        this.remoteManager = remoteManager;
        this.infoDisplayManager = infoDisplayManager;
        this.playerManager = playerManager;
        this.appConfig = appConfig;
        this.playerStateListener = playerStateListener;
    }
    // ===================== 修复后的按键分发 =====================
    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        // 1. 数字选台优先，不影响导航
        if (remoteManager.handleNumberKey(keyCode)) {
            return true;
        }
        // 核心修复：面板打开时，优先交给面板做焦点移动，不再直接切台吞键
        if (channelPanelController.isPanelOpen()) {
            boolean consume = channelPanelController.dispatchKeyEvent(keyCode);
            if (consume) {
                channelPanelController.resetAutoHide();
                return true;
            }
        }
        // 面板关闭，才执行全局切台逻辑
        return handleDirectionKey(keyCode);
    }
    private boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (channelReverse) {
                    playNext();
                } else {
                    playPrev();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channelReverse) {
                    playPrev();
                } else {
                    playNext();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                togglePanel();
                return true;
            default:
                return false;
        }
    }
    public boolean handleBackPressed() {
        if (remoteManager.isNumberInputting()) {
            remoteManager.cancelNumberInput();
            return true;
        }
        if (channelPanelController.handleBackPressed()) {
            if (panelControlListener != null) {
                panelControlListener.onRequestFocus();
            }
            return true;
        }
        return false;
    }
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
        playerStateListener.setCurrentChannelName(channel.getName());
        appConfig.setLastPlayIndex(index);
        playerManager.playUrl(channel.getPlayUrl());
        TVPlayerManager.LiveInfo live = playerManager.getLiveInfo();
        infoDisplayManager.showInfoBar(channel, live);
        if (playControlListener != null) {
            playControlListener.onPlayChannel(channel, index);
        }
    }
    public void togglePanel() {
        channelPanelController.togglePanel();
        if (panelControlListener != null) {
            panelControlListener.onTogglePanel();
        }
    }
    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }
    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
        channelPanelController.setCurrentPlayIndex(index);
    }
    public void loadSettings() {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        epgEnable = sp.getBoolean("epg_enable", true);
        channelReverse = sp.getBoolean("channel_reverse", false);
        numberChannelEnable = sp.getBoolean("numberChannelEnable", true);
        autoUpdateSource = sp.getBoolean("auto_update_source", true);
        if (remoteManager != null) {
            remoteManager.setNumberChannelEnable(numberChannelEnable);
        }
        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(epgEnable);
        }
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
    public void setOnPlayControlListener(OnPlayControlListener listener) {
        this.playControlListener = listener;
    }
    public void setOnPanelControlListener(OnPanelControlListener listener) {
        this.panelControlListener = null;
    }
    public void release() {
        context = null;
        channelPanelController = null;
        remoteManager = null;
        infoDisplayManager = null;
        playerManager = null;
        appConfig = null;
        playerStateListener = null;
        playControlListener = null;
        panelControlListener = null;
    }
}
