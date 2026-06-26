package com.tv.live;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;

import androidx.media3.ui.PlayerView;

import com.tv.live.manager.ChannelPanelController;
import com.tv.live.manager.InfoDisplayManager;

import java.util.List;

/**
 * 画中画(PIP)核心管理器
 * 作用：统一管理画中画的初始化、开关、状态回调、兼容性判断、生命周期处理
 * 设计：单例模式，全局唯一实例，避免重复创建
 *
 * 【2026-06-25 增强：抽离 MainActivity 中的画中画辅助方法】
 * 【修改说明】
 * 把 MainActivity 里的画中画相关辅助方法抽离到这里，
 * 包括：隐藏所有UI、保持播放、打印View尺寸、打印窗口尺寸。
 * 这样 MainActivity 更清爽，画中画逻辑更集中。
 *
 * 【2026-06-26 增强：合并剩余画中画辅助方法】
 * 【修改说明】
 * 把 MainActivity 中剩余的画中画辅助方法全部合并到这里，
 * 包括：恢复播放、构建画中画参数、进入画中画便捷方法。
 * MainActivity 只保留生命周期回调，画中画逻辑全部集中在此。
 *
 * 【2026-06-26 增强：合并画中画排查日志】
 * 【修改说明】
 * 把 MainActivity.onUserLeaveHint() 中的画中画排查日志合并到这里，
 * 新增 debugLogEnabled 开关，统一管理调试日志输出。
 * MainActivity 只需一行调用，排查日志由管理器自己输出。
 */
public class PictureInPictureManager {

    private static final String LOG_PREFIX = "【画中画】";
    private static final String DEBUG_PREFIX = "【画中画排查】";

    private static PictureInPictureManager instance;
    private final Context appContext;

    private boolean pipEnabled = false;
    private boolean isInPipMode = false;
    private boolean isPipEntering = false;
    private boolean onStopCalled = false;

    // ✅ 2026-06-26 新增：调试日志开关
    private boolean debugLogEnabled = true;

    private OnPipListener listener;

    public static PictureInPictureManager getInstance(Context context) {
        if (instance == null) {
            instance = new PictureInPictureManager(context.getApplicationContext());
        }
        return instance;
    }

    private PictureInPictureManager(Context context) {
        this.appContext = context;
    }

    public interface OnPipListener {
        void onPipModeChanged(boolean inPip);
    }

    // ====================================================================
    // ✅ 2026-06-26 新增：调试日志开关控制
    // ====================================================================
    /**
     * 设置调试日志开关
     *
     * 【作用】
     * 控制是否输出详细的画中画排查日志。
     * 开启后会在进入画中画的各个环节输出详细状态，便于排查问题。
     *
     * @param enabled true=开启调试日志，false=关闭
     */
    public void setDebugLogEnabled(boolean enabled) {
        this.debugLogEnabled = enabled;
        logDebug("调试日志：" + (enabled ? "✅ 已开启" : "❌ 已关闭"));
    }

    /**
     * 输出调试日志（只有开启时才输出）
     */
    private void logDebug(String msg) {
        if (!debugLogEnabled) return;
        try {
            SettingsActivity.logOperation(DEBUG_PREFIX + msg);
        } catch (Exception e) {
        }
    }

    public boolean isPipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public void setPipEnabled(boolean enabled) {
        this.pipEnabled = enabled;
        log("开关设置：" + (enabled ? "✅ 开启" : "❌ 关闭"));
    }

    public boolean isPipEnabled() {
        return pipEnabled;
    }

    public boolean isInPipMode() {
        return isInPipMode;
    }

    public boolean isPipEntering() {
        return isPipEntering;
    }

    public void setPipEntering(boolean entering) {
        this.isPipEntering = entering;
        log("设置正在进入标记：" + entering);
    }

    public void setStopCalled(boolean stopCalled) {
        this.onStopCalled = stopCalled;
        log("设置onStop标记：" + stopCalled);
    }

    public boolean isStopCalled() {
        return onStopCalled;
    }

    public void setListener(OnPipListener listener) {
        this.listener = listener;
    }

