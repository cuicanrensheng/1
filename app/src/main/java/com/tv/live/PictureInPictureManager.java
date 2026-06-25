package com.tv.live;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.media3.ui.PlayerView;

import com.tv.live.manager.ChannelPanelController;
import com.tv.live.manager.DisplayManager;
import com.tv.live.manager.InfoDisplayManager;
import com.tv.live.manager.TvRemoteManager;

import java.util.List;

/**
 * 画中画(PIP)核心管理器
 * 作用：统一管理画中画的初始化、开关、状态回调、兼容性判断、生命周期处理
 * 设计：单例模式，全局唯一实例，避免重复创建
 *
 * 【2026-06-25 增强：抽离 MainActivity 中的画中画逻辑】
 * 【修改说明】
 * 把 MainActivity 里的画中画相关逻辑全部抽离到这里，
 * 包括：隐藏UI、保持播放、尺寸日志、用户离开处理、模式变化处理。
 * 这样 MainActivity 更清爽，画中画逻辑更集中。
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

    // ====================================================================
    // ✅ 2026-06-25 新增：处理用户按 Home 键（onUserLeaveHint）
    // ====================================================================
    /**
     * 处理用户按 Home 键时的画中画逻辑
     *
     * 【为什么要抽离到这里？】
     * 原来在 MainActivity.onUserLeaveHint() 里，现在统一封装到画中画管理器。
     * Activity 里只需要调用这一个方法就行。
     *
     * @param activity           当前 Activity
     * @param isOpeningSettings  是否正在打开设置页面（打开设置不进入画中画）
     * @param mainPipEnable      MainActivity 里的开关状态（用于日志对比）
     * @param playerManager      播放器管理器（用于更新播放状态）
     * @return 是否成功进入画中画
     */
    public boolean handleUserLeaveHint(Activity activity,
                                       boolean isOpeningSettings,
                                       boolean mainPipEnable,
                                       TVPlayerManager playerManager) {
        log("排查】========== 开始 ==========");
        log("排查】onUserLeaveHint 被调用");

        if (isOpeningSettings) {
            log("排查】打开设置页面，跳过");
            log("排查】========== 结束 ==========");
            return false;
        }

        if (instance == null) {
            log("排查】❌ pipManager 为 null");
            log("排查】========== 结束 ==========");
            return false;
        }

        boolean shouldEnter = shouldEnterPip();

        log("排查】MainActivity开关状态：" + mainPipEnable);
        log("排查】设备支持：" + isPipSupported());
        log("排查】PIP管理器开关：" + isPipEnabled());
        log("排查】已在画中画模式：" + isInPipMode());
        log("排查】正在进入画中画：" + isPipEntering());

        if (shouldEnter) {
            log("排查】所有条件满足，尝试进入画中画...");
            try {
                PictureInPictureParams pipParams = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder();
                    pipBuilder.setAspectRatio(new Rational(16, 9));
                    pipParams = pipBuilder.build();
                }
                if (playerManager != null) {
                    updatePlayState(true);
                }
                boolean result = enterPictureInPicture(activity, pipParams);
                log("排查】进入结果：" + (result ? "✅ 成功" : "❌ 失败"));
                log("排查】========== 结束 ==========");
                return result;
            } catch (Exception e) {
                log("排查】❌ 异常：" + e.getMessage());
                e.printStackTrace();
            }
        } else {
            log("排查】❌ 条件不满足，不进入画中画");
        }

        log("排查】========== 结束 ==========");
        return false;
    }

    // ====================================================================
    // ✅ 2026-06-25 新增：处理进入画中画后的UI逻辑
    // ====================================================================
    /**
     * 处理进入画中画模式后的UI逻辑
     *
     * 【为什么要抽离到这里？】
     * 原来在 MainActivity.onPictureInPictureModeChanged() 的 if(isInPip) 分支里，
     * 现在统一封装到画中画管理器。
     *
     * @param activity              当前 Activity
     * @param channelPanelController 频道面板控制器
     * @param infoDisplayManager    信息展示管理器
     * @param playerManager         播放器管理器
     * @param playerView            播放器视图
     */
    public void handleEnterPip(Activity activity,
                               ChannelPanelController channelPanelController,
                               InfoDisplayManager infoDisplayManager,
                               TVPlayerManager playerManager,
                               PlayerView playerView) {
        log("========== 进入画中画 ==========");

        hideAllUi(channelPanelController, infoDisplayManager);

        if (activity != null) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        if (playerManager != null) {
            try {
                playerManager.resume();
                log("✅ 恢复播放");
            } catch (Exception e) {
                log("恢复播放失败：" + e.getMessage());
            }
        }

        logViewSize("进入画中画时", playerView);

        log("================================");
    }

    // ====================================================================
    // ✅ 2026-06-25 新增：处理退出画中画后的UI逻辑
    // ====================================================================
    /**
     * 处理退出画中画模式后的UI逻辑
     *
     * 【为什么要抽离到这里？】
     * 原来在 MainActivity.onPictureInPictureModeChanged() 的 else 分支里，
     * 现在统一封装到画中画管理器。
     *
     * @param activity              当前 Activity
     * @param displayManager        显示管理器（用于重新应用全屏）
     * @param playerView            播放器视图
     * @param playerManager         播放器管理器
     * @param remoteManager         遥控器管理器（用于同步模式）
     * @param infoDisplayManager    信息展示管理器
     * @param channelSourceList     频道列表
     * @param currentPlayIndex      当前播放索引
     * @param onExitComplete        退出完成后的回调（可选）
     */
    public void handleExitPip(final Activity activity,
                              final DisplayManager displayManager,
                              final PlayerView playerView,
                              final TVPlayerManager playerManager,
                              final TvRemoteManager remoteManager,
                              final InfoDisplayManager infoDisplayManager,
                              final List<Channel> channelSourceList,
                              final int currentPlayIndex,
                              final Runnable onExitComplete) {
        log("========== 退出画中画 ==========");

        handleExitPip(new Runnable() {
            @Override
            public void run() {
                log("应用已关闭，释放播放器");
                if (onExitComplete != null) {
                    onExitComplete.run();
                }
            }
        });

        log("尺寸】===== 1. 刚退出画中画（初始状态） =====");
        logViewSize("PlayerView", playerView);
        if (playerView != null && playerView.getParent() instanceof View) {
            logViewSize("父布局", (View) playerView.getParent());
        }
        logWindowSize(activity);

        if (displayManager != null) {
            log("尺寸】执行 displayManager.reapplyFullScreen()");
            displayManager.reapplyFullScreen();
        }

        log("尺寸】===== 2. reapplyFullScreen 后 =====");
        logViewSize("PlayerView", playerView);

        if (playerView != null) {
            playerView.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        playerView.requestLayout();
                        playerView.invalidate();
                        log("✅ 立即刷新 PlayerView 布局");
                        log("尺寸】===== 3. 立即 requestLayout 后 =====");
                        logViewSize("PlayerView", playerView);
                    } catch (Exception e) {
                        log("刷新 PlayerView 失败：" + e.getMessage());
                    }
                }
            });

            playerView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        playerView.requestLayout();
                        playerView.invalidate();
                        if (playerManager != null) {
                            playerManager.attachPlayerView(playerView);
                            playerManager.resume();
                        }
                        log("✅ 延迟刷新 PlayerView + 重新绑定");
                        log("尺寸】===== 4. 延迟200ms刷新 + 重新绑定后 =====");
                        logViewSize("PlayerView", playerView);
                        if (playerView.getParent() instanceof View) {
                            logViewSize("父布局", (View) playerView.getParent());
                        }
                        log("尺寸】========================================");
                    } catch (Exception e) {
                        log("延迟刷新失败：" + e.getMessage());
                    }
                }
            }, 200);
        }

        if (remoteManager != null) {
            if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                remoteManager.setMode(TvRemoteManager.Mode.CHANNEL_PANEL_MODE);
                remoteManager.setRightPanelOpen(channelPanelController.isRightPanelOpen());
            } else {
                remoteManager.setMode(TvRemoteManager.Mode.PLAY_MODE);
            }
        }

        if (infoDisplayManager != null && channelSourceList != null
                && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel currChannel = channelSourceList.get(currentPlayIndex);
            TVPlayerManager.LiveInfo liveInfo = null;
            if (playerManager != null) {
                liveInfo = playerManager.getLiveInfo();
            }
            infoDisplayManager.showInfoBar(currChannel, liveInfo);
            infoDisplayManager.showChannelNum(currentPlayIndex + 1);
        }

        if (activity != null) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        try {
            if (playerManager != null) {
                playerManager.resume();
            }
        } catch (Exception e) {
            log("恢复播放失败：" + e.getMessage());
        }

        log("退出画中画完成");
        log("================================");
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
