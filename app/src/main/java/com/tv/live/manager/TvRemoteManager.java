package com.tv.live.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import com.tv.live.Channel;
import com.tv.live.TVPlayerManager;
import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;

public class TvRemoteManager {

    public enum Mode {
        PLAY_MODE,
        CHANNEL_PANEL_MODE,
        SETTINGS_MODE
    }

    // ==================== 遥控器事件监听 ====================
    public interface OnRemoteActionListener {
        void onPlayChannelUp();
        void onPlayChannelDown();
        void onPlayTogglePanel();
        void onPlayOpenSettings();
        boolean onPlayBack();

        void onPanelMoveUp();
        void onPanelMoveDown();
        void onPanelMoveLeft();
        void onPanelMoveRight();
        void onPanelConfirm();
        boolean onPanelBack();
        void onPanelMenu();
        void onPanelNumber(int number);

        void onSettingsMoveUp();
        void onSettingsMoveDown();
        void onSettingsConfirm();
        boolean onSettingsBack();
        void onSettingsMenu();
        void onSettingsFocusChanged(int position);

        boolean onPipBack();
        void onRequestPlayFocus();

        void onChannelNumberSelected(int channelIndex);
        void onShowChannelNumber(String number);
        void onHideChannelNumber();
    }

    // ==================== 播放控制回调 ====================
    public interface OnPlayControlListener {
        void onPlayChannel(Channel channel, int index);
    }

    // ==================== 成员变量 ====================
    private Context context;
    private ChannelPanelController channelPanelController;
    private InfoDisplayManager infoDisplayManager;
    private TVPlayerManager playerManager;
    private AppConfig appConfig;
    private PlayerStateListenerImpl playerStateListener;

    private OnPlayControlListener playControlListener;
    private OnRemoteActionListener listener;

    // 播放状态（从 MainController 合并）
    private int currentPlayIndex = 0;
    private boolean channelReverse = false;

    // 遥控器状态
    private Mode currentMode = Mode.PLAY_MODE;
    private boolean isRightPanelOpen = false;
    private int settingsItemCount = 0;
    private int settingsFocusPosition = 0;
    private boolean isInPipMode = false;
    private boolean numberChannelEnable = true;
    private int totalChannelCount = 0;

    // 数字选台
    private final StringBuilder channelNumInput = new StringBuilder();
    private final Handler channelNumHandler = new Handler(Looper.getMainLooper());
    private static final long CHANNEL_NUM_TIMEOUT = 2000;

