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
import android.view.WindowManager;
import androidx.media3.ui.PlayerView;
import com.tv.live.manager.ChannelPanelController;
import com.tv.live.manager.DisplayManager;
import com.tv.live.manager.InfoManager;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 画中画(PIP)核心管理器 修复内存泄漏版
 */
public class PictureInPictureManager {
    private static final String LOG_PREFIX = "【画中画】";
    private static final String DEBUG_PREFIX = "【画中画排查】";
    private static PictureInPictureManager instance;

    // 修复：使用WeakReference弱引用ApplicationContext，取消强持有
    private WeakReference<Context> appContextRef;

    private boolean pipEnabled = false;
    private boolean isInPipMode = false;
    private boolean isPipEntering = false;
    private boolean onStopCalled = false;
    private boolean debugLogEnabled = true;
    private OnPipListener listener;

    // ====================== 静态弱引用Runnable 消除匿名内部类泄漏 ======================
    // 立即刷新布局Runnable
    private static class RefreshViewRunnable implements Runnable {
        private final WeakReference<PictureInPictureManager> mgrRef;
        private final WeakReference<PlayerView> viewRef;

        public RefreshViewRunnable(PictureInPictureManager mgr, PlayerView view) {
            this.mgrRef = new WeakReference<>(mgr);
            this.viewRef = new WeakReference<>(view);
        }

        @Override
        public void run() {
            PictureInPictureManager mgr = mgrRef.get();
            PlayerView pv = viewRef.get();
            if (mgr == null || pv == null) return;
            try {
                pv.requestLayout();
                pv.invalidate();
                mgr.log("✅ 立即刷新 PlayerView 布局");
                mgr.logViewSize("PlayerView", pv);
            } catch (Exception e) {
                mgr.log("❌ 立即刷新 PlayerView 失败：" + e.getMessage());
            }
        }
    }

    // 延迟200ms刷新+恢复播放Runnable
    private static class DelayedRestoreRunnable implements Runnable {
        private final WeakReference<PictureInPictureManager> mgrRef;
        private final WeakReference<PlayerView> viewRef;
        private final WeakReference<TVPlayerManager> playerRef;
        private final List<Channel> channelList;
        private final int playIndex;

        public DelayedRestoreRunnable(PictureInPictureManager mgr, PlayerView view, TVPlayerManager player, List<Channel> channels, int index) {
            this.mgrRef = new WeakReference<>(mgr);
            this.viewRef = new WeakReference<>(view);
            this.playerRef = new WeakReference<>(player);
            this.channelList = channels;
            this.playIndex = index;
        }
        @Override
public void run() {
    PictureInPictureManager mgr = mgrRef.get();
    PlayerView pv = viewRef.get();
    TVPlayerManager player = playerRef.get();
    if (mgr == null || pv == null) return;
    try {
        pv.requestLayout();
        pv.invalidate();
        mgr.keepPlaying(player, pv, channelList, playIndex);
        mgr.log("✅ 延迟刷新 PlayerView + 重新绑定");
        mgr.logViewSize("PlayerView", pv);
        // 修复 instanceof 模式匹配语法（适配 Java 8）
        if (pv.getParent() instanceof View) {
            View parent = (View) pv.getParent();
            mgr.logViewSize("父布局", parent);
        }
        mgr.log("【尺寸】================================");
    } catch (Exception e) {
        mgr.log("❌ 延迟刷新失败：" + e.getMessage());
    }
}
    }

    // 退出Pip释放回调静态弱引用包装
    private static class ExitReleaseRunnable implements Runnable {
        private final WeakReference<PictureInPictureManager> mgrRef;
        private final WeakReference<Runnable> realRef;

        public ExitReleaseRunnable(PictureInPictureManager mgr, Runnable real) {
            this.mgrRef = new WeakReference<>(mgr);
            this.realRef = new WeakReference<>(real);
        }

        @Override
        public void run() {
            Runnable target = realRef.get();
            if (target != null) target.run();
        }
    }

    // Pip暂停回调静态弱引用包装
    private static class PipPauseRunnable implements Runnable {
        private final WeakReference<PictureInPictureManager> mgrRef;
        private final WeakReference<Runnable> actionRef;

