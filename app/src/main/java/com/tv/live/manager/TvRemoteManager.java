package com.tv.live.manager;

import android.view.KeyEvent;

public class TvRemoteManager {

    public enum Mode {
        PLAY_MODE,
        CHANNEL_PANEL_MODE,
        SETTINGS_MODE
    }

    public interface OnRemoteActionListener {
        void onPlayChannelUp();
        void onPlayChannelDown();
        void onPlayTogglePanel();
        void onPlayOpenSettings();
        boolean onPlayBack();

        void onPanelConfirm();
        boolean onPanelBack();

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

    private Mode currentMode = Mode.PLAY_MODE;
    private OnRemoteActionListener listener;
    private boolean isInPipMode = false;
    private ChannelPanelController channelPanelController;

    // ✅ 恢复数字输入相关的成员变量（虽然不再使用，但保留以兼容旧调用）
    private boolean numberChannelEnable = true;
    private int totalChannelCount = 0;

    public TvRemoteManager() {
    }

    public void setMode(Mode mode) {
        this.currentMode = mode;
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

    // ✅ 恢复 setNumberChannelEnable
    public void setNumberChannelEnable(boolean enable) {
        this.numberChannelEnable = enable;
    }

    // ✅ 恢复 setTotalChannelCount
    public void setTotalChannelCount(int count) {
        this.totalChannelCount = count;
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

        boolean handled = false;
        switch (currentMode) {
            case CHANNEL_PANEL_MODE:
                if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                    handled = dispatchChannelPanelKey(keyCode);
                } else {
                    handled = false;
                }
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

        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        return false;
    }

    public boolean dispatchKeyLongPress(int keyCode) {
        if (isInPipMode) {
            return false;
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

        if (channelPanelController != null && channelPanelController.handleBackPressed()) {
            syncMode();
            if (listener != null) {
                listener.onRequestPlayFocus();
            }
            return true;
        }

        return false;
    }

    public void syncMode() {
        if (channelPanelController == null) return;
        if (channelPanelController.isPanelOpen()) {
            if (currentMode != Mode.CHANNEL_PANEL_MODE) {
                setMode(Mode.CHANNEL_PANEL_MODE);
            }
        } else {
            if (currentMode != Mode.PLAY_MODE) {
                setMode(Mode.PLAY_MODE);
            }
        }
    }

    private boolean dispatchPlayKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (listener != null) listener.onPlayChannelUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (listener != null) listener.onPlayChannelDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (listener != null) listener.onPlayTogglePanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (listener != null) listener.onPlayTogglePanel();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_HELP:
                if (listener != null) listener.onPlayOpenSettings();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) return listener.onPlayBack();
                return false;
            default:
                return false;
        }
    }

    private boolean dispatchChannelPanelKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (listener != null) listener.onPanelConfirm();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) return listener.onPanelBack();
                return false;
            case KeyEvent.KEYCODE_MENU:
                if (listener != null) listener.onPlayOpenSettings();
                return true;
            default:
                return false;
        }
    }

    private boolean dispatchSettingsKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (listener != null) listener.onSettingsMoveUp();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (listener != null) listener.onSettingsMoveDown();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (listener != null) listener.onSettingsConfirm();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) return listener.onSettingsBack();
                return false;
            case KeyEvent.KEYCODE_MENU:
                if (listener != null) listener.onSettingsMenu();
                return true;
            default:
                return false;
        }
    }

    public void release() {
        listener = null;
        channelPanelController = null;
    }
}
