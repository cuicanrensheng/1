package com.tv.live;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 播放器状态管理类
 * 独立处理播放状态监听、状态分发、异常统计等逻辑
 */
public class PlayerStateManager {
    private static final String TAG = "TVPlayer_StateManager";
    private static final long STATE_THROTTLE_MS = 500; // 状态回调节流时间，避免频繁回调

    private Context mContext;
    private Handler mMainHandler;
    private TVPlayerManager mPlayerManager;

    // 状态缓存，用于节流
    private int mLastReportedState = -1;
    private long mLastStateReportTime = 0;

    // 状态监听列表
    private final List<OnPlayerStateChangeListener> mStateListeners = new ArrayList<>();
    // 错误监听列表
    private final List<OnPlayerErrorListener> mErrorListeners = new ArrayList<>();
    // 直播信息监听列表
    private final List<OnLiveInfoUpdateListener> mLiveInfoListeners = new ArrayList<>();
    // 播放源失效监听列表
    private final List<OnSourceFailedListener> mSourceFailedListeners = new ArrayList<>();

    // 单例
    private static volatile PlayerStateManager sInstance;

    private PlayerStateManager(Context context) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mPlayerManager = TVPlayerManager.getInstance(mContext);
        initPlayerStateListener();
    }

    /**
     * 获取单例实例
     */
    public static PlayerStateManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (PlayerStateManager.class) {
                if (sInstance == null) {
                    sInstance = new PlayerStateManager(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * 初始化播放器状态监听
     */
    private void initPlayerStateListener() {
        // 注册TVPlayerManager的状态监听
        mPlayerManager.setOnPlayStateListener(new TVPlayerManager.OnPlayStateListener() {
            @Override
            public void onIdle() {
                dispatchStateChange(TVPlayerManager.PLAY_STATE_IDLE);
            }

            @Override
            public void onBuffering() {
                dispatchStateChange(TVPlayerManager.PLAY_STATE_BUFFERING);
            }

            @Override
            public void onPlayReady() {
                dispatchStateChange(TVPlayerManager.PLAY_STATE_READY);
            }

            @Override
            public void onPlayEnd() {
                dispatchStateChange(TVPlayerManager.PLAY_STATE_ENDED);
            }

            @Override
            public void onPlayError(String msg) {
                dispatchPlayError(msg);
            }
        });

        // 注册播放源失效监听
        mPlayerManager.setOnSourceFailedListener(() -> {
            mMainHandler.post(() -> {
                for (OnSourceFailedListener listener : mSourceFailedListeners) {
                    try {
                        listener.onSourceFailed();
                    } catch (Exception e) {
                        Log.e(TAG, "分发播放源失效回调异常", e);
                    }
                }
            });
        });

        // 注册直播信息更新监听
        mPlayerManager.setOnLiveInfoUpdateListener(info -> {
            mMainHandler.post(() -> {
                for (OnLiveInfoUpdateListener listener : mLiveInfoListeners) {
                    try {
                        listener.onLiveInfoUpdate(info);
                    } catch (Exception e) {
                        Log.e(TAG, "分发直播信息回调异常", e);
                    }
                }
            });
        });
    }

    /**
     * 分发播放状态变化（带节流）
     */
    private void dispatchStateChange(int state) {
        long currentTime = System.currentTimeMillis();
        // 节流：相同状态短时间内只回调一次
        if (state == mLastReportedState && (currentTime - mLastStateReportTime) < STATE_THROTTLE_MS) {
            return;
        }

        mLastReportedState = state;
        mLastStateReportTime = currentTime;

        mMainHandler.post(() -> {
            String stateName = getStateName(state);
            Log.d(TAG, "分发播放状态: " + stateName + "(" + state + ")");

            for (OnPlayerStateChangeListener listener : mStateListeners) {
                try {
                    listener.onStateChanged(state, stateName);
                } catch (Exception e) {
                    Log.e(TAG, "分发状态回调异常", e);
                }
            }
        });
    }

    /**
     * 分发播放错误
     */
    private void dispatchPlayError(String errorMsg) {
        mMainHandler.post(() -> {
            Log.e(TAG, "分发播放错误: " + errorMsg);
            for (OnPlayerErrorListener listener : mErrorListeners) {
                try {
                    listener.onPlayError(errorMsg);
                } catch (Exception e) {
                    Log.e(TAG, "分发错误回调异常", e);
                }
            }
        });
    }

    /**
     * 获取状态名称
     */
    public String getStateName(int state) {
        switch (state) {
            case TVPlayerManager.PLAY_STATE_IDLE:
                return "空闲";
            case TVPlayerManager.PLAY_STATE_BUFFERING:
                return "缓冲中";
            case TVPlayerManager.PLAY_STATE_READY:
                return "播放中";
            case TVPlayerManager.PLAY_STATE_ENDED:
                return "播放结束";
            default:
                return String.format(Locale.getDefault(), "未知状态(%d)", state);
        }
    }

    // ===================== 对外暴露的监听注册/注销方法 =====================

    /**
     * 注册播放状态变化监听
     */
    public void addOnPlayerStateChangeListener(OnPlayerStateChangeListener listener) {
        if (!mStateListeners.contains(listener)) {
            mStateListeners.add(listener);
        }
    }

    /**
     * 注销播放状态变化监听
     */
    public void removeOnPlayerStateChangeListener(OnPlayerStateChangeListener listener) {
        mStateListeners.remove(listener);
    }

    /**
     * 注册播放错误监听
     */
    public void addOnPlayerErrorListener(OnPlayerErrorListener listener) {
        if (!mErrorListeners.contains(listener)) {
            mErrorListeners.add(listener);
        }
    }

    /**
     * 注销播放错误监听
     */
    public void removeOnPlayerErrorListener(OnPlayerErrorListener listener) {
        mErrorListeners.remove(listener);
    }

    /**
     * 注册直播信息更新监听
     */
    public void addOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        if (!mLiveInfoListeners.contains(listener)) {
            mLiveInfoListeners.add(listener);
        }
    }

    /**
     * 注销直播信息更新监听
     */
    public void removeOnLiveInfoUpdateListener(OnLiveInfoUpdateListener listener) {
        mLiveInfoListeners.remove(listener);
    }

    /**
     * 注册播放源失效监听
     */
    public void addOnSourceFailedListener(OnSourceFailedListener listener) {
        if (!mSourceFailedListeners.contains(listener)) {
            mSourceFailedListeners.add(listener);
        }
    }

    /**
     * 注销播放源失效监听
     */
    public void removeOnSourceFailedListener(OnSourceFailedListener listener) {
        mSourceFailedListeners.remove(listener);
    }

    /**
     * 清空所有监听
     */
    public void clearAllListeners() {
        mStateListeners.clear();
        mErrorListeners.clear();
        mLiveInfoListeners.clear();
        mSourceFailedListeners.clear();
    }

    // ===================== 回调接口定义 =====================

    /**
     * 播放状态变化监听
     */
    public interface OnPlayerStateChangeListener {
        /**
         * 状态变化回调
         * @param state 状态值（参考TVPlayerManager的PLAY_STATE_*常量）
         * @param stateName 状态名称（如：播放中、缓冲中）
         */
        void onStateChanged(int state, String stateName);
    }

    /**
     * 播放错误监听
     */
    public interface OnPlayerErrorListener {
        /**
         * 播放错误回调
         * @param errorMsg 错误信息
         */
        void onPlayError(String errorMsg);
    }

    /**
     * 直播信息更新监听（复用TVPlayerManager的LiveInfo）
     */
    public interface OnLiveInfoUpdateListener {
        /**
         * 直播信息更新回调
         * @param info 直播信息（分辨率、码率、音频格式等）
         */
        void onLiveInfoUpdate(TVPlayerManager.LiveInfo info);
    }

    /**
     * 播放源失效监听
     */
    public interface OnSourceFailedListener {
        /**
         * 播放源失效回调（重试次数达上限）
         */
        void onSourceFailed();
    }

    // ===================== 辅助方法 =====================

    /**
     * 获取当前播放状态
     */
    public int getCurrentState() {
        if (mPlayerManager != null && mPlayerManager.player != null) {
            return mPlayerManager.player.getPlaybackState();
        }
        return TVPlayerManager.PLAY_STATE_IDLE;
    }

    /**
     * 获取当前播放状态名称
     */
    public String getCurrentStateName() {
        return getStateName(getCurrentState());
    }

    /**
     * 释放资源
     */
    public void release() {
        clearAllListeners();
        mMainHandler.removeCallbacksAndMessages(null);
        sInstance = null;
    }
}