        public PipPauseRunnable(PictureInPictureManager mgr, Runnable action) {
            this.mgrRef = new WeakReference<>(mgr);
            this.actionRef = new WeakReference<>(action);
        }

        @Override
        public void run() {
            Runnable act = actionRef.get();
            if (act != null) act.run();
        }
    }

    public interface OnPipListener {
        void onPipModeChanged(boolean inPip);
    }

    // 获取上下文统一封装判空
    private Context getAppContext() {
        return appContextRef != null ? appContextRef.get() : null;
    }

    public static PictureInPictureManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PictureInPictureManager.class) {
                if (instance == null) {
                    Context appCtx = context.getApplicationContext();
                    instance = new PictureInPictureManager(appCtx);
                }
            }
        }
        // 上下文失效则刷新弱引用
        if (instance.appContextRef.get() == null && context != null) {
            instance.appContextRef = new WeakReference<>(context.getApplicationContext());
        }
        return instance;
    }

    // 修复构造：不再强持有Context，使用WeakReference
    private PictureInPictureManager(Context context) {
        this.appContextRef = new WeakReference<>(context);
    }

    // ====================================================================
    // 调试日志开关控制
    // ====================================================================
    public void setDebugLogEnabled(boolean enabled) {
        this.debugLogEnabled = enabled;
        logDebug("调试日志：" + (enabled ? "✅ 已开启" : "❌ 已关闭"));
    }

    private void logDebug(String msg) {
        if (!debugLogEnabled) return;
        try {
            SettingsActivity.logOperation(DEBUG_PREFIX + msg);
        } catch (Exception ignored) {}
    }

    // ====================================================================
    // 基础状态方法
    // ====================================================================
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

    // ====================================================================
    // 是否应该进入画中画
    // ====================================================================
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
    // 构建默认画中画参数（16:9）
    // ====================================================================
    public PictureInPictureParams buildDefaultPipParams() {
        if (!isPipSupported()) return null;
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
            builder.setAspectRatio(new Rational(16, 9));
            log("✅ 构建画中画参数成功（比例 16:9）");
            return builder.build();
        } catch (Exception e) {
            log("❌ 构建画中画参数失败：" + e.getMessage());
        }
        return null;
    }

    // ====================================================================
    // 便捷进入画中画方法
    // ====================================================================
    public boolean enterPip(Activity activity, TVPlayerManager playerManager, boolean mainSwitch) {
        logDebug("========== 开始 ==========");
        logDebug("onUserLeaveHint 被调用");
        if (activity == null) {
            logDebug("❌ Activity 为 null");
            logDebug("========== 结束 ==========");
            return false;
        }
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

    public boolean enterPip(Activity activity, TVPlayerManager playerManager) {
        return enterPip(activity, playerManager, pipEnabled);
    }

    private boolean enterPipInternal(Activity activity, TVPlayerManager playerManager) {
        log("========== 便捷进入画中画 ==========");
        if (!shouldEnterPip()) {
            log("❌ 条件不满足，不进入画中画");
            return false;
        }
        try {
            if (playerManager != null) updatePlayState(true);
            PictureInPictureParams params = buildDefaultPipParams();
            boolean result = enterPictureInPicture(activity, params);
            log("进入结果：" + (result ? "✅ 成功" : "❌ 失败"));
            return result;
        } catch (Exception e) {
            log("❌ 便捷进入画中画异常：" + e.getMessage());
        }
        return false;
    }

    // ====================================================================
    // 底层进入画中画API调用
    // ====================================================================
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
            activity.enterPictureInPictureMode(params);
            log("✅ 调用系统API进入画中画成功");
            return true;
        } catch (Exception e) {
            log("❌ 进入画中画异常：" + e.getMessage());
            isPipEntering = false;
        }
        return false;
    }

    // ====================================================================
    // onPause 处理
    // ====================================================================
    public void handleOnPause(Runnable resumeAction, Runnable pauseAction) {
        log("========== onPause 处理 ==========");
        log("当前状态：isInPipMode=" + isInPipMode + "，isPipEntering=" + isPipEntering);
        if (!isPipSupported()) {
            log("设备不支持画中画，直接暂停");
            if (pauseAction != null) new PipPauseRunnable(this, pauseAction).run();
            return;
        }
        if (isInPipMode || isPipEntering) {
            log("✅ 画中画模式，继续播放");
            if (resumeAction != null) new PipPauseRunnable(this, resumeAction).run();
        } else {
            log("普通模式，暂停播放");
            if (pauseAction != null) new PipPauseRunnable(this, pauseAction).run();
        }
        log("================================");
    }

    public void handleOnPause(Runnable resumeAction) {
        handleOnPause(resumeAction, null);
    }

    // ====================================================================
    // 画中画模式变化回调
    // ====================================================================
    public void onPipModeChanged(Activity activity, boolean isInPip) {
        log("========== 模式变化回调 ==========");
        log("新模式：" + (isInPip ? "✅ 进入画中画" : "❌ 退出画中画"));
        this.isInPipMode = isInPip;
        this.isPipEntering = false;
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

    // ====================================================================
    // 退出画中画释放逻辑
    // ====================================================================
    public void handleExitPip(Runnable releaseAction) {
        log("========== 退出画中画处理 ==========");
        log("onStopCalled = " + onStopCalled);
        if (!isPipSupported()) {
            log("设备不支持画中画，跳过");
            return;
        }
        if (onStopCalled) {
            log("用户关闭了应用，释放播放器");
            if (releaseAction != null) new ExitReleaseRunnable(this, releaseAction).run();
        } else {
            log("用户返回应用，继续播放（不释放）");
        }
        onStopCalled = false;
        log("重置 onStopCalled = false");
        log("================================");
    }

    // ====================================================================
    // 进入画中画UI处理
    // ====================================================================
    public void handleEnterPip(Activity activity,
                               ChannelPanelController channelPanelController,
                               InfoDisplayManager infoDisplayManager,
                               TVPlayerManager playerManager,
                               PlayerView playerView) {
        log("========== 进入画中画 - UI 处理 ==========");
        try {
            hideAllUi(channelPanelController, infoDisplayManager);
            if (activity != null) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                log("✅ 已保持屏幕常亮");
            }
            resumePlayback(playerManager);
            logViewSize("进入画中画时", playerView);
        } catch (Exception e) {
            log("❌ 进入画中画 UI 处理失败：" + e.getMessage());
        }
        log("================================");
    }

    // ====================================================================
    // 退出画中画UI恢复（全部匿名Runnable替换静态弱引用）
    // ====================================================================
    public void handleExitPipRestore(Activity activity,
                                     DisplayManager displayManager,
                                     PlayerView playerView,
                                     TVPlayerManager playerManager,
                                     List<Channel> channelSourceList,
                                     int currentPlayIndex,
                                     InfoDisplayManager infoDisplayManager) {
        log("========== 退出画中画 - UI 恢复 ==========");
        try {
            log("【尺寸】===== 1. 刚退出画中画（初始状态） =====");
            logViewSize("PlayerView", playerView);
            if (playerView != null && playerView.getParent() instanceof View) {
                logViewSize("父布局", (View) playerView.getParent());
            }
            logWindowSize(activity);

            if (displayManager != null) {
                log("【尺寸】执行 displayManager.reapplyFullScreen()");
                displayManager.reapplyFullScreen();
                log("【尺寸】===== 2. reapplyFullScreen 后 =====");
                logViewSize("PlayerView", playerView);
            }

            if (playerView != null) {
                // 替换原匿名Runnable为静态弱引用类
                playerView.post(new RefreshViewRunnable(this, playerView));
                // 延迟刷新同样替换
                playerView.postDelayed(
                        new DelayedRestoreRunnable(this, playerView, playerManager, channelSourceList, currentPlayIndex),
                        200
                );
            }

            if (infoDisplayManager != null && channelSourceList != null
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel currChannel = channelSourceList.get(currentPlayIndex);
                TVPlayerManager.LiveInfo liveInfo = playerManager != null ? playerManager.getLiveInfo() : null;
                infoDisplayManager.showInfoBar(currChannel, liveInfo);
                infoDisplayManager.showChannelNum(currentPlayIndex + 1);
                log("✅ 已显示信息栏");
            }

            if (activity != null) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                log("✅ 已保持屏幕常亮");
            }
            resumePlayback(playerManager);
            log("✅ 退出画中画 UI 恢复完成");
        } catch (Exception e) {
            log("❌ 退出画中画 UI 恢复失败：" + e.getMessage());
        }
        log("================================");
    }

    // ====================================================================
    // 播放状态、频道信息更新
    // ====================================================================
    public void updatePlayState(boolean isPlaying) {
        log("更新播放状态：" + (isPlaying ? "▶ 播放中" : "⏸ 已暂停"));
    }

    public void updateChannelInfo(int num, String name, String bitrate) {
        log("更新频道信息：" + num + " - " + name + " - " + bitrate);
    }

    // ====================================================================
    // 隐藏画中画全部UI
    // ====================================================================
    public void hideAllUi(ChannelPanelController channelPanelController, InfoDisplayManager infoDisplayManager) {
        log("========== 隐藏所有 UI（画中画模式） ==========");
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
            log("❌ 隐藏 UI 失败：" + e.getMessage());
        }
        log("================================");
    }

    // ====================================================================
    // 画中画兜底恢复播放
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
    // 简易恢复播放
    // ====================================================================
    public void resumePlayback(TVPlayerManager playerManager) {
        try {
            if (playerManager != null) {
                player.resume();
                log("✅ 恢复播放成功");
            }
        } catch (Exception e) {
            log("❌ 恢复播放失败：" + e.getMessage());
        }
    }

    // ====================================================================
    // 打印View尺寸日志
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
            log("尺寸】" + tag + "尺寸：宽=" + view.getWidth() + "，高=" + view.getHeight());
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            String widthStr = lp.width == ViewGroup.LayoutParams.MATCH_PARENT ? "MATCH_PARENT" :
                    lp.width == ViewGroup.LayoutParams.WRAP_CONTENT ? "WRAP_CONTENT" : String.valueOf(lp.width);
            String heightStr = lp.height == ViewGroup.LayoutParams.MATCH_PARENT ? "MATCH_PARENT" :
                    lp.height == ViewGroup.LayoutParams.WRAP_CONTENT ? "WRAP_CONTENT" : String.valueOf(lp.height);
            log("尺寸】" + tag + "布局参数：width=" + widthStr + "，height=" + heightStr);
            int vis = view.getVisibility();
            String visStr = vis == View.VISIBLE ? "VISIBLE" : vis == View.INVISIBLE ? "INVISIBLE" : "GONE";
            log("尺寸】" + tag + "可见性：" + visStr);
        } catch (Exception e) {
            log("尺寸】" + tag + "获取尺寸失败：" + e.getMessage());
        }
    }

    // ====================================================================
    // 打印窗口尺寸日志
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
            View decor = activity.getWindow().getDecorView();
            log("尺寸】DecorView：宽=" + decor.getWidth() + "，高=" + decor.getHeight());
        } catch (Exception e) {
            log("尺寸】获取窗口尺寸失败：" + e.getMessage());
        }
    }

    // ====================================================================
    // 完整release 资源释放（满足全部清理要求）
    // ====================================================================
    public void release() {
        log("========== 释放资源 ==========");
        // 1. 清空监听器
        listener = null;
        // 2. 重置全部状态标记
        pipEnabled = false;
        isInPipMode = false;
        isPipEntering = false;
        onStopCalled = false;
        debugLogEnabled = false;
        // 3. 清空上下文弱引用
        if (appContextRef != null) {
            appContextRef.clear();
            appContextRef = null;
        }
        // 4. 单例置空，彻底释放
        instance = null;
        log("✅ 资源释放完成");
    }

    // ====================================================================
    // 日志工具
    // ====================================================================
    private void log(String msg) {
        try {
            SettingsActivity.logOperation(LOG_PREFIX + msg);
        } catch (Exception ignored) {}
    }
}
