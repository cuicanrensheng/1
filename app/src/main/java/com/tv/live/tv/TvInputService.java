package com.tv.live.tv;

import android.content.ContentValues;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputService;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.TVPlayerManager;

import java.util.List;

public class TvInputService extends TvInputService {

    private static final String TAG = "TvInputService";

    @Override
    public Session onCreateSession(String inputId) {
        return new TvInputSession();
    }

    /**
     * TIF 会话 - 包含实际播放逻辑
     */
    public class TvInputSession extends TvInputService.Session {

        private TVPlayerManager mPlayerManager;
        private Surface mSurface;
        private Handler mMainHandler = new Handler(Looper.getMainLooper());

        public TvInputSession() {
            super(TvInputService.this);
            // 实例化您的播放管理器（使用 ApplicationContext）
            mPlayerManager = TVPlayerManager.getInstance(TvInputService.this);
        }

        // 1. 系统回调：为播放提供画布 Surface
        @Override
        public void onSetSurface(Surface surface) {
            mSurface = surface;
            if (mPlayerManager != null) {
                // 将播放器渲染指向系统提供的 Surface
                mPlayerManager.setSurface(surface);
                Log.d(TAG, "Surface 已绑定到播放器");
            }
        }

        // 2. 系统回调：切换频道
        @Override
        public void onTune(Uri channelUri) {
            long channelId = TvContract.Channel.getChannelIdFromUri(channelUri);
            Log.d(TAG, "正在切换到频道 ID: " + channelId);

            MainActivity mainActivity = MainActivity.getRunningInstance();
            List<Channel> channelList = mainActivity != null ? mainActivity.channelSourceList : null;
            String playUrl = null;
            String channelName = null;

            if (channelList != null && !channelList.isEmpty()) {
                for (Channel ch : channelList) {
                    // 注意：这里需要根据您的 Channel 类中的 channelId 字段进行匹配
                    String chIdStr = ch.getChannelId();
                    if (chIdStr != null) {
                        try {
                            if (Long.parseLong(chIdStr) == channelId) {
                                playUrl = ch.getPlayUrl();
                                channelName = ch.getName();
                                Log.d(TAG, "找到匹配频道: " + channelName + " -> " + playUrl);
                                break;
                            }
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "解析频道ID异常", e);
                        }
                    }
                }
            }

            if (playUrl != null && !playUrl.isEmpty()) {
                final String finalPlayUrl = playUrl;
                final String finalChannelName = channelName;
                mMainHandler.post(() -> {
                    mPlayerManager.playUrl(finalPlayUrl, finalChannelName);
                    Log.d(TAG, "播放器已开始播放");
                });

                // 通知系统视频已就绪
                notifyVideoAvailable();
            } else {
                Log.w(TAG, "未找到该频道，播放失败");
                notifyVideoUnavailable(TvInputService.VIDEO_UNAVAILABLE_REASON_TUNING);
            }
        }

        // 3. 系统回调：音量控制（基本适配）
        @Override
        public void onSetVolume(float volume) {
            // 可将音量同步到您的播放器，当前直接放行由系统处理
        }

        // 4. 释放资源
        @Override
        public void onRelease() {
            Log.d(TAG, "TIF Session 释放");
            if (mPlayerManager != null) {
                mPlayerManager.release();
                mPlayerManager = null;
            }
            notifyVideoUnavailable(TvInputService.VIDEO_UNAVAILABLE_REASON_BUFFERING);
        }

        // 5. 按键转发（用于切台）
        @Override
        public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
            MainActivity activity = MainActivity.getRunningInstance();
            if (activity != null) {
                // 将按键交给 MainActivity 处理，触发其原有的 ChannelPanelController 逻辑
                return activity.dispatchKeyEvent(event);
            }
            return super.onKeyUp(keyCode, event);
        }
    }
}
