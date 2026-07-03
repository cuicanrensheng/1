package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import com.tv.live.SettingsActivity;

public class TvRemoteManager {

    public enum Mode {
        PLAY_MODE,
        CHANNEL_PANEL_MODE,
        SETTINGS_MODE
    }

    public enum PanelFocus {
        LEFT_GROUP,
        LEFT_CHANNEL,
        LEFT_EPG_BTN,
        RIGHT_BACK_BTN,
        RIGHT_CHANNEL,
        RIGHT_DATE,
        RIGHT_EPG
    }

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
        void onPanelFocusChanged(PanelFocus newFocus);

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

    private static final long CHANNEL_NUM_TIMEOUT = 2000;

    private Mode currentMode = Mode.PLAY_MODE;
    private OnRemoteActionListener listener;

    private PanelFocus currentPanelFocus = PanelFocus.LEFT_CHANNEL;
    private boolean isRightPanelOpen = false;

    private int settingsItemCount = 0;
    private int settingsFocusPosition = 0;

    private boolean isInPipMode = false;
    private ChannelPanelController channelPanelController;

    private final StringBuilder channelNumInput = new StringBuilder();
    private final Handler channelNumHandler = new Handler(Looper.getMainLooper());
    private boolean numberChannelEnable = true;
    private int totalChannelCount = 0;

