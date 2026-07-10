package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

public class TvRemoteManager {

    public enum Mode {
        PLAY_MODE,
        CHANNEL_PANEL_MODE,
        SETTINGS_MODE
    }

    // ===================== 删除 PanelFocus 枚举 =====================
    // public enum PanelFocus { ... }  // 已删除

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
        // void onPanelFocusChanged(PanelFocus newFocus);  // 删除该回调

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

    // ===================== 删除虚拟焦点变量 =====================
    // private PanelFocus currentPanelFocus = PanelFocus.LEFT_CHANNEL;
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

    private final Runnable hideChannelNumRunnable = new Runnable() {
        @Override
        public void run() {
            if (listener != null) {
                listener.onHideChannelNumber();
            }
        }
    };

    public TvRemoteManager() {
    }

    public void setMode(Mode mode) {
        this.currentMode = mode;
        // 删除 resetPanelFocus() 调用
        switch (mode) {
            case SETTINGS_MODE:
                resetSettingsFocus();
                break;
            case PLAY_MODE:
            case CHANNEL_PANEL_MODE:
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

    // ===================== 统一按键分发入口 =====================
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

        // 数字键统一处理（不管什么模式，都支持数字选台）
        if (handleNumberKey(keyCode)) {
            return true;
        }

        // ===================== 删除面板模式下的兜底调用 =====================
        // 面板模式下按键已由 dispatchChannelPanelKey 通过回调处理，无需再次调用
        // 播放模式下，若面板打开，已在 dispatchPlayKey 中处理
        // 因此此处仅保留 settings 模式可能的额外处理，但 settings 模式不需要兜底
        // 故完全移除下面的兜底代码
        // if (channelPanelController != null && currentMode != Mode.CHANNEL_PANEL_MODE) {
        //     if (channelPanelController.dispatchKeyEvent(keyCode)) {
        //         return true;
        //     }
        // }

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

    // ===================== 返回键统一处理 =====================
    public boolean handleBackPressed() {
        if (isInPipMode) {
            if (listener != null) {
                return listener.onPipBack();
            }
            return false;
        }

        // 数字输入中，取消输入
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

        // 如果面板打开且未被消费，尝试让面板控制器处理返回（可能关闭面板）
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

    // ===================== 模式同步 =====================
    public void syncMode() {
        if (channelPanelController == null) return;
        if (channelPanelController.isPanelOpen()) {
            if (currentMode != Mode.CHANNEL_PANEL_MODE) {
                setMode(Mode.CHANNEL_PANEL_MODE);
            }
            // 更新右侧面板状态（仅用于记录，不再影响焦点）
            setRightPanelOpen(channelPanelController.isRightPanelOpen());
        } else {
            if (currentMode != Mode.PLAY_MODE) {
                setMode(Mode.PLAY_MODE);
            }
        }
    }

    // ===================== 播放模式按键处理 =====================
    private boolean dispatchPlayKey(int keyCode) {
        // 如果面板打开，所有方向键/确定键都交给面板控制器
        if (channelPanelController != null && channelPanelController.isPanelOpen()) {
            // 面板打开时，将按键转发给面板控制器处理（包括上下左右、确定等）
            // 但返回键、菜单键等仍需单独处理
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    // 转发给面板控制器
                    if (channelPanelController.dispatchKeyEvent(keyCode)) {
                        return true;
                    }
                    // 如果面板未消费，则继续执行原有逻辑（例如确定键打开面板等）
                    break;
                default:
                    // 其他按键不拦截
                    break;
            }
            // 注意：数字键会由外部统一处理，不在这里拦截
        }

        // 面板关闭或按键未被面板消费，执行播放模式逻辑
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (listener != null) {
                    listener.onPlayChannelUp();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (listener != null) {
                    listener.onPlayChannelDown();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 如果是数字输入中，由数字处理逻辑确认，这里不处理
                if (isNumberInputting()) {
                    return false; // 让数字处理逻辑接管
                }
                if (listener != null) {
                    listener.onPlayTogglePanel();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (listener != null) {
                    listener.onPlayTogglePanel();
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

    // ===================== 面板模式按键处理 =====================
    private boolean dispatchChannelPanelKey(int keyCode) {
        // 只处理面板相关的按键，数字键由外部统一处理（已删除数字键处理）
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
                // 面板模式下菜单键直接打开设置
                if (listener != null) {
                    listener.onPlayOpenSettings();
                }
                return true;
            // 数字键不再处理，让它们落到统一数字处理
            default:
                return false;
        }
    }

    // ===================== 设置模式按键处理 =====================
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

    private boolean handleSettingsMoveUp() {
        if (settingsFocusPosition > 0) {
            settingsFocusPosition--;
            if (listener != null) {
                listener.onSettingsMoveUp();
                listener.onSettingsFocusChanged(settingsFocusPosition);
            }
            return true;
        } else {
            return false;
        }
    }

    private boolean handleSettingsMoveDown() {
        if (settingsFocusPosition < settingsItemCount - 1) {
            settingsFocusPosition++;
            if (listener != null) {
                listener.onSettingsMoveDown();
                listener.onSettingsFocusChanged(settingsFocusPosition);
            }
            return true;
        } else {
            return false;
        }
    }

    // ===================== 数字键处理（统一） =====================
    public boolean handleNumberKey(int keyCode) {
        if (!numberChannelEnable) return false;
        int num = keyCodeToNumber(keyCode);
        if (num == -1) return false;

        // 如果面板打开，可以允许数字选台，但不清除面板？根据需求，数字选台应该切换频道并可能关闭面板。
        // 这里只记录数字输入，不直接切台，由后续确认时执行。
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

    // ===================== 右侧面板状态（仅记录，不影响焦点） =====================
    public void setRightPanelOpen(boolean open) {
        this.isRightPanelOpen = open;
        // 删除 resetPanelFocus() 调用，焦点由真实 View 管理
    }

    // ===================== 删除所有焦点相关方法 =====================
    // public PanelFocus getCurrentPanelFocus() { ... }
    // public void setCurrentPanelFocus(PanelFocus focus) { ... }
    // public void resetPanelFocus() { ... }

    // ===================== 设置模式焦点管理（保留） =====================
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

    // ===================== 资源释放 =====================
    public void release() {
        channelNumHandler.removeCallbacks(channelNumConfirmRunnable);
        channelNumHandler.removeCallbacks(hideChannelNumRunnable);
        channelNumInput.setLength(0);
        listener = null;
        channelPanelController = null;
    }
}
