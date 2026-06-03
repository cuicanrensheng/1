package com.iptvlive.activity;
import com.tv.live.R;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.iptvlive.bean.ChannelBean;
import com.iptvlive.bean.EpgInfoBean;
import com.iptvlive.receiver.RefreshSubReceiver;
import com.iptvlive.util.AppSpUtil;
import com.iptvlive.util.LogSpUtil;
import com.iptvlive.util.PlayErrClassUtil;
import com.iptvlive.util.PlayHeaderUtil;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 主播放页面
 * 上下键换台、MENU进设置、INFO弹出EPG
 * OSD右上角频道号+底部信息栏，换台弹出3秒自动消失
 */
public class PlayActivity extends AppCompatActivity {
    //播放器画布
    private SurfaceView mSurface;
    private ExoPlayer mExoPlayer;
    //全局频道列表
    private List<ChannelBean> mAllChannelList;
    //当前播放频道
    private ChannelBean mPlayChannel;
    //当前下标
    private int mCurrentIndex = 0;

    //OSD控件
    private TextView mTvTopNum;
    private View mOsdRoot;
    private TextView mTvChName, mTvChIndex, mTvInfo, mTvEpgNow;

    //OSD延时隐藏
    private final Handler mOsdHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideOsdRun = () -> {
        mTvTopNum.setVisibility(View.GONE);
        mOsdRoot.setVisibility(View.GONE);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play);
        bindView();

        //读取全局频道，无数据内置CCTV1
        mAllChannelList = RefreshSubReceiver.globalChannelList;
        if (mAllChannelList == null || mAllChannelList.isEmpty()) {
            mAllChannelList = new ArrayList<>();
            ChannelBean def = new ChannelBean();
            def.name = "CCTV1";
            def.url = "https://iptv.example/cctv1.m3u8";
            def.backupUrls = new ArrayList<>();
            mAllChannelList.add(def);
            LogSpUtil.addParseLog("【无订阅，加载默认CCTV1】");
        }

        mCurrentIndex = 0;
        playIndex(mCurrentIndex);
        showOsd(3000);
    }

    //绑定控件
    private void bindView() {
        mSurface = findViewById(R.id.surface);
        mTvTopNum = findViewById(R.id.tv_top_ch_num);
        mOsdRoot = findViewById(R.id.ll_osd_root);
        mTvChName = findViewById(R.id.tv_ch_name);
        mTvChIndex = findViewById(R.id.tv_ch_no);
        mTvInfo = findViewById(R.id.tv_fhd);
        mTvEpgNow = findViewById(R.id.tv_epg_info);
    }

    //根据下标播放
    private void playIndex(int pos) {
        mPlayChannel = mAllChannelList.get(pos);
        releasePlayer();
        //按频道优先级组装header
        Map<String, String> headerMap = PlayHeaderUtil.getPlayHeader(mPlayChannel);
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory();
        httpFactory.setDefaultRequestProperties(headerMap);
        DefaultMediaSourceFactory mediaFac = new DefaultMediaSourceFactory(httpFactory);
        //初始化播放器
        mExoPlayer = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaFac)
                .setLoadControl(new DefaultLoadControl())
                .build();
        mExoPlayer.setVideoSurfaceView(mSurface);
        MediaItem item = MediaItem.fromUri(mPlayChannel.url);
        mExoPlayer.setMediaItem(item);
        mExoPlayer.prepare();
        mExoPlayer.play();

        //播放异常监听
        mExoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                String err = PlayErrClassUtil.getErrTypeName(error) + "：" + mPlayChannel.name;
                LogSpUtil.addParseLog(err);
            }
        });
    }

    //展示OSD信息栏
    private void showOsd(int delayMs) {
        mOsdHandler.removeCallbacks(hideOsdRun);
        String idxStr = String.valueOf(mCurrentIndex + 1);
        mTvTopNum.setText(idxStr);
        mTvTopNum.setVisibility(View.VISIBLE);
        mTvChName.setText(mPlayChannel.name);
        mTvChIndex.setText("频道:" + idxStr);
        mTvInfo.setText(mPlayChannel.resolution + " | 立体声 | " + mPlayChannel.bitrate);

        //匹配当前EPG
        String epgTip = "暂无EPG节目";
        List<EpgInfoBean> epgAll = RefreshSubReceiver.globalEpgList;
        if (epgAll != null && !epgAll.isEmpty() && mPlayChannel.tvgId != null) {
            long now = System.currentTimeMillis();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
            for (EpgInfoBean epg : epgAll) {
                if (!epg.channelId.equals(mPlayChannel.tvgId)) continue;
                try {
                    long st = sdf.parse(epg.startTime).getTime();
                    long et = sdf.parse(epg.endTime).getTime();
                    if (now >= st && now <= et) {
                        String sStr = new SimpleDateFormat("HH:mm").format(st);
                        String eStr = new SimpleDateFormat("HH:mm").format(et);
                        epgTip = sStr + "~" + eStr + " 正在播放：" + epg.proName;
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }
        mTvEpgNow.setText(epgTip);
        mOsdRoot.setVisibility(View.VISIBLE);
        mOsdHandler.postDelayed(hideOsdRun, delayMs);
    }

    //上一个频道
    private void prevCh() {
        mCurrentIndex--;
        if (mCurrentIndex < 0) mCurrentIndex = mAllChannelList.size() - 1;
        playIndex(mCurrentIndex);
        showOsd(3000);
        LogSpUtil.addOperCrashLog("【切台】" + mPlayChannel.name);
    }

    //下一个频道
    private void nextCh() {
        mCurrentIndex++;
        if (mCurrentIndex >= mAllChannelList.size()) mCurrentIndex = 0;
        playIndex(mCurrentIndex);
        showOsd(3000);
        LogSpUtil.addOperCrashLog("【切台】" + mPlayChannel.name);
    }

    //遥控器按键
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                prevCh();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                nextCh();
                return true;
            case KeyEvent.KEYCODE_MENU:
                startActivity(new Intent(this, SettingActivity.class));
                LogSpUtil.addOperCrashLog("【打开设置页面】");
                return true;
            case KeyEvent.KEYCODE_INFO:
                new EpgDialog(this, mPlayChannel.name).show();
                LogSpUtil.addOperCrashLog("【打开EPG弹窗】" + mPlayChannel.name);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    //释放播放器
    private void releasePlayer() {
        if (mExoPlayer != null) {
            mExoPlayer.release();
            mExoPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
        mOsdHandler.removeCallbacks(hideOsdRun);
    }
}
