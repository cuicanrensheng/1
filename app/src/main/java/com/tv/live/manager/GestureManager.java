package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

/**
 * 手势管理器
 */
public class GestureManager {

    // 🟢【修复内存泄漏】activity 不能 final，release 时需要置 null 断开 Activity 引用
    private MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // ✅【修改】防抖时长延长至 500ms，与长按判定时长对齐，进一步降低误触概率
    private static final long DEBOUNCE_DELAY_MS = 500;
    private boolean isGestureLocked = false;

    private boolean isLongPressTriggered = false;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                // 🟢【修复内存泄漏】activity 可能被 release，避免 NPE
                if (activity == null) return;
                activity.togglePanel();
            }

            @Override
            public void onLongOk() {
                if (activity == null) return;
                if (activity.isInCatchUpMode()) {
                    return;
                }
                isLongPressTriggered = true;
                mainHandler.removeCallbacksAndMessages(null);
                mainHandler.postDelayed(() -> isLongPressTriggered = false, DEBOUNCE_DELAY_MS);

                activity.openSettings();
            }

            @Override
            public void onMenu() {
                if (activity == null) return;
                if (activity.isInCatchUpMode()) {
                    activity.showExoController();
                }
            }

            @Override
            public void onPrevChannel() {
                if (activity == null) return;
                if (isLongPressTriggered) return;

                if (!isGestureLocked) {
                    isGestureLocked = true;
                    boolean isReverse = activity.isChannelReverse();
                    if (isReverse) {
                        activity.playNext();
                    } else {
                        activity.playPrev();
                    }
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                }
            }

            @Override
            public void onNextChannel() {
                if (activity == null) return;
                if (isLongPressTriggered) return;

                if (!isGestureLocked) {
                    isGestureLocked = true;
                    boolean isReverse = activity.isChannelReverse();
                    if (isReverse) {
                        activity.playPrev();
                    } else {
                        activity.playNext();
                    }
                    mainHandler.postDelayed(() -> isGestureLocked = false, DEBOUNCE_DELAY_MS);
                }
            }
        });
    }

    /**
     * 🟢【修复内存泄漏】清理 Handler 回调与 Activity 引用，避免长生命周期持有 Activity
     */
    public void release() {
        try {
            mainHandler.removeCallbacksAndMessages(null);
        } catch (Exception ignored) {}
        activity = null;
        isGestureLocked = false;
        isLongPressTriggered = false;
    }
}
