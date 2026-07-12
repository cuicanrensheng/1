package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

/**
 * 手势管理器
 */
public class GestureManager {

    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_DELAY_MS = 300;
    private boolean isGestureLocked = false;

    // 🛡️【新增】长按保护锁：防止长按触发设置时，手指轻微滑动误触切台
    private boolean isLongPressTriggered = false;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                activity.togglePanel();
            }

            @Override
            public void onLongOk() {
                // 🟢 回看模式下禁止长按触发设置
                if (activity.isInCatchUpMode()) {
                    return;
                }
                // ✅【核心修复】启动长按保护锁，阻断接下来 300ms 内的任何滑动切台
                isLongPressTriggered = true;
                mainHandler.removeCallbacksAndMessages(null);
                mainHandler.postDelayed(() -> isLongPressTriggered = false, DEBOUNCE_DELAY_MS);

                activity.openSettings();
            }

            @Override
            public void onMenu() {
                // ✅ 双击：仅在回看模式下唤起控制栏
                if (activity.isInCatchUpMode()) {
                    activity.showExoController();
                }
            }

            @Override
            public void onPrevChannel() {
                // 🛡️【修复】处于长按保护期内，直接忽略滑动切台
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
                // 🛡️【修复】处于长按保护期内，直接忽略滑动切台
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
}