    private final Runnable channelNumConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            confirmChannelNum();
        }
    };

    private final Runnable hideChannelNumRunnable = new Runnable() {
        @Override
        public void run() {
            if (listener != null) {
                listener.onHideChannelNumber();
            }
        }
    };

    // ==================== 构造函数 ====================
    public TvRemoteManager(
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

    // ==================== 播放控制方法（从 MainController 合并） ====================
    public void playPrev() {
        if (channelPanelController != null) {
            channelPanelController.playPrev();
        }
    }

    public void playNext() {
        if (channelPanelController != null) {
            channelPanelController.playNext();
        }
    }

    public void playChannel(int index) {
        if (channelPanelController != null) {
            channelPanelController.playChannel(index);
        }
    }

    public void doPlayChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;

        Log.d("TvRemoteManager", "========================================");
        Log.d("TvRemoteManager", "【播放】频道名称：" + channel.getName());
        Log.d("TvRemoteManager", "【播放】播放地址：" + channel.getPlayUrl());
        Log.d("TvRemoteManager", "【播放】当前索引：" + index);
        Log.d("TvRemoteManager", "========================================");

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
        if (channelPanelController != null) {
            channelPanelController.setCurrentPlayIndex(index);
        }
    }

    public boolean isChannelReverse() {
        return channelReverse;
    }

    public void setOnPlayControlListener(OnPlayControlListener listener) {
        this.playControlListener = listener;
    }

    // ==================== 设置加载（从 MainController 合并） ====================
    public void loadSettings() {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        channelReverse = sp.getBoolean("channel_reverse", false);
        numberChannelEnable = sp.getBoolean("number_channel_enable", true);

        if (channelPanelController != null) {
            channelPanelController.setEpgEnable(sp.getBoolean("epg_enable", true));
        }

        Log.d("TvRemoteManager", "【设置】切台反转：" + channelReverse);
        Log.d("TvRemoteManager", "【设置】数字选台：" + numberChannelEnable);
    }

    // ==================== 遥控器分发方法（原有） ====================
    public void setMode(Mode mode) {
        this.currentMode = mode;
        if (mode == Mode.SETTINGS_MODE) {
            resetSettingsFocus();
        }
    }

    public Mode getCurrentMode() {
        return currentMode;
    }

    public void setOnRemoteActionListener(OnRemoteActionListener listener) {
        this.listener = listener;
    }

    public void setInPipMode(boolean inPipMode) {
        this.isInPipMode = inPipMode;
    }

    public void setChannelPanelController(ChannelPanelController controller) {
        this.channelPanelController = controller;
    }

    public void setNumberChannelEnable(boolean enable) {
        this.numberChannelEnable = enable;
        if (!enable && isNumberInputting()) {
            cancelNumberInput();
        }
    }

    public void setTotalChannelCount(int count) {
        this.totalChannelCount = count;
    }

    public boolean isNumberInputting() {
        return channelNumInput.length() > 0;
    }

    public boolean dispatchKeyEvent(int keyCode) {
        if (isInPipMode) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (listener != null) {
                    return listener.onPipBack();
                }
                return false;
            }
            return false;
        }

        if (channelPanelController != null) {
            channelPanelController.resetAutoHide();
        }

        boolean handled = false;
        switch (currentMode) {
            case CHANNEL_PANEL_MODE:
                handled = dispatchChannelPanelKey(keyCode);
                break;
            case SETTINGS_MODE:
                handled = dispatchSettingsKey(keyCode);
                break;
            case PLAY_MODE:
            default:
                handled = dispatchPlayKey(keyCode);
                break;
        }
        if (handled) {
            return true;
        }

        if (handleNumberKey(keyCode)) {
            return true;
        }

        return false;
    }

    public boolean dispatchKeyLongPress(int keyCode) {
        if (isInPipMode) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (listener != null) {
                listener.onPlayOpenSettings();
            }
            return true;
        }
        return false;
    }

    public boolean handleBackPressed() {
        if (isInPipMode) {
            if (listener != null) {
                return listener.onPipBack();
            }
            return false;
        }

        if (isNumberInputting()) {
            cancelNumberInput();
            return true;
        }

        boolean handled = false;
        switch (currentMode) {
            case CHANNEL_PANEL_MODE:
                if (listener != null) {
                    handled = listener.onPanelBack();
                }
                break;
            case SETTINGS_MODE:
                if (listener != null) {
                    handled = listener.onSettingsBack();
                }
                break;
            case PLAY_MODE:
            default:
                if (listener != null) {
                    handled = listener.onPlayBack();
                }
                break;
        }
        if (handled) {
            syncMode();
            return true;
        }

        if (channelPanelController != null) {
            if (channelPanelController.handleBackPressed()) {
                syncMode();
                if (listener != null) {
                    listener.onRequestPlayFocus();
                }
                return true;
            }
        }

        return false;
    }

    public void syncMode() {
        if (channelPanelController == null) return;
        if (channelPanelController.isPanelOpen()) {
            if (currentMode != Mode.CHANNEL_PANEL_MODE) {
                setMode(Mode.CHANNEL_PANEL_MODE);
            }
            setRightPanelOpen(channelPanelController.isRightPanelOpen());
        } else {
            if (currentMode != Mode.PLAY_MODE) {
                setMode(Mode.PLAY_MODE);
            }
        }
    }

    // ==================== 按键处理 ====================
    private boolean dispatchPlayKey(int keyCode) {
        // 如果面板打开，让面板控制器优先处理方向键和确定键
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (channelPanelController.dispatchKeyEvent(keyCode)) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }

        // 面板关闭或未消费的按键，执行播放模式逻辑
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (listener != null) {
                    listener.onPlayChannelUp();
                }
                // 直接切台（自动处理反转）
                if (channelReverse) {
                    playNext();
                } else {
                    playPrev();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (listener != null) {
                    listener.onPlayChannelDown();
                }
                if (channelReverse) {
                    playPrev();
                } else {
                    playNext();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (isNumberInputting()) {
                    return false; // 让数字处理逻辑接管
                }
                if (listener != null) {
                    listener.onPlayTogglePanel();
                }
                if (channelPanelController != null) {
                    channelPanelController.togglePanel();
                    syncMode();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (listener != null) {
                    listener.onPlayTogglePanel();
                }
                if (channelPanelController != null) {
                    channelPanelController.togglePanel();
                    syncMode();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_HELP:
                if (listener != null) {
                    listener.onPlayOpenSettings();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) {
                    return listener.onPlayBack();
                }
                return false;
            default:
                return false;
        }
    }

    private boolean dispatchChannelPanelKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (listener != null) {
                    listener.onPanelMoveUp();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (listener != null) {
                    listener.onPanelMoveDown();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (listener != null) {
                    listener.onPanelMoveLeft();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (listener != null) {
                    listener.onPanelMoveRight();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (listener != null) {
                    listener.onPanelConfirm();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) {
                    return listener.onPanelBack();
                }
                return false;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_HELP:
                if (listener != null) {
                    listener.onPlayOpenSettings();
                }
                return true;
            default:
                return false;
        }
    }

    private boolean dispatchSettingsKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return handleSettingsMoveUp();
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return handleSettingsMoveDown();
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (listener != null) {
                    listener.onSettingsConfirm();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) {
                    return listener.onSettingsBack();
                }
                return false;
            case KeyEvent.KEYCODE_MENU:
                if (listener != null) {
                    listener.onSettingsMenu();
                }
                return true;
            default:
                return false;
        }
    }

    // 首尾循环
    private boolean handleSettingsMoveUp() {
        if (settingsItemCount <= 0) return false;
        if (settingsFocusPosition > 0) {
            settingsFocusPosition--;
        } else {
            settingsFocusPosition = settingsItemCount - 1;
        }
        if (listener != null) {
            listener.onSettingsMoveUp();
            listener.onSettingsFocusChanged(settingsFocusPosition);
        }
        return true;
    }

    private boolean handleSettingsMoveDown() {
        if (settingsItemCount <= 0) return false;
        if (settingsFocusPosition < settingsItemCount - 1) {
            settingsFocusPosition++;
        } else {
            settingsFocusPosition = 0;
        }
        if (listener != null) {
            listener.onSettingsMoveDown();
            listener.onSettingsFocusChanged(settingsFocusPosition);
        }
        return true;
    }

    // ==================== 数字键处理 ====================
    public boolean handleNumberKey(int keyCode) {
        if (!numberChannelEnable) return false;
        int num = keyCodeToNumber(keyCode);
        if (num == -1) return false;
        channelNumInput.append(num);
        if (listener != null) {
            listener.onShowChannelNumber(channelNumInput.toString());
        }
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumHandler.postDelayed(channelNumConfirmRunnable, CHANNEL_NUM_TIMEOUT);
        return true;
    }

    public void confirmChannelNum() {
        if (channelNumInput.length() == 0) return;
        try {
            int channelNum = Integer.parseInt(channelNumInput.toString());
            if (channelNum >= 1 && channelNum <= totalChannelCount) {
                int index = channelNum - 1;
                if (listener != null) {
                    listener.onChannelNumberSelected(index);
                }
                // 直接切台
                playChannel(index);
            }
        } catch (NumberFormatException e) {
        }
        channelNumInput.setLength(0);
        channelNumHandler.removeCallbacks(hideChannelNumRunnable);
        channelNumHandler.postDelayed(hideChannelNumRunnable, 1000);
    }

    public void cancelNumberInput() {
        if (channelNumInput.length() > 0) {
            channelNumInput.setLength(0);
            channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
            if (listener != null) {
                listener.onHideChannelNumber();
            }
        }
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

    // ==================== 右侧面板状态 ====================
    public void setRightPanelOpen(boolean open) {
        this.isRightPanelOpen = open;
    }

    // ==================== 设置模式焦点 ====================
    public void setSettingsItemCount(int count) {
        this.settingsItemCount = count;
        if (settingsFocusPosition >= count) {
            settingsFocusPosition = count - 1;
        }
        if (settingsFocusPosition < 0) {
            settingsFocusPosition = 0;
        }
    }

    public int getSettingsItemCount() {
        return settingsItemCount;
    }

    public int getSettingsFocusPosition() {
        return settingsFocusPosition;
    }

    public void setSettingsFocusPosition(int position) {
        if (position >= 0 && position < settingsItemCount) {
            this.settingsFocusPosition = position;
        }
    }

    public void resetSettingsFocus() {
        settingsFocusPosition = 0;
    }

    // ==================== 资源释放 ====================
    public void release() {
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumHandler.removeCallbacks(hideChannelNumRunnable);
        channelNumInput.setLength(0);
        listener = null;
        channelPanelController = null;
        infoDisplayManager = null;
        playerManager = null;
        appConfig = null;
        playerStateListener = null;
        playControlListener = null;
        context = null;
    }
}
