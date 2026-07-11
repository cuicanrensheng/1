package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;
import android.content.Intent;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;
import com.tv.live.SettingsActivity;

/**
 * 手势管理器
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
                activity.togglePanel();
            }

            @Override
            public void onLongOk() {
                // 🟢【新增】回看模式下禁止长按触发设置
                if (activity.isInCatchUpMode()) {
                    return;
                }
                // 统一调用 MainActivity 的 openSettings()
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
