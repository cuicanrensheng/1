package com.tv.live.tv;

import android.content.ContentUris;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;

import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.TVPlayerManager;

import java.util.List;

public class LiveTvInputService extends TvInputService {

    private static final String TAG = "LiveTvInputService";

    @Override
    public Session onCreateSession(String inputId) {
        return new TvInputSession();
    }

    public class TvInputSession extends TvInputService.Session {

        private TVPlayerManager mPlayerManager;
        private Handler mMainHandler = new Handler(Looper.getMainLooper());

        public TvInputSession() {
            super(LiveTvInputService.this);
            mPlayerManager = TVPlayerManager.getInstance(LiveTvInputService.this);
        }

        @Override
        public boolean onSetSurface(Surface surface) {
            if (mPlayerManager != null) {
                mPlayerManager.setSurface(surface);
                Log.d(TAG, "Surface 已绑定到播放器");
            }
            return true;
        }

        @Override
        public boolean onTune(Uri channelUri) {
            long channelId = ContentUris.parseId(channelUri);
            Log.d(TAG, "正在切换到频道 ID: " + channelId);

            MainActivity mainActivity = MainActivity.getRunningInstance();
            List<Channel> channelList = mainActivity != null ? mainActivity.channelSourceList : null;
            String playUrl = null;
            String channelName = null;

            if (channelList != null && !channelList.isEmpty()) {
                for (Channel ch : channelList) {
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
                });
                notifyVideoAvailable();
                return true;
            } else {
                Log.w(TAG, "未找到该频道，播放失败");
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
                return false;
            }
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            // 忽略字幕设置
        }

        @Override
        public void onSetStreamVolume(float volume) {
            // 忽略音量设置
        }

        @Override
        public void onSetVolume(float volume) {
            // 忽略音量设置
        }

        @Override
        public void onRelease() {
            Log.d(TAG, "TIF Session 释放");
            if (mPlayerManager != null) {
                mPlayerManager.release();
                mPlayerManager = null;
            }
            // ✅ 修复：通知系统视频已不可用，防止退出时系统界面卡黑屏
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            MainActivity activity = MainActivity.getRunningInstance();
            if (activity != null) {
                return activity.dispatchKeyEvent(event);
            }
            return super.onKeyUp(keyCode, event);
        }
    }
}
