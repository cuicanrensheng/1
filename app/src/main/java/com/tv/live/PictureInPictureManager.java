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

import java.util.List;

/**
 * 画中画(PIP)核心管理器
 *
 * 【2026-06-26 增强：合并 onPictureInPictureModeChanged 逻辑】
 * 【2026-06-27 增强：后台小窗返回前台检测，恢复手势/切台功能】
 * 【修改说明】
 * 1. 新增后台返回前台检测逻辑（isReturnFromBackgroundPip）
 * 2. 新增交互恢复方法（restoreGestureAndChannelSwitch）
 * 3. 关联退出画中画流程，确保手势/切台功能正常
 */
public class PictureInPictureManager {

    private static PictureInPictureManager instance;

    private final Context appContext;
    private boolean pipEnabled = false;
    private boolean isInPipMode = false;
    private boolean isPipEntering = false;
    private boolean onStopCalled = false;
    // 新增：标记是否从后台小窗返回前台
    private boolean isReturnFromBackgroundPip = false;
    // 新增：主线程 Handler，用于延迟检测
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OnPipListener listener;
    // 新增：交互恢复监听器（用于恢复手势/切台）
    private OnPipInteractionRestoreListener interactionRestoreListener;

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

    // 新增：交互恢复监听器（供Activity实现，恢复手势/切台）
    public interface OnPipInteractionRestoreListener {
        void onRestoreGesture();       // 恢复手势操作
        void onRestoreChannelSwitch(); // 恢复切台功能
        void onRestoreLandscapeUi();   // 恢复横屏UI布局
    }

    // ====================================================================
    // 基础状态方法（新增后台返回标记）
    // ====================================================================
    public boolean isPipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public void setPipEnabled(boolean enabled) {
        this.pipEnabled = enabled;
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
    }

    public void setStopCalled(boolean stopCalled) {
        this.onStopCalled = stopCalled;
    }

    public boolean isStopCalled() {
        return onStopCalled;
    }

    public void setListener(OnPipListener listener) {
        this.listener = listener;
    }

    // 新增：设置交互恢复监听器
    public void setInteractionRestoreListener(OnPipInteractionRestoreListener listener) {
        this.interactionRestoreListener = listener;
    }

    // 新增：标记是否从后台小窗返回前台
    public void setReturnFromBackgroundPip(boolean isReturn) {
        this.isReturnFromBackgroundPip = isReturn;
    }

    // ====================================================================
    // 是否应该进入画中画
    // ====================================================================
    public boolean shouldEnterPip(boolean isExternalPlayer) {
        if (!isPipSupported()) {
            return false;
        }
        if (!pipEnabled) {
            return false;
        }
        if (isInPipMode) {
            return false;
        }
        if (isPipEntering) {
            return false;
        }
        if (isExternalPlayer) {
            return false;
        }
        return true;
    }

    public boolean shouldEnterPip() {
        return shouldEnterPip(false);
    }