    public boolean shouldEnterPip(boolean isExternalPlayer) {
        logDebug("========== 开始 ==========");
        logDebug("shouldEnterPip 被调用");

        if (!isPipSupported()) {
            logDebug("❌ 不满足：设备不支持画中画（API < 26）");
            logDebug("========== 结束 ==========");
            return false;
        }
        logDebug("✅ 满足：设备支持画中画");

        if (!pipEnabled) {
            logDebug("❌ 不满足：画中画开关未开启");
            logDebug("========== 结束 ==========");
            return false;
        }
        logDebug("✅ 满足：画中画开关已开启");

        if (isInPipMode) {
            logDebug("❌ 不满足：已在画中画模式");
            logDebug("========== 结束 ==========");
            return false;
        }
        logDebug("✅ 满足：不在画中画模式");

        if (isPipEntering) {
            logDebug("❌ 不满足：正在进入画中画中");
            logDebug("========== 结束 ==========");
            return false;
        }
        logDebug("✅ 满足：没有正在进入");

        if (isExternalPlayer) {
            logDebug("❌ 不满足：当前是外部播放器");
            logDebug("========== 结束 ==========");
            return false;
        }
        logDebug("✅ 满足：不是外部播放器");

        logDebug("✅ 所有条件满足，可以进入画中画");
        logDebug("========== 结束 ==========");
        return true;
    }

    public boolean shouldEnterPip() {
        return shouldEnterPip(false);
    }

