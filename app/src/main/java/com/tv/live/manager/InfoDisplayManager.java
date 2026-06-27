import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.tv.live.bean.EpgInfo;
import com.tv.live.bean.Channel;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class InfoDisplayManager {
    private Context mContext;
    private TextView tvChannelNum;
    private View infoBar;
    private TextView tvChannelName;
    private TextView tvTagFhd;
    private TextView tvTagAudio;
    private TextView tvBitrate;
    private TextView tvCurrentProgramName;
    private TextView tvCurrentTimeRange;
    private ProgressBar progressProgram;
    private TextView tvRemainingTime;
    private TextView tvNextProgramName;
    private TextView tvNextTimeRange;
    
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mProgressUpdateRunnable;
    private SimpleDateFormat mTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private SimpleDateFormat mEpgTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public InfoDisplayManager(Context context, TextView tv_channel_num, View info_bar,
                             TextView tv_channel_name, TextView tv_tag_fhd, TextView tv_tag_audio,
                             TextView tv_bitrate, TextView tv_current_program_name,
                             TextView tv_current_time_range, ProgressBar progress_program,
                             TextView tv_remaining_time, TextView tv_next_program_name,
                             TextView tv_next_time_range) {
        this.mContext = context;
        this.tvChannelNum = tv_channel_num;
        this.infoBar = info_bar;
        this.tvChannelName = tv_channel_name;
        this.tvTagFhd = tv_tag_fhd;
        this.tvTagAudio = tv_tag_audio;
        this.tvBitrate = tv_bitrate;
        this.tvCurrentProgramName = tv_current_program_name;
        this.tvCurrentTimeRange = tv_current_time_range;
        this.progressProgram = progress_program;
        this.tvRemainingTime = tv_remaining_time;
        this.tvNextProgramName = tv_next_program_name;
        this.tvNextTimeRange = tv_next_time_range;
        
        initProgressUpdateTask();
    }

    /**
     * 初始化进度更新任务（每秒更新一次）
     */
    private void initProgressUpdateTask() {
        mProgressUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateProgramProgress();
                mHandler.postDelayed(this, 1000);
            }
        };
    }

    /**
     * 更新EPG信息
     */
    public void updateEpgInfo(Channel channel) {
        if (channel == null || channel.getCurrentEpg() == null) {
            clearEpgInfo();
            return;
        }
        
        // 1. 获取当前节目EPG时间
        EpgInfo curEpg = channel.getCurrentEpg();
        String startStr = curEpg.getStartTimeStr();
        String endStr = curEpg.getEndTimeStr();
        
        // 显示当前节目时间范围
        tvCurrentTimeRange.setText(String.format("%s - %s", startStr, endStr));
        tvCurrentProgramName.setText(curEpg.getProgramName());
        
        // 2. 处理下一档节目
        EpgInfo nextEpg = channel.getNextEpg();
        if (nextEpg != null) {
            String nextTime = String.format("%s - %s", nextEpg.getStartTimeStr(), nextEpg.getEndTimeStr());
            String nextProgram = String.format("%s %s", nextTime, nextEpg.getProgramName());
            tvNextTimeRange.setText(nextTime);
            tvNextProgramName.setText(nextProgram);
        } else {
            tvNextTimeRange.setText("");
            tvNextProgramName.setText("暂无下一档节目");
        }
        
        // 启动进度更新
        startProgressUpdate();
    }

    /**
     * 计算并更新节目播放进度
     */
    private void updateProgramProgress() {
        if (progressProgram == null) return;
        
        Channel currentChannel = getCurrentChannel(); // 需实现获取当前播放频道逻辑
        if (currentChannel == null || currentChannel.getCurrentEpg() == null) return;
        
        EpgInfo curEpg = currentChannel.getCurrentEpg();
        try {
            // 解析时间
            Date startTime = mEpgTimeFormat.parse(curEpg.getStartTimeStr());
            Date endTime = mEpgTimeFormat.parse(curEpg.getEndTimeStr());
            Date currentTime = new Date();
            
            long startMs = startTime.getTime();
            long endMs = endTime.getTime();
            long currentMs = currentTime.getTime();
            
            // 计算进度百分比
            if (endMs > startMs && currentMs >= startMs) {
                long duration = endMs - startMs;
                long elapsed = currentMs - startMs;
                int progress = (int) ((elapsed * 100) / duration);
                progress = Math.min(progress, 100); // 防止超过100%
                progressProgram.setProgress(progress);
                
                // 格式化已播放时长（毫秒转分钟）
                String playedTime = formatMsToMinute(elapsed);
                // 格式化剩余时长
                String remainingTime = formatMsToMinute(duration - elapsed);
                tvRemainingTime.setText(String.format("已播放：%s | 剩余：%s", playedTime, remainingTime));
            } else {
                progressProgram.setProgress(0);
                tvRemainingTime.setText("节目尚未开始");
            }
        } catch (ParseException e) {
            e.printStackTrace();
            progressProgram.setProgress(0);
            tvRemainingTime.setText("时间解析失败");
        }
    }

    /**
     * 毫秒转分钟格式化
     * @param ms 毫秒数
     * @return 格式：XX分XX秒 或 XX小时XX分
     */
    private String formatMsToMinute(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        
        if (minutes >= 60) {
            long hours = minutes / 60;
            minutes = minutes % 60;
            return String.format("%d小时%d分", hours, minutes);
        } else {
            return String.format("%d分%d秒", minutes, seconds);
        }
    }

    /**
     * 启动进度更新
     */
    private void startProgressUpdate() {
        stopProgressUpdate();
        mHandler.post(mProgressUpdateRunnable);
    }

    /**
     * 停止进度更新
     */
    private void stopProgressUpdate() {
        mHandler.removeCallbacks(mProgressUpdateRunnable);
    }

    /**
     * 清空EPG信息
     */
    private void clearEpgInfo() {
        tvCurrentProgramName.setText("");
        tvCurrentTimeRange.setText("");
        tvNextProgramName.setText("");
        tvNextTimeRange.setText("");
        tvRemainingTime.setText("");
        progressProgram.setProgress(0);
        stopProgressUpdate();
    }

    /**
     * 显示信息栏
     */
    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo) {
        if (channel == null) return;
        
        tvChannelName.setText(channel.getName());
        // 其他信息展示逻辑...
        
        // 更新EPG信息
        updateEpgInfo(channel);
    }

    /**
     * 释放资源
     */
    public void release() {
        stopProgressUpdate();
        mHandler.removeCallbacksAndMessages(null);
    }

    // 辅助方法 - 获取当前播放频道（需根据实际业务实现）
    private Channel getCurrentChannel() {
        if (MainActivity.mInstance != null) {
            int currentIndex = MainActivity.mInstance.currentPlayIndex;
            if (currentIndex >= 0 && currentIndex < MainActivity.mInstance.channelSourceList.size()) {
                return MainActivity.mInstance.channelSourceList.get(currentIndex);
            }
        }
        return null;
    }

    // 其他原有方法...
    public void showChannelNum(int num) {
        if (tvChannelNum != null) {
            tvChannelNum.setText(String.valueOf(num));
        }
    }

    public void hideChannelNum() {
        if (tvChannelNum != null) {
            tvChannelNum.setText("");
        }
    }

    public void updateLiveInfo(TVPlayerManager.LiveInfo info) {
        if (tvBitrate != null && info != null) {
            tvBitrate.setText(info.bitrate);
        }
        // 其他直播信息更新...
    }
}