    // ====================================================================
    // 构建默认画中画参数（16:9）
    // ====================================================================
    public PictureInPictureParams buildDefaultPipParams() {
        if (!isPipSupported()) {
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                builder.setAspectRatio(new Rational(16, 9));
                return builder.build();
            }
        } catch (Exception e) {
            // 忽略异常
        }
        return null;
    }

    // ====================================================================
    // 便捷进入画中画方法
    // ====================================================================
    public boolean enterPip(Activity activity, TVPlayerManager playerManager, boolean mainSwitch) {
        if (activity == null) {
            return false;
        }
        boolean shouldEnter = shouldEnterPip();
        if (shouldEnter) {
            return enterPipInternal(activity, playerManager);
        } else {
            return false;
        }
    }

    public boolean enterPip(Activity activity, TVPlayerManager playerManager) {
        return enterPip(activity, playerManager, pipEnabled);
    }

    private boolean enterPipInternal(Activity activity, TVPlayerManager playerManager) {
        if (!shouldEnterPip()) {
            return false;
        }
        try {
            if (playerManager != null) {
                updatePlayState(true);
            }
            PictureInPictureParams params = buildDefaultPipParams();
            return enterPictureInPicture(activity, params);
        } catch (Exception e) {
            return false;
        }
    }

    // ====================================================================
    // 进入画中画（底层方法）
    // ====================================================================
    public boolean enterPictureInPicture(Activity activity, PictureInPictureParams params) {
        if (!isPipSupported()) {
            return false;
        }
        if (!pipEnabled) {
            return false;
        }
        if (activity == null) {
            return false;
        }
        if (activity.isFinishing()) {
            return false;
        }
        try {
            isPipEntering = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.enterPictureInPictureMode(params);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            isPipEntering = false;
        }
        return false;
    }

    // ====================================================================
    // onPause 处理
    // ====================================================================
    public void handleOnPause(Runnable resumeAction, Runnable pauseAction) {
        if (!isPipSupported()) {
            if (pauseAction != null) {
                pauseAction.run();
            }
            return;
        }
        if (isInPipMode || isPipEntering) {
            if (resumeAction != null) {
                try {
                    resumeAction.run();
                } catch (Exception e) {
                    // 忽略异常
                }
            }
        } else {
            if (pauseAction != null) {
                try {
                    pauseAction.run();
                } catch (Exception e) {
                    // 忽略异常
                }
            }
        }
    }

    public void handleOnPause(Runnable resumeAction) {
        handleOnPause(resumeAction, null);
    }

    // ====================================================================
    // 画中画模式变化回调（新增后台返回检测）
    // ====================================================================
    public void onPipModeChanged(Activity activity, boolean isInPip) {
        this.isInPipMode = isInPip;
        this.isPipEntering = false;
        
        // 新增：退出画中画时，标记为「从后台小窗返回」
        if (!isInPip) {
            setReturnFromBackgroundPip(true);
        }
        
        if (listener != null) {
            try {
                listener.onPipModeChanged(isInPip);
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }
    
    // ====================================================================
    // 退出画中画处理（兼容旧调用重载）
    // ====================================================================
    public void handleExitPip(Runnable releaseAction) {
        handleExitPip(null, releaseAction);
    }

    public void handleExitPip(Activity activity, Runnable releaseAction) {
        if (!isPipSupported()) {
            return;
        }
        if (onStopCalled) {
            if (releaseAction != null) {
                try {
                    releaseAction.run();
                } catch (Exception e) {
                    // 忽略异常
                }
            }
        } else {
            // 判空，防止activity为null时调用恢复方法崩溃
            if (isReturnFromBackgroundPip && activity != null) {
                restoreGestureAndChannelSwitch(activity);
            }
        }
        onStopCalled = false;
        isReturnFromBackgroundPip = false; // 重置标记
    }

    // ====================================================================
    // 2026-06-26 新增：处理进入画中画的 UI 变化
    // ====================================================================
    public void handleEnterPip(Activity activity,
                               ChannelPanelController channelPanelController,
                               InfoDisplayManager infoDisplayManager,
                               TVPlayerManager playerManager,
                               PlayerView playerView) {
        try {
            // 1. 隐藏所有 UI
            hideAllUi(channelPanelController, infoDisplayManager);

            // 2. 保持屏幕常亮
            if (activity != null) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            // 3. 恢复播放
            resumePlayback(playerManager);

        } catch (Exception e) {
            // 忽略异常
        }
    }

    // ====================================================================
    // 2026-06-26 新增：处理退出画中画的 UI 恢复（增强交互恢复）
    // ====================================================================
    public void handleExitPipRestore(Activity activity,
                                     DisplayManager displayManager,
                                     PlayerView playerView,
                                     TVPlayerManager playerManager,
                                     List<Channel> channelSourceList,
                                     int currentPlayIndex,
                                     InfoDisplayManager infoDisplayManager) {
        try {
            // ===== 第 1 步：重新应用全面屏 =====
            if (displayManager != null) {
                displayManager.reapplyFullScreen();
            }

            // ===== 第 2 步：立即刷新 PlayerView =====
            if (playerView != null) {
                playerView.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            playerView.requestLayout();
                            playerView.invalidate();
                        } catch (Exception e) {
                            // 忽略异常
                        }
                    }
                });

                // ===== 第 3 步：延迟刷新 + 重新绑定播放器 =====
                playerView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            playerView.requestLayout();
                            playerView.invalidate();
                            keepPlaying(playerManager, playerView, channelSourceList, currentPlayIndex);
                            
                            // 新增：延迟恢复交互（确保UI完全加载）
                            mainHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    restoreGestureAndChannelSwitch(activity);
                                }
                            }, 300);
                            
                        } catch (Exception e) {
                            // 忽略异常
                        }
                    }
                }, 200);
            }

            // ===== 第 4 步：显示信息栏 =====
            if (infoDisplayManager != null
                    && channelSourceList != null
                    && currentPlayIndex >= 0
                    && currentPlayIndex < channelSourceList.size()) {
                Channel currChannel = channelSourceList.get(currentPlayIndex);
                TVPlayerManager.LiveInfo liveInfo = null;
                if (playerManager != null) {
                    liveInfo = playerManager.getLiveInfo();
                }
                infoDisplayManager.showInfoBar(currChannel, liveInfo);
                infoDisplayManager.showChannelNum(currentPlayIndex + 1);
            }

            // ===== 第 5 步：保持屏幕常亮 =====
            if (activity != null) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            // ===== 第 6 步：恢复播放 =====
            resumePlayback(playerManager);

        } catch (Exception e) {
            // 忽略异常
        }
    }

    // ====================================================================
    // 2026-06-27 新增：恢复手势和切台功能（核心逻辑）
    // ====================================================================
    private void restoreGestureAndChannelSwitch(Activity activity) {
        try {
            // 1. 确保Activity处于前台且横屏
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            
            // 2. 恢复横屏UI布局
            if (interactionRestoreListener != null) {
                interactionRestoreListener.onRestoreLandscapeUi();
            }
            
            // 3. 恢复手势操作（如滑动切换、点击控制）
            if (interactionRestoreListener != null) {
                interactionRestoreListener.onRestoreGesture();
            }
            
            // 4. 恢复频道切换功能
            if (interactionRestoreListener != null) {
                interactionRestoreListener.onRestoreChannelSwitch();
            }
            
            // 5. 确保窗口焦点和触摸事件可用
            if (activity.getWindow() != null) {
                activity.getWindow().getDecorView().setFocusable(true);
                activity.getWindow().getDecorView().setFocusableInTouchMode(true);
                activity.getWindow().getDecorView().requestFocus();
            }
            
        } catch (Exception e) {
            // 忽略异常
        }
    }

    // ====================================================================
    // 播放状态和频道信息更新
    // ====================================================================
    public void updatePlayState(boolean isPlaying) {
        // 已清除日志
    }

    public void updateChannelInfo(int num, String name, String bitrate) {
        // 已清除日志
    }

    // ====================================================================
    // 隐藏画中画模式下的所有 UI
    // ====================================================================
    public void hideAllUi(ChannelPanelController channelPanelController,
                          InfoDisplayManager infoDisplayManager) {
        try {
            if (channelPanelController != null && channelPanelController.isPanelOpen()) {
                channelPanelController.hidePanel();
            }
            if (infoDisplayManager != null) {
                infoDisplayManager.hideInfoBar();
                infoDisplayManager.hideChannelNum();
            }
        } catch (Exception e) {
            // 忽略异常
        }
    }

    // ====================================================================
    // 画中画模式下保持播放（三重保险）
    // ====================================================================
    public void keepPlaying(TVPlayerManager playerManager,
                            PlayerView playerView,
                            List<Channel> channelSourceList,
                            int currentPlayIndex) {
        try {
            if (playerManager != null) {
                playerManager.resume();
                if (playerView != null) {
                    playerManager.attachPlayerView(playerView);
                    playerManager.resume();
                }
            }
        } catch (Exception e) {
            try {
                if (channelSourceList != null
                        && currentPlayIndex >= 0
                        && currentPlayIndex < channelSourceList.size()) {
                    Channel channel = channelSourceList.get(currentPlayIndex);
                    if (channel != null && channel.getPlayUrl() != null) {
                        playerManager.playUrl(channel.getPlayUrl());
                    }
                }
            } catch (Exception e2) {
                // 忽略异常
            }
        }
    }

    // ====================================================================
    // 恢复播放（简单版）
    // ====================================================================
    public void resumePlayback(TVPlayerManager playerManager) {
        try {
            if (playerManager != null) {
                playerManager.resume();
            }
        } catch (Exception e) {
            // 忽略异常
        }
    }

    // ====================================================================
    // 释放资源（新增清空交互监听器）
    // ====================================================================
    public void release() {
        listener = null;
        interactionRestoreListener = null; // 清空交互恢复监听器
        isInPipMode = false;
        isPipEntering = false;
        onStopCalled = false;
        isReturnFromBackgroundPip = false; // 重置后台返回标记
    }
}