    // ====================================================================
    // ✅ 2026-06-26 新增：构建默认画中画参数（16:9）
    // ====================================================================
    public PictureInPictureParams buildDefaultPipParams() {
        if (!isPipSupported()) {
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                builder.setAspectRatio(new Rational(16, 9));
                log("✅ 构建画中画参数成功（比例 16:9）");
                return builder.build();
            }
        } catch (Exception e) {
            log("❌ 构建画中画参数失败：" + e.getMessage());
        }
        return null;
    }

    // ====================================================================
    // ✅ 2026-06-26 新增：便捷进入画中画方法（含详细排查日志）
    // ====================================================================
    /**
     * 便捷进入画中画（使用默认 16:9 参数，含详细排查日志）
     *
     * 【为什么抽离到这里？】
     * 封装进入画中画的完整流程，包括详细的状态排查日志。
     * MainActivity 只需一行调用，排查日志由管理器自己输出。
     *
     * @param activity      当前 Activity
     * @param playerManager 播放器管理器（用于更新播放状态）
     * @param mainSwitch    MainActivity 中的开关状态（用于排查日志对比）
     * @return true=进入成功，false=进入失败
     */
    public boolean enterPip(Activity activity, TVPlayerManager playerManager, boolean mainSwitch) {
        logDebug("========== 开始 ==========");
        logDebug("onUserLeaveHint 被调用");

        if (activity == null) {
            logDebug("❌ Activity 为 null");
            logDebug("========== 结束 ==========");
            return false;
        }

        // 输出 MainActivity 和 PIP管理器的开关状态对比
        logDebug("MainActivity开关状态：" + mainSwitch);
        logDebug("设备支持：" + isPipSupported());
        logDebug("PIP管理器开关：" + isPipEnabled());
        logDebug("已在画中画模式：" + isInPipMode());
        logDebug("正在进入画中画：" + isPipEntering());

        boolean shouldEnter = shouldEnterPip();
        if (shouldEnter) {
            logDebug("所有条件满足，尝试进入画中画...");
            boolean result = enterPipInternal(activity, playerManager);
            logDebug("进入结果：" + (result ? "✅ 成功" : "❌ 失败"));
            logDebug("========== 结束 ==========");
            return result;
        } else {
            logDebug("❌ 条件不满足，不进入画中画");
            logDebug("========== 结束 ==========");
            return false;
        }
    }

    /**
     * 便捷进入画中画（简化版，不传 mainSwitch）
     */
    public boolean enterPip(Activity activity, TVPlayerManager playerManager) {
        return enterPip(activity, playerManager, pipEnabled);
    }

    /**
     * 内部进入画中画方法（不含排查日志，由 enterPip() 调用）
     */
    private boolean enterPipInternal(Activity activity, TVPlayerManager playerManager) {
        log("========== 便捷进入画中画 ==========");
        if (!shouldEnterPip()) {
            log("❌ 条件不满足，不进入画中画");
            return false;
        }
        try {
            if (playerManager != null) {
                updatePlayState(true);
            }
            PictureInPictureParams params = buildDefaultPipParams();
            boolean result = enterPictureInPicture(activity, params);
            log("进入结果：" + (result ? "✅ 成功" : "❌ 失败"));
            return result;
        } catch (Exception e) {
            log("❌ 便捷进入画中画异常：" + e.getMessage());
        }
        return false;
    }

    public boolean enterPictureInPicture(Activity activity, PictureInPictureParams params) {
        log("========== 尝试进入画中画 ==========");
        if (!isPipSupported()) {
            log("❌ 失败：设备不支持");
            return false;
        }
        if (!pipEnabled) {
            log("❌ 失败：开关未开启");
            return false;
        }
        if (activity == null) {
            log("❌ 失败：Activity 为 null");
            return false;
        }
        if (activity.isFinishing()) {
            log("❌ 失败：Activity 正在销毁");
            return false;
        }
        try {
            isPipEntering = true;
            log("设置正在进入标记 = true");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.enterPictureInPictureMode(params);
                log("✅ 调用系统API进入画中画成功");
                return true;
            }
        } catch (Exception e) {
            log("❌ 进入画中画异常：" + e.getMessage());
            e.printStackTrace();
            isPipEntering = false;
        }
        return false;
    }

    public void handleOnPause(Runnable resumeAction, Runnable pauseAction) {
        log("========== onPause 处理 ==========");
        log("当前状态：isInPipMode=" + isInPipMode + "，isPipEntering=" + isPipEntering);
        if (!isPipSupported()) {
            log("设备不支持画中画，直接暂停");
            if (pauseAction != null) {
                pauseAction.run();
            }
            return;
        }
        if (isInPipMode || isPipEntering) {
            log("✅ 画中画模式，继续播放");
            if (resumeAction != null) {
                try {
                    resumeAction.run();
                    log("✅ 恢复播放执行成功");
                } catch (Exception e) {
                    log("❌ 恢复播放失败：" + e.getMessage());
                }
            }
        } else {
            log("普通模式，暂停播放");
            if (pauseAction != null) {
                try {
                    pauseAction.run();
                    log("✅ 暂停播放执行成功");
                } catch (Exception e) {
                    log("❌ 暂停播放失败：" + e.getMessage());
                }
            }
        }
        log("================================");
    }

    public void handleOnPause(Runnable resumeAction) {
        handleOnPause(resumeAction, null);
    }

    public void onPipModeChanged(Activity activity, boolean isInPip) {
        log("========== 模式变化回调 ==========");
        log("新模式：" + (isInPip ? "✅ 进入画中画" : "❌ 退出画中画"));
        this.isInPipMode = isInPip;
        this.isPipEntering = false;
        log("更新状态：isInPipMode=" + isInPip + "，isPipEntering=false");
        if (listener != null) {
            try {
                listener.onPipModeChanged(isInPip);
                log("✅ 监听器回调成功");
            } catch (Exception e) {
                log("❌ 监听器回调失败：" + e.getMessage());
            }
        }
        log("================================");
    }

    public void handleExitPip(Runnable releaseAction) {
        log("========== 退出画中画处理 ==========");
        log("onStopCalled = " + onStopCalled);
        if (!isPipSupported()) {
            log("设备不支持画中画，跳过");
            return;
        }
        if (onStopCalled) {
            log("用户关闭了应用，释放播放器");
            if (releaseAction != null) {
                try {
                    releaseAction.run();
                    log("✅ 释放播放器执行成功");
                } catch (Exception e) {
                    log("❌ 释放播放器失败：" + e.getMessage());
                }
            }
        } else {
            log("用户返回应用，继续播放（不释放）");
        }
        onStopCalled = false;
        log("重置 onStopCalled = false");
        log("================================");
    }

    public void updatePlayState(boolean isPlaying) {
        log("更新播放状态：" + (isPlaying ? "▶ 播放中" : "⏸ 已暂停"));
    }

    public void updateChannelInfo(int num, String name, String bitrate) {
        log("更新频道信息：" + num + " - " + name + " - " + bitrate);
    }

    // ====================================================================
    // ✅ 2026-06-25 新增：隐藏画中画模式下的所有UI
    // ====================================================================
    public void hideAllUi(ChannelPanelController channelPanelController,
                          InfoDisplayManager infoDisplayManager) {
        log("========== 隐藏所有UI（画中画模式） ==========");
        try {
            if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                channelPanelController.hidePanel();
                log("✅ 已隐藏频道面板");
            }
            if (infoDisplayManager != null) {
                infoDisplayManager.hideInfoBar();
                infoDisplayManager.hideChannelNum();
                log("✅ 已隐藏信息栏和频道号");
            }
        } catch (Exception e) {
            log("❌ 隐藏UI失败：" + e.getMessage());
        }
        log("================================");
    }

    // ====================================================================
    // ✅ 2026-06-25 新增：画中画模式下保持播放
    // ====================================================================
    public void keepPlaying(TVPlayerManager playerManager,
                            PlayerView playerView,
                            List<Channel> channelSourceList,
                            int currentPlayIndex) {
        log("========== 画中画保持播放 ==========");
        try {
            if (playerManager != null) {
                playerManager.resume();
                log("✅ 第一重：调用 resume() 恢复播放");
                if (playerView != null) {
                    playerManager.attachPlayerView(playerView);
                    playerManager.resume();
                    log("✅ 第二重：重新绑定 PlayerView 后再次恢复");
                }
            }
        } catch (Exception e) {
            log("❌ 前两重恢复播放失败：" + e.getMessage());
            try {
                if (channelSourceList != null
                        && currentPlayIndex >= 0
                        && currentPlayIndex < channelSourceList.size()) {
                    Channel channel = channelSourceList.get(currentPlayIndex);
                    if (channel != null && channel.getPlayUrl() != null) {
                        playerManager.playUrl(channel.getPlayUrl());
                        log("✅ 第三重（兜底）：重新加载当前频道");
                    }
                }
            } catch (Exception e2) {
                log("❌ 兜底播放也失败：" + e2.getMessage());
            }
        }
        log("================================");
    }

    // ====================================================================
    // ✅ 2026-06-26 新增：恢复播放（简单版）
    // ====================================================================
    public void resumePlayback(TVPlayerManager playerManager) {
        try {
            if (playerManager != null) {
                playerManager.resume();
                log("✅ 恢复播放成功");
            }
        } catch (Exception e) {
            log("❌ 恢复播放失败：" + e.getMessage());
        }
    }

    // ====================================================================
    // ✅ 2026-06-25 新增：打印 View 的详细尺寸信息（调试用）
    // ====================================================================
    public void logViewSize(String tag, View view) {
        if (view == null) {
            log("尺寸】" + tag + "：View 为 null");
            return;
        }
        try {
            log("尺寸】" + tag + "位置：left=" + view.getLeft()
                    + "，top=" + view.getTop()
                    + "，right=" + view.getRight()
                    + "，bottom=" + view.getBottom());
            log("尺寸】" + tag + "尺寸：宽=" + view.getWidth()
                    + "，高=" + view.getHeight());
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) {
                String widthStr = lp.width == ViewGroup.LayoutParams.MATCH_PARENT ? "MATCH_PARENT(-1)" :
                                  lp.width == ViewGroup.LayoutParams.WRAP_CONTENT ? "WRAP_CONTENT(-2)" :
                                  String.valueOf(lp.width);
                String heightStr = lp.height == ViewGroup.LayoutParams.MATCH_PARENT ? "MATCH_PARENT(-1)" :
                                   lp.height == ViewGroup.LayoutParams.WRAP_CONTENT ? "WRAP_CONTENT(-2)" :
                                   String.valueOf(lp.height);
                log("尺寸】" + tag + "布局参数：width=" + widthStr
                        + "，height=" + heightStr);
            }
            int visibility = view.getVisibility();
            String visStr = visibility == View.VISIBLE ? "VISIBLE" :
                            visibility == View.INVISIBLE ? "INVISIBLE" : "GONE";
            log("尺寸】" + tag + "可见性：" + visStr);
        } catch (Exception e) {
            log("尺寸】" + tag + "获取尺寸失败：" + e.getMessage());
        }
    }

    // ====================================================================
    // ✅ 2026-06-25 新增：打印窗口和屏幕尺寸（调试用）
    // ====================================================================
    public void logWindowSize(Activity activity) {
        if (activity == null) {
            log("尺寸】Activity 为 null，无法获取窗口尺寸");
            return;
        }
        try {
            Rect rect = new Rect();
            activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(rect);
            log("尺寸】窗口可见区域：宽=" + rect.width() + "，高=" + rect.height());
            DisplayMetrics metrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
            log("尺寸】屏幕尺寸：宽=" + metrics.widthPixels + "，高=" + metrics.heightPixels);
            View decorView = activity.getWindow().getDecorView();
            log("尺寸】DecorView：宽=" + decorView.getWidth() + "，高=" + decorView.getHeight());
        } catch (Exception e) {
            log("尺寸】获取窗口尺寸失败：" + e.getMessage());
        }
    }

    public void release() {
        log("========== 释放资源 ==========");
        listener = null;
        isInPipMode = false;
        isPipEntering = false;
        onStopCalled = false;
        log("✅ 资源释放完成");
    }

    private void log(String msg) {
        try {
            SettingsActivity.logOperation(LOG_PREFIX + msg);
        } catch (Exception e) {
        }
    }
}
