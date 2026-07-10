package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

/**
 * 手势管理器
 *
 * 【职责】
 * 处理播放器上的手势操作：
 * - 回看模式下：禁用单击和长按，双击唤起控制栏，滑动切台保持可用
 * - 非回看模式：所有手势正常
 */
public class GestureManager {

    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_DELAY_MS = 300;
    private boolean isGestureLocked = false;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                // 🔥 回看模式下禁用单击
                if (!activity.isInCatchUpMode()) {
                    activity.togglePanel();
                }
            }

            @Override
            public void onLongOk() {
                // 🔥 回看模式下禁用长按
                if (!activity.isInCatchUpMode()) {
                    activity.openSettings();
                }
            }

            @Override
            public void onMenu() {
                // ✅ 双击：仅在回看模式下唤起控制栏
                if (activity.isInCatchUpMode()) {
                    activity.showExoController();
                }
                // 非回看模式下双击无反应（保持原逻辑）
            }

            // ====================================================================
            // 滑动切台：回看模式下保持可用（不做任何限制）
            // ====================================================================
            @Override
            public void onPrevChannel() {
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
}
