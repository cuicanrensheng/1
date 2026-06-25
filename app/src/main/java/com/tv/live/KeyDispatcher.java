package com.tv.live;

import android.view.KeyEvent;
import android.view.View;

import com.tv.live.manager.*;

/**
 * 按键分发器
 * 作用：统一管理 MainActivity 的按键分发和返回键处理逻辑
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 onKeyDown() 和 onBackPressed() 方法里的分发逻辑抽离到这里，
 * 统一管理按键事件的分发顺序和处理逻辑。
 *
 * 【分发顺序】
 * 1. 画中画模式下的特殊处理
 * 2. 重置面板自动隐藏计时
 * 3. TvRemoteManager（遥控器统一管理）
 * 4. ChannelNumberManager（数字选台）
 * 5. ChannelPanelController（频道面板）
 * 6. 方向键处理（上/下切台、左/右/OK 切换面板）
 * 7. KeyEventManager（其他按键）
 *
 * 【2026-06-25 合并：DirectionKeyHandler + BackPressHandler】
 * 【合并说明】
 * 把 DirectionKeyHandler 和 BackPressHandler 的逻辑都合并到这里，减少文件数量。
 * 原来三个独立的小管理器，现在合并成一个统一的按键分发器。
 *
 * 【为什么合并？】
 * 1. DirectionKeyHandler 和 BackPressHandler 本身就很小，每个只有几个方法
 * 2. 都是按键处理相关，逻辑上紧密相关
 * 3. 减少文件数量，让项目结构更清晰
 * 4. 避免重复的成员变量（三个类里都有 pipManager、channelNumberManager 等）
 */
public class KeyDispatcher {

    // ====================== 单例模式 ======================
    private static KeyDispatcher instance;

    private KeyDispatcher() {
    }

    public static synchronized KeyDispatcher getInstance() {
        if (instance == null) {
            instance = new KeyDispatcher();
        }
        return instance;
    }

    // ====================== 子管理器 ======================
    private PictureInPictureManager pipManager;
    private PanelAutoHideManager panelAutoHideManager;
    private TvRemoteManager remoteManager;
    private ChannelNumberManager channelNumberManager;
    private ChannelPanelController channelPanelController;
    private KeyEventManager keyEventManager;

    // ====================================================================
    // ✅ 2026-06-25 合并：DirectionKeyHandler - 方向键相关成员变量
    // ====================================================================
    /**
     * 切台反转开关
     * true = 反转（上键 = 下一台，下键 = 上一台）
     * false = 不反转（默认）
     */
    private boolean channelReverse = false;

    /**
     * 面板切换回调
     * 【作用】
     * 方向键切换面板时，通过回调通知外部（MainActivity）执行切换操作。
     * 因为 KeyDispatcher 不直接持有面板的引用，所以用回调的方式。
     */
    private PanelToggleCallback panelToggleCallback;

    // ====================================================================
    // ✅ 2026-06-25 合并：BackPressHandler - 返回键相关成员变量
    // ====================================================================
    /**
     * 播放器视图
     * 【作用】
     * 返回键关闭面板后，把焦点还给播放器视图。
     */
    private View playerView;

    /**
     * 返回键监听器
     * 【作用】
     * 返回键处理过程中，需要外部（MainActivity）配合的操作通过回调完成。
     */
    private OnBackPressListener backPressListener;

    // ====================== 监听器 ======================
    private OnKeyDispatcherListener listener;

    // ====================================================================
    // 接口定义
    // ====================================================================

    /**
     * 按键分发监听器
     * 【作用】
     * 按键分发过程中，需要外部（MainActivity）配合的操作通过回调完成。
     */
    public interface OnKeyDispatcherListener {
        boolean onPipBackKey();
        void onSuperKeyDown(int keyCode, KeyEvent event);
    }

    // ====================================================================
    // ✅ 2026-06-25 合并：DirectionKeyHandler - 面板切换回调接口
    // ====================================================================
    /**
     * 面板切换回调接口
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     */
    public interface PanelToggleCallback {
        void onTogglePanel();
    }

    // ====================================================================
    // ✅ 2026-06-25 合并：BackPressHandler - 返回键监听器接口
    // ====================================================================
    /**
     * 返回键监听器接口
     * 【2026-06-25 合并：从 BackPressHandler 移过来】
     */
    public interface OnBackPressListener {
        void onMoveTaskToBack();
        void onSyncRemoteMode();
        void onSuperBackPressed();
    }

    // ====================================================================
    // Setter 方法
    // ====================================================================

    public void setOnKeyDispatcherListener(OnKeyDispatcherListener listener) {
        this.listener = listener;
    }