    private final Runnable channelNumConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            confirmChannelNum();
        }
    };

    public TvRemoteManager() {
    }

    // 新增的空实现方法
    public void onRequestPlayFocus() {
        // 空实现，仅解决编译找不到符号
    }

    public void setMode(Mode mode) {
        this.currentMode = mode;
        SettingsActivity.logOperation("【遥控】切换模式：" + mode);
        switch (mode) {
            case CHANNEL_PANEL_MODE:
                resetPanelFocus();
                break;
            case SETTINGS_MODE:
                resetSettingsFocus();
                break;
            case PLAY_MODE:
            default:
                break;
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
        SettingsActivity.logOperation("【遥控】画中画模式：" + (inPipMode ? "进入" : "退出"));
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
                SettingsActivity.logOperation("【遥控-画中画】返回键 → 退到后台");
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

        if (channelPanelController != null) {
            if (channelPanelController.dispatchKeyEvent(keyCode)) {
                return true;
            }
        }

        return false;
    }

    public boolean handleBackPressed() {
        if (isInPipMode) {
            SettingsActivity.logOperation("【遥控-返回】画中画模式 → 退到后台");
            if (listener != null) {
                return listener.onPipBack();
            }
            return false;
        }

        if (isNumberInputting()) {
            SettingsActivity.logOperation("【遥控-返回】数字选台输入中 → 取消输入");
            cancelNumberInput();
            return true;
        }

        boolean handled = false;
        switch (currentMode) {
            case CHANNEL_PANEL_MODE:
                SettingsActivity.logOperation("【遥控-返回】面板模式 → 调用 onPanelBack()");
                if (listener != null) {
                    handled = listener.onPanelBack();
                }
                break;
            case SETTINGS_MODE:
                SettingsActivity.logOperation("【遥控-返回】设置模式 → 调用 onSettingsBack()");
                if (listener != null) {
                    handled = listener.onSettingsBack();
                }
                break;
            case PLAY_MODE:
            default:
                SettingsActivity.logOperation("【遥控-返回】播放模式 → 调用 onPlayBack()");
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
                SettingsActivity.logOperation("【遥控-返回】面板返回兜底 → handleBackPressed()");
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

    private boolean dispatchPlayKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                SettingsActivity.logOperation("【遥控-播放】上键 → 上一台");
                if (listener != null) {
                    listener.onPlayChannelUp();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                SettingsActivity.logOperation("【遥控-播放】下键 → 下一台");
                if (listener != null) {
                    listener.onPlayChannelDown();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (isNumberInputting()) {
                    SettingsActivity.logOperation("【遥控-播放】OK键 → 确认数字选台");
                    confirmChannelNum();
                    return true;
                }
                SettingsActivity.logOperation("【遥控-播放】OK键 → 切换面板");
                if (listener != null) {
                    listener.onPlayTogglePanel();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                SettingsActivity.logOperation("【遥控-播放】左右键 → 切换面板");
                if (listener != null) {
                    listener.onPlayTogglePanel();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                SettingsActivity.logOperation("【遥控-播放】菜单键 → 打开设置");
                if (listener != null) {
                    listener.onPlayOpenSettings();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                SettingsActivity.logOperation("【遥控-播放】返回键");
                if (listener != null) {
                    return listener.onPlayBack();
                }
                return false;
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                int number = keyCode - KeyEvent.KEYCODE_0;
                SettingsActivity.logOperation("【遥控-播放】数字键 → " + number);
                if (listener != null) {
                    listener.onPanelNumber(number);
                }
                return true;
            default:
                return false;
        }
    }

    private boolean dispatchChannelPanelKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                SettingsActivity.logOperation("【遥控-面板】上键 → 当前焦点：" + currentPanelFocus);
                if (listener != null) {
                    listener.onPanelMoveUp();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                SettingsActivity.logOperation("【遥控-面板】下键 → 当前焦点：" + currentPanelFocus);
                if (listener != null) {
                    listener.onPanelMoveDown();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return handlePanelLeftKey();
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return handlePanelRightKey();
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                SettingsActivity.logOperation("【遥控-面板】OK键 → 当前焦点：" + currentPanelFocus);
                if (listener != null) {
                    listener.onPanelConfirm();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                SettingsActivity.logOperation("【遥控-面板】返回键");
                if (listener != null) {
                    return listener.onPanelBack();
                }
                return false;
            case KeyEvent.KEYCODE_MENU:
                SettingsActivity.logOperation("【遥控-面板】菜单键 → 关闭面板");
                if (listener != null) {
                    listener.onPanelMenu();
                }
                return true;
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6:
            case KeyEvent.KEYCODE_7:
            case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                int number = keyCode - KeyEvent.KEYCODE_0;
                SettingsActivity.logOperation("【遥控-面板】数字键 → " + number);
                if (listener != null) {
                    listener.onPanelNumber(number);
                }
                return true;
            default:
                return false;
        }
    }

    private boolean handlePanelLeftKey() {
        PanelFocus oldFocus = currentPanelFocus;
        switch (currentPanelFocus) {
            case LEFT_EPG_BTN:
                currentPanelFocus = PanelFocus.LEFT_CHANNEL;
                break;
            case LEFT_CHANNEL:
                currentPanelFocus = PanelFocus.LEFT_GROUP;
                break;
            case RIGHT_EPG:
                currentPanelFocus = PanelFocus.RIGHT_DATE;
                break;
            case RIGHT_DATE:
                currentPanelFocus = PanelFocus.RIGHT_CHANNEL;
                break;
            case RIGHT_CHANNEL:
                currentPanelFocus = PanelFocus.RIGHT_BACK_BTN;
                break;
            default:
                SettingsActivity.logOperation("【遥控-面板】左键 → 已在最左侧，无法左移");
                return false;
        }
        SettingsActivity.logOperation("【遥控-面板】左键 → " + oldFocus + " → " + currentPanelFocus);
        if (listener != null) {
            listener.onPanelMoveLeft();
            listener.onPanelFocusChanged(currentPanelFocus);
        }
        return true;
    }

    private boolean handlePanelRightKey() {
        PanelFocus oldFocus = currentPanelFocus;
        switch (currentPanelFocus) {
            case LEFT_GROUP:
                currentPanelFocus = PanelFocus.LEFT_CHANNEL;
                break;
            case LEFT_CHANNEL:
                currentPanelFocus = PanelFocus.LEFT_EPG_BTN;
                break;
            case RIGHT_BACK_BTN:
                currentPanelFocus = PanelFocus.RIGHT_CHANNEL;
                break;
            case RIGHT_CHANNEL:
                currentPanelFocus = PanelFocus.RIGHT_DATE;
                break;
            case RIGHT_DATE:
                currentPanelFocus = PanelFocus.RIGHT_EPG;
                break;
            default:
                SettingsActivity.logOperation("【遥控-面板】右键 → 已在最右侧，无法右移");
                return false;
        }
        SettingsActivity.logOperation("【遥控-面板】右键 → " + oldFocus + " → " + currentPanelFocus);
        if (listener != null) {
            listener.onPanelMoveRight();
            listener.onPanelFocusChanged(currentPanelFocus);
        }
        return true;
    }

    private boolean dispatchSettingsKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return handleSettingsMoveUp();
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return handleSettingsMoveDown();
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                SettingsActivity.logOperation("【遥控-设置】OK键 → 第 " + settingsFocusPosition + " 项");
                if (listener != null) {
                    listener.onSettingsConfirm();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                SettingsActivity.logOperation("【遥控-设置】返回键 → 关闭设置");
                if (listener != null) {
                    return listener.onSettingsBack();
                }
                return false;
            case KeyEvent.KEYCODE_MENU:
                SettingsActivity.logOperation("【遥控-设置】菜单键 → 关闭设置");
                if (listener != null) {
                    listener.onSettingsMenu();
                }
                return true;
            default:
                return false;
        }
    }

    private boolean handleSettingsMoveUp() {
        if (settingsFocusPosition > 0) {
            settingsFocusPosition--;
            SettingsActivity.logOperation("【遥控-设置】上移 → 第 " + settingsFocusPosition + " 项");
            if (listener != null) {
                listener.onSettingsMoveUp();
                listener.onSettingsFocusChanged(settingsFocusPosition);
            }
            return true;
        } else {
            SettingsActivity.logOperation("【遥控-设置】上移 → 已在顶部");
            return false;
        }
    }

    private boolean handleSettingsMoveDown() {
        if (settingsFocusPosition < settingsItemCount - 1) {
            settingsFocusPosition++;
            SettingsActivity.logOperation("【遥控-设置】下移 → 第 " + settingsFocusPosition + " 项");
            if (listener != null) {
                listener.onSettingsMoveDown();
                listener.onSettingsFocusChanged(settingsFocusPosition);
            }
            return true;
        } else {
            SettingsActivity.logOperation("【遥控-设置】下移 → 已在底部");
            return false;
        }
    }

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
        SettingsActivity.logOperation("【数字选台】输入：" + channelNumInput);
        return true;
    }

    public void confirmChannelNum() {
        if (channelNumInput.length() == 0) return;
        try {
            int channelNum = Integer.parseInt(channelNumInput.toString());
            if (channelNum >= 1 && channelNum <= totalChannelCount) {
                int index = channelNum - 1;
                SettingsActivity.logOperation("【数字选台】切换到第 " + channelNum + " 频道");
                if (listener != null) {
                    listener.onChannelNumberSelected(index);
                }
            } else {
                SettingsActivity.logOperation("【数字选台】频道号不存在：" + channelNum);
            }
        } catch (NumberFormatException e) {
            SettingsActivity.logOperation("【数字选台】数字解析失败：" + channelNumInput);
        }
        channelNumInput.setLength(0);
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onHideChannelNumber();
                }
            }
        }, 1000);
    }

    public void cancelNumberInput() {
        if (channelNumInput.length() > 0) {
            channelNumInput.setLength(0);
            channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
            if (listener != null) {
                listener.onHideChannelNumber();
            }
            SettingsActivity.logOperation("【数字选台】取消输入");
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

    public void setRightPanelOpen(boolean open) {
        this.isRightPanelOpen = open;
        resetPanelFocus();
    }

    public PanelFocus getCurrentPanelFocus() {
        return currentPanelFocus;
    }

    public void setCurrentPanelFocus(PanelFocus focus) {
        this.currentPanelFocus = focus;
        SettingsActivity.logOperation("【遥控-面板】设置焦点：" + focus);
    }

    public void resetPanelFocus() {
        if (isRightPanelOpen) {
            currentPanelFocus = PanelFocus.RIGHT_CHANNEL;
        } else {
            currentPanelFocus = PanelFocus.LEFT_CHANNEL;
        }
        SettingsActivity.logOperation("【遥控-面板】重置焦点：" + currentPanelFocus);
    }

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
            SettingsActivity.logOperation("【遥控-设置】设置焦点：第 " + position + " 项");
        }
    }

    public void resetSettingsFocus() {
        settingsFocusPosition = 0;
        SettingsActivity.logOperation("【遥控-设置】重置焦点到第一项");
    }

    public void release() {
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumInput.setLength(0);
    }
}
