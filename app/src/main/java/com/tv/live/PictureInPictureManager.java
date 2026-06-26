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
 */
public class PictureInPictureManager {
    private static final String LOG_PREFIX = "【画中画】";
    private static PictureInPictureManager instance;
    private final Context appContext;
    private boolean pipEnabled = false;
    private boolean isInPipMode = false;
    private boolean isPipEntering = false;
    private boolean onStopCalled = false;
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
        log("========== 进入条件检查 ==========");
        if (!isPipSupported()) {
            log("❌ 不满足：设备不支持画中画（API < 26）");
            log("================================");
            return false;
        }
        log("✅ 满足：设备支持画中画");
        if (!pipEnabled) {
            log("❌ 不满足：画中画开关未开启");
            log("================================");
            return false;
        }
        log("✅ 满足：画中画开关已开启");
        if (isInPipMode) {
            log("❌ 不满足：已在画中画模式");
            log("================================");
            return false;
        }
        log("✅ 满足：不在画中画模式");
        if (isPipEntering) {
            log("❌ 不满足：正在进入画中画中");
            log("================================");
            return false;
        }
        log("✅ 满足：没有正在进入");
        if (isExternalPlayer) {
            log("❌ 不满足：当前是外部播放器");
            log("================================");
            return false;
        }
        log("✅ 满足：不是外部播放器");
        log("✅ 所有条件满足，可以进入画中画");
        log("================================");
        return true;
    }
    public boolean shouldEnterPip() {
        return shouldEnterPip(false);
    }
    // ====================================================================
    // ✅ 2026-06-26 新增：构建默认画中画参数（16:9）
    // ====================================================================
    /**
     * 构建默认的画中画参数（16:9 比例）
     *
     * 【为什么抽离到这里？】
     * 原来在 MainActivity.onUserLeaveHint() 里，
     * 现在统一放到画中画管理器中，MainActivity 直接调用。
     *
     * @return PictureInPictureParams 对象，不支持时返回 null
     */
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
    // ✅ 2026-06-26 新增：便捷进入画中画方法
    // ====================================================================
    /**
     * 便捷进入画中画（使用默认 16:9 参数）
     *
     * 【为什么抽离到这里？】
     * 封装进入画中画的完整流程，MainActivity 一行调用即可。
     *
     * @param activity      当前 Activity
     * @param playerManager 播放器管理器（用于更新播放状态）
     * @return true=进入成功，false=进入失败
     */
    public boolean enterPip(Activity activity, TVPlayerManager playerManager) {
        log("========== 便捷进入画中画 ==========");
        if (!shouldEnterPip()) {
            log("❌ 条件不满足，不进入画中画");
            return false;
        }
        try {
            // 更新播放状态
            if (playerManager != null) {
                updatePlayState(true);
            }
            // 构建默认参数并进入
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
    /**
     * 画中画模式下隐藏所有 UI
     *
     * 【为什么要抽离到这里？】
     * 原来在 MainActivity 里，现在统一放到画中画管理器里，
     * 逻辑更集中，MainActivity 更清爽。
     *
     * @param channelPanelController 频道面板控制器
     * @param infoDisplayManager     信息展示管理器
     */
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
    /**
     * 画中画模式下保持播放（三重保险）
     *
     * 【为什么要抽离到这里？】
     * 原来在 MainActivity 里，现在统一放到画中画管理器里。
     *
     * 【三重保险机制】
     * 1. 直接调用 resume() 恢复播放
     * 2. 重新绑定 PlayerView 后再次 resume()
     * 3. 兜底：重新加载当前频道
     *
     * @param playerManager      播放器管理器
     * @param playerView         播放器视图
     * @param channelSourceList  频道列表
     * @param currentPlayIndex   当前播放索引
     */
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
    /**
     * 恢复播放（简单版，仅调用 resume）
     *
     * 【为什么抽离到这里？】
     * 原来在 MainActivity.resumeCurrentChannel() 里，
     * 现在统一放到画中画管理器中。
     *
     * @param playerManager 播放器管理器
     */
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
    /**
     * 打印 View 的详细尺寸信息（用于画中画调试）
     *
     * 【为什么要抽离到这里？】
     * 原来在 MainActivity 里，现在统一放到画中画管理器里，
     * 作为调试工具方法。
     *
     * @param tag  日志标签
     * @param view 要打印的 View
     */
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
    /**
     * 打印窗口和屏幕尺寸信息（用于画中画调试）
     *
     * 【为什么要抽离到这里？】
     * 原来在 MainActivity 里，现在统一放到画中画管理器里，
     * 作为调试工具方法。
     *
     * @param activity 当前 Activity
     */
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