    public void setPipManager(PictureInPictureManager manager) {
        this.pipManager = manager;
    }

    public void setPanelAutoHideManager(PanelAutoHideManager manager) {
        this.panelAutoHideManager = manager;
    }

    public void setRemoteManager(TvRemoteManager manager) {
        this.remoteManager = manager;
    }

    public void setChannelNumberManager(ChannelNumberManager manager) {
        this.channelNumberManager = manager;
    }

    public void setChannelPanelController(ChannelPanelController controller) {
        this.channelPanelController = controller;
    }

    // ====================================================================
    // ✅ 2026-06-25 合并：DirectionKeyHandler - 向后兼容方法
    // ====================================================================
    /**
     * 【2026-06-25 合并：保留 setDirectionKeyHandler 方法用于向后兼容】
     * 现在 DirectionKeyHandler 已经合并到 KeyDispatcher 里了，
     * 这个方法主要是为了不让旧代码报错。
     */
    @Deprecated
    public void setDirectionKeyHandler(Object handler) {
        // 空实现，向后兼容
        SettingsActivity.logOperation("【兼容】setDirectionKeyHandler 已废弃，DirectionKeyHandler 已合并到 KeyDispatcher");
    }

    public void setKeyEventManager(KeyEventManager manager) {
        this.keyEventManager = manager;
    }

    // ====================================================================
    // ✅ 2026-06-25 合并：DirectionKeyHandler - 方向键相关 setter
    // ====================================================================

    /**
     * 设置面板控制器
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     *
     * 【注意】
     * 这个方法和 setChannelPanelController() 功能一样，
     * 保留是为了向后兼容，推荐使用 setChannelPanelController()。
     */
    @Deprecated
    public void setPanelController(ChannelPanelController controller) {
        this.channelPanelController = controller;
    }

    /**
     * 设置切台反转开关
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     */
    public void setChannelReverse(boolean reverse) {
        this.channelReverse = reverse;
        SettingsActivity.logOperation("【设置】切台反转状态同步到 KeyDispatcher："
                + (reverse ? "开启" : "关闭"));
    }

    /**
     * 获取切台反转状态
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     */
    public boolean isChannelReverse() {
        return channelReverse;
    }

    /**
     * 设置面板切换回调
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     */
    public void setPanelToggleCallback(PanelToggleCallback callback) {
        this.panelToggleCallback = callback;
    }

    // ====================================================================
    // ✅ 2026-06-25 合并：BackPressHandler - 返回键相关 setter
    // ====================================================================

    /**
     * 【2026-06-25 合并：保留 setBackPressHandler 方法用于向后兼容】
     * 现在 BackPressHandler 已经合并到 KeyDispatcher 里了，
     * 这个方法主要是为了不让旧代码报错。
     */
    @Deprecated
    public void setBackPressHandler(Object handler) {
        // 空实现，向后兼容
        SettingsActivity.logOperation("【兼容】setBackPressHandler 已废弃，BackPressHandler 已合并到 KeyDispatcher");
    }

    /**
     * 设置播放器视图
     * 【2026-06-25 合并：从 BackPressHandler 移过来】
     */
    public void setPlayerView(View view) {
        this.playerView = view;
    }

    /**
     * 设置返回键监听器
     * 【2026-06-25 合并：从 BackPressHandler 移过来】
     */
    public void setOnBackPressListener(OnBackPressListener listener) {
        this.backPressListener = listener;
    }

    // ====================================================================
    // 核心方法：按键分发
    // ====================================================================
    /**
     * 分发按键事件
     *
     * 【分发顺序】
     * 1. 画中画模式下的特殊处理
     * 2. 重置面板自动隐藏计时
     * 3. TvRemoteManager（遥控器统一管理）
     * 4. ChannelNumberManager（数字选台）
     * 5. ChannelPanelController（频道面板）
     * 6. 方向键处理（上/下切台、左/右/OK 切换面板）
     * 7. KeyEventManager（其他按键）
     *
     * 【2026-06-25 修改：合并 DirectionKeyHandler 后直接调用自己的方法】
     * 【修改说明】
     * 原来调用 directionKeyHandler.handleDirectionKey()，
     * 现在 DirectionKeyHandler 已经合并进来了，直接调用自己的 handleDirectionKey()。
     */
    public boolean dispatchKeyEvent(int keyCode, KeyEvent event) {
        // 1. 画中画模式下的特殊处理
        if (pipManager != null && pipManager.isInPipMode()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (listener != null) {
                    return listener.onPipBackKey();
                }
                return true;
            }
            return false;
        }

        // 2. 重置面板自动隐藏计时
        if (panelAutoHideManager != null) {
            panelAutoHideManager.reset();
        }

