package com.tv.live.manager;

import android.os.Handler;
import android.os.Looper;

import com.tv.live.MainActivity;
import com.tv.live.PlayerGestureHelper;

/**
 * 手势管理器
 *
 * 【职责】
 * 处理播放器上的手势操作，包括：
 * 1. 单击：切换频道面板（回看模式下唤出控制栏）
 * 2. 双击：唤起控制栏（新增）
 * 3. 长按：打开设置页面
 * 4. 上滑：上一个频道（带反转）
 * 5. 下滑：下一个频道（带反转）
 */
public class GestureManager {

    private final MainActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_DELAY_MS = 300; // 300ms防抖
    private boolean isGestureLocked = false;

    public GestureManager(MainActivity activity) {
        this.activity = activity;
    }

    public PlayerGestureHelper create() {
        return new PlayerGestureHelper(activity, new PlayerGestureHelper.GestureCallback() {
            @Override
            public void onOk() {
                // 单击：切换面板（回看模式下已在 togglePanel 中特殊处理为唤起控制栏）
                activity.togglePanel();
            }

            @Override
            public void onLongOk() {
                // 长按：打开设置
                activity.openSettings();
            }

            @Override
            public void onMenu() {
                // ✅ 双击：唤起控制栏（原来这里调用 openSettings，现在改为显示控制栏）
                activity.showExoController();
            }

            // ====================================================================
            // ✅ 上滑/下滑手势（保持原逻辑，带反转判断）
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
