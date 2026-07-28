package com.tv.live.tv;

import android.content.ContentUris;
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

public class TvInputService extends TvInputService {

    private static final String TAG = "TvInputService";

    @Override
    public Session onCreateSession(String inputId) {
        return new TvInputSession();
    }

    // ==========================================
    // ✅ 注意！大括号必须在这里闭合，下面的类才是嵌套在内部的
    // ==========================================
    public class TvInputSession extends TvInputService.Session {

        private TVPlayerManager mPlayerManager;
        private Handler mMainHandler = new Handler(Looper.getMainLooper());

        public TvInputSession() {
            // ✅ 正确引用外部类
            super(TvInputService.this);
            mPlayerManager = TVPlayerManager.getInstance(TvInputService.this);
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
            // ✅ 使用 ContentUris.parseId 解析频道ID，解决找不到符号的问题
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
                // ✅ 使用正确常量
                notifyVideoUnavailable(TvInputService.VIDEO_UNAVAILABLE_REASON_TUNING);
                return false;
            }
        }

        // ✅ 修复：API 36 新增的抽象方法：字幕启用
        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            // 直接放行，目前不支持字幕，可忽略
        }

        // ✅ 修复：API 36 新增的抽象方法：流音量
        @Override
        public void onSetStreamVolume(float volume) {
            // 透传，直接使用系统音量
        }

        @Override
        public void onSetVolume(float volume) {
            // 保留旧方法，由系统控制
        }

        @Override
        public void onRelease() {
            Log.d(TAG, "TIF Session 释放");
            if (mPlayerManager != null) {
                mPlayerManager.release();
                mPlayerManager = null;
            }
            // ✅ 使用正确常量
            notifyVideoUnavailable(TvInputService.VIDEO_UNAVAILABLE_REASON_BUFFERING);
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