        // 3. 遥控器统一管理
        if (remoteManager != null && remoteManager.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // 4. 数字选台
        if (channelNumberManager != null && channelNumberManager.handleNumberKey(keyCode)) {
            return true;
        }

        // 5. 频道面板（面板打开时才处理）
        if (channelPanelController != null && channelPanelController.dispatchKeyEvent(keyCode)) {
            return true;
        }

        // ✅ 2026-06-25 合并：方向键处理（原来调用 directionKeyHandler，现在直接调用自己的方法）
        // 6. 方向键处理（播放模式下）
        if (handleDirectionKey(keyCode)) {
            return true;
        }

        // 7. 其他按键
        if (keyEventManager != null && keyEventManager.dispatchKey(keyCode)) {
            return true;
        }

        return false;
    }

    // ====================================================================
    // ✅ 2026-06-25 合并：DirectionKeyHandler - 方向键处理方法
    // ====================================================================
    /**
     * 处理方向键（播放模式下）
     * 【2026-06-25 合并：从 DirectionKeyHandler 移过来】
     *
     * 【职责】
     * - 上/下键：切换频道
     * - 左/右键：切换面板显示/隐藏
     * - OK/确认键：切换面板显示/隐藏（或确认数字选台）
     *
     * @param keyCode 按键码
     * @return true 表示消费了事件
     */
    public boolean handleDirectionKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                SettingsActivity.logOperation("【按键】方向键上 → 反转状态："
                        + (channelReverse ? "开启" : "关闭"));
                if (channelPanelController != null) {
                    channelPanelController.switchUp();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                SettingsActivity.logOperation("【按键】方向键下 → 反转状态："
                        + (channelReverse ? "开启" : "关闭"));
                if (channelPanelController != null) {
                    channelPanelController.switchDown();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                // 如果正在输入数字选台，确认选台
                if (channelNumberManager != null && channelNumberManager.isInputting()) {
                    channelNumberManager.confirmChannelNum();
                    return true;
                }
                // 否则切换面板
                if (panelToggleCallback != null) {
                    panelToggleCallback.onTogglePanel();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 左右键切换面板
                if (panelToggleCallback != null) {
                    panelToggleCallback.onTogglePanel();
                }
                return true;

            default:
                return false;
        }
    }

    // ====================================================================
    // ✅ 2026-06-25 合并：BackPressHandler - 返回键处理方法
    // ====================================================================
    /**
     * 处理返回键
     * 【2026-06-25 合并：从 BackPressHandler 移过来】
     *
     * 【处理顺序】
     * 1. 画中画模式下：退到后台
     * 2. 数字选台输入中：取消输入
     * 3. 遥控器管理器处理
     * 4. 频道面板处理
     * 5. 都不处理：返回 false，由外部调用 super.onBackPressed()
     *
     * @return true 表示消费了事件，false 表示需要继续处理
     */
    public boolean handleBackPressed() {
        // 1. 画中画模式下：退到后台
        if (pipManager != null && pipManager.isInPipMode()) {
            if (backPressListener != null) {
                backPressListener.onMoveTaskToBack();
            }
            return true;
        }

        // 2. 数字选台输入中：取消输入
        if (channelNumberManager != null && channelNumberManager.isInputting()) {
            channelNumberManager.cancelInput();
            SettingsActivity.logOperation("【返回】取消数字选台输入");
            return true;
        }

        // 3. 遥控器管理器处理
        if (remoteManager != null) {
            if (remoteManager.dispatchKeyEvent(KeyEvent.KEYCODE_BACK)) {
                SettingsActivity.logOperation("【返回】遥控器管理器处理");
                return true;
            }
        }

        // 4. 频道面板处理
        if (channelPanelController != null && channelPanelController.handleBackPressed()) {
            if (playerView != null) {
                playerView.requestFocus();
            }
            if (backPressListener != null) {
                backPressListener.onSyncRemoteMode();
            }
            SettingsActivity.logOperation("【返回】频道面板处理");
            return true;
        }

        // 5. 都不处理，返回 false
        return false;
    }

    // ====================================================================
    // 资源释放
    // ====================================================================
    public void release() {
        pipManager = null;
        panelAutoHideManager = null;
        remoteManager = null;
        channelNumberManager = null;
        channelPanelController = null;
        keyEventManager = null;
        listener = null;

        // ✅ 2026-06-25 合并：DirectionKeyHandler 相关资源释放
        panelToggleCallback = null;
        channelReverse = false;

        // ✅ 2026-06-25 合并：BackPressHandler 相关资源释放
        playerView = null;
        backPressListener = null;
    }
}
