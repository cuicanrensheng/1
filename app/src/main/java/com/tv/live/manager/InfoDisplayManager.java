package com.tv.live.manager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.SettingsActivity;
import com.tv.live.TVPlayerManager;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
/**
 * 信息展示管理器【修复三大问题版本】
 * 2026-06-27 修复1：码率显示延迟，调换更新顺序优先渲染码率画质
 * 2026-06-27 修复2：下一档节目匹配容错，匹配失败保留旧数据不闪烁
 * 2026-06-27 修复3：播放时长超大数字溢出，限制单日最大时长24h
 * 2026-06-27 补齐：完整tvNextTimeRange逻辑、跨天时间计算
 */
public class InfoDisplayManager {
    // ===================== 定时延时常量 =====================
    private static final long INFO_BAR_HIDE_DELAY = 3000;
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    private static final long PROGRAM_PROGRESS_INTERVAL = 60000;
    // ===================== UI控件引用 =====================
    private Context context;
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
    // 缓存上一档节目数据，匹配失败不闪烁
    private Channel.EpgItem lastCurrItem;
    private Channel.EpgItem lastNextItem;
    // ===================== 调度变量 =====================
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Channel currentPlayChannel;
    private final Runnable hideInfoBarTask = new Runnable() {
        @Override
        public void run() {
            if(infoBar != null) infoBar.setVisibility(View.GONE);
        }
    };
    private final Runnable hideChannelNumTask = new Runnable() {
        @Override
        public void run() {
            if(tvChannelNum != null) tvChannelNum.setVisibility(View.GONE);
        }
    };
    private final Runnable refreshProgressTask = new Runnable() {
        @Override
        public void run() {
            if(currentPlayChannel != null){
                updateEpgInternal(currentPlayChannel);
            }
            mainHandler.postDelayed(this, PROGRAM_PROGRESS_INTERVAL);
        }
    };
    // ===================== 构造方法 =====================
    public InfoDisplayManager(Context context,
                              TextView tvChannelNum,
                              View infoBar,
                              TextView tvChannelName,
                              TextView tvTagFhd,
                              TextView tvTagAudio,
                              TextView tvBitrate,
                              TextView tvCurrentProgramName,
                              TextView tvCurrentTimeRange,
                              ProgressBar progressProgram,
                              TextView tvRemainingTime,
                              TextView tvNextProgramName,
                              TextView tvNextTimeRange){
        this.context = context.getApplicationContext();
        this.tvChannelNum = tvChannelNum;
        this.infoBar = infoBar;
        this.tvChannelName = tvChannelName;
        this.tvTagFhd = tvTagFhd;
        this.tvTagAudio = tvTagAudio;
        this.tvBitrate = tvBitrate;
        this.tvCurrentProgramName = tvCurrentProgramName;
        this.tvCurrentTimeRange = tvCurrentTimeRange;
        this.progressProgram = progressProgram;
        this.tvRemainingTime = tvRemainingTime;
        this.tvNextProgramName = tvNextProgramName;
        this.tvNextTimeRange = tvNextTimeRange;
        if(tvTagAudio != null){
            tvTagAudio.setText("立体声");
        }
    }
    // ===================== 频道数字弹窗 =====================
    public void showChannelNum(int num){
        if(tvChannelNum == null) return;
        tvChannelNum.setText(String.valueOf(num));
        tvChannelNum.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideChannelNumTask);
        mainHandler.postDelayed(hideChannelNumTask, CHANNEL_NUM_HIDE_DELAY);
    }
    public void hideChannelNum(){
        if(tvChannelNum == null) return;
        mainHandler.removeCallbacks(hideChannelNumTask);
        tvChannelNum.setVisibility(View.GONE);
    }
    // ===================== 底部信息栏【修复码率延迟：先更新码率再EPG】 =====================
    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo){
        if(infoBar == null || channel == null) return;
        currentPlayChannel = channel;
        infoBar.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideInfoBarTask);
        mainHandler.postDelayed(hideInfoBarTask, INFO_BAR_HIDE_DELAY);
        if(tvChannelName != null) tvChannelName.setText(channel.getName());
        // 修复1：优先更新码率、画质，不再等EPG加载完成才显示
        updateLiveInfo(liveInfo);
        // 后处理EPG节目信息
        updateEpgInternal(channel);
        startProgressLoop();
    }
    public void hideInfoBar(){
        if(infoBar == null) return;
        mainHandler.removeCallbacks(hideInfoBarTask);
        infoBar.setVisibility(View.GONE);
    }
    public void updateLiveInfo(TVPlayerManager.LiveInfo info){
        if(info == null) return;
        if(tvTagFhd != null){
            tvTagFhd.setText(parseQualityText(info.resolution));
        }
        if(tvBitrate != null){
            tvBitrate.setText(info.bitrate);
        }
    }
    private String parseQualityText(String resolution){
        if(resolution == null || resolution.isEmpty()) return "未知";
        try {
            String[] split = resolution.split("×");
            if(split.length >= 2){
                int height = Integer.parseInt(split[1].trim());
                if(height >= 1080) return "FHD";
                else if(height >=720) return "HD";
                else return "SD";
            }
        }catch (Exception e){
            SettingsActivity.log("【分辨率解析异常】" + resolution + " err:" + e.getMessage());
        }
        return resolution;
    }
    // ===================== EPG逻辑【修复节目闪烁：缓存上次节目】 =====================
    public void updateEpgInfo(Channel channel){
        if(channel == null) return;
        currentPlayChannel = channel;
        updateEpgInternal(channel);
    }
    private void updateEpgInternal(Channel channel){
        if(channel == null || tvCurrentProgramName == null) return;
        String channelName = channel.getName();
        try {
            SettingsActivity.logOperation("【EPG匹配】开始匹配频道:" + channelName);
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channelName);
            if((epgList == null || epgList.isEmpty()) && channelName != null){
                SettingsActivity.logOperation("【EPG匹配】精确匹配为空，执行模糊匹配");
                epgList = fuzzyMatchEpg(channelName);
            }
            // 修复2：无节目时不立即清空，保留上次UI，避免一闪而过“暂无下一档”
            if(epgList == null || epgList.size() == 0){
                SettingsActivity.logOperation("【EPG匹配】未获取节目，复用缓存节目");
                if(lastCurrItem != null){
                    refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                    refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
                }else {
                    setEpgEmptyUi();
                }
                return;
            }
            List<Channel.EpgItem> todayEpg = filterTodayEpg(epgList);
            if(todayEpg.isEmpty()){
                SettingsActivity.logOperation("【EPG匹配】今日无节目，复用缓存");
                if(lastCurrItem != null){
                    refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                    refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
                }else {
                    setEpgEmptyUi();
                }
                return;
            }
            sortEpgByTime(todayEpg);
            String nowTime = getCurrentTimeStr();
            Channel.EpgItem currItem = null;
            Channel.EpgItem nextItem = null;
            int currIndex = -1;
            for(int i=0; i<todayEpg.size(); i++){
                Channel.EpgItem item = todayEpg.get(i);
                String start = item.time;
                String end = (i+1 < todayEpg.size()) ? todayEpg.get(i+1).time : "23:59";
                if(timeBetween(nowTime, start, end)){
                    currItem = item;
                    currIndex = i;
                    if(i+1 < todayEpg.size()) nextItem = todayEpg.get(i+1);
                    break;
                }
            }
            // 更新缓存，下次无数据复用
            lastCurrItem = currItem;
            lastNextItem = nextItem;
            refreshCurrProgramUi(currItem, currIndex, todayEpg, nowTime);
            refreshNextProgramUi(nextItem, currIndex, todayEpg);
        }catch (Exception e){
            e.printStackTrace();
            SettingsActivity.logOperation("【EPG匹配异常】" + e.getMessage());
            // 异常也复用缓存，不全部清空
            if(lastCurrItem != null){
                refreshCurrProgramUi(lastCurrItem, 0, new ArrayList<>(), getCurrentTimeStr());
                refreshNextProgramUi(lastNextItem, 0, new ArrayList<>());
            }else {
                setEpgEmptyUi();
            }
        }
    }
    private List<Channel.EpgItem> fuzzyMatchEpg(String rawName){
        if(rawName == null || rawName.isEmpty()) return null;
        try {
            String clean = rawName.replaceAll("\\s+","").toLowerCase();
            SettingsActivity.logOperation("【EPG模糊匹配】待完善");
        }catch (Exception e){
            SettingsActivity.logOperation("【EPG模糊匹配失败】" + e.getMessage());
        }
        return null;
    }
    private List<Channel.EpgItem> filterTodayEpg(List<Channel.EpgItem> source){
        List<Channel.EpgItem> res = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        int weekNum = cal.get(Calendar.DAY_OF_WEEK);
        String[] weekArr = {"周日","周一","周二","周三","周四","周五","周六"};
        String todayWeek = weekArr[weekNum - 1];
        for(Channel.EpgItem item : source){
            if(item.dayName == null) continue;
            String day = item.dayName.trim();
            if("今天".equals(day) || todayWeek.equals(day)){
                res.add(item);
            }
        }
        return res;
    }
    private void sortEpgByTime(List<Channel.EpgItem> list){
        Collections.sort(list, new Comparator<Channel.EpgItem>() {
            @Override
            public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                return o1.time.compareTo(o2.time);
            }
        });
    }
    // ===================== 刷新当前节目UI【修复超大时长数字】 =====================
    private void refreshCurrProgramUi(Channel.EpgItem currItem, int currIdx, List<Channel.EpgItem> todayList, String now){
        if(currItem != null){
            tvCurrentProgramName.setText(currItem.title);
            String start = currItem.time;
            String end = (currIdx+1 < todayList.size()) ? todayList.get(currIdx+1).time : "23:59";
            if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText(start + " - " + end);
            long nowMs = timeToMs(now, false, 0);
            long sMs = timeToMs(start, false, 0);
            long eMs = timeToMs(end, true, sMs);
            if(progressProgram != null){
                long totalDuration = eMs - sMs;
                long played = nowMs - sMs;
                int progress = 0;
                if(totalDuration > 0){
                    progress = (int) (played * 100 / totalDuration);
                    progress = Math.max(0, Math.min(100, progress));
                }else {
                    SettingsActivity.logOperation("【进度异常】时长非法 start="+start+" end="+end+" total="+totalDuration);
                }
                progressProgram.setProgress(progress);
                progressProgram.invalidate();
            }
            // 修复3：限制单日最大时长，杜绝几十万小时溢出
            if(tvRemainingTime != null){
                long played = nowMs - sMs;
                // 负数说明当前时间还没到节目开始，直接显示0分钟
                if(played < 0){
                    tvRemainingTime.setText("已播放0分钟");
                    return;
                }
                long playedSec = played / 1000;
                // 限制单日最大86400秒（24h），防止跨天超大差值
                long validSec = playedSec % (24 * 3600);
                long playedMin = validSec / 60;
                if(playedMin >= 60){
                    int h = (int) (playedMin / 60);
                    int m = (int) (playedMin % 60);
                    tvRemainingTime.setText("已播放"+h+"时"+m+"分");
                }else {
                    tvRemainingTime.setText("已播放"+playedMin+"分钟");
                }
            }
        }else {
            tvCurrentProgramName.setText("暂无节目信息");
            if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
            if(progressProgram != null) {
                progressProgram.setProgress(0);
                progressProgram.invalidate();
            }
            if(tvRemainingTime != null) tvRemainingTime.setText("");
        }
    }
    private void refreshNextProgramUi(Channel.EpgItem nextItem, int currIdx, List<Channel.EpgItem> todayList){
        if(nextItem != null && tvNextProgramName != null && tvNextTimeRange != null){
            String s = nextItem.time;
            String e = (currIdx +2 < todayList.size()) ? todayList.get(currIdx+2).time : "23:59";
            tvNextTimeRange.setText(s + " - " + e);
            tvNextProgramName.setText(nextItem.title);
        }else {
            // 有缓存就不显示空白提示，解决短暂“暂无下一档”
            if(lastNextItem != null){
                String s = lastNextItem.time;
                String e = "23:59";
                tvNextTimeRange.setText(s + " - " + e);
                tvNextProgramName.setText(lastNextItem.title);
            }else {
                if(tvNextProgramName != null) tvNextProgramName.setText("暂无下一档节目");
                if(tvNextTimeRange != null) tvNextTimeRange.setText("");
            }
        }
    }
    private void setEpgEmptyUi(){
        if(tvCurrentProgramName != null) tvCurrentProgramName.setText("暂无节目信息");
        if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if(tvNextProgramName != null) tvNextProgramName.setText("");
        if(tvNextTimeRange != null) tvNextTimeRange.setText("");
        if(progressProgram != null) progressProgram.setProgress(0);
        if(tvRemainingTime != null) tvRemainingTime.setText("");
    }
    // ===================== 定时控制 =====================
    public void startProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
        mainHandler.postDelayed(refreshProgressTask, PROGRAM_PROGRESS_INTERVAL);
    }
    public void stopProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
    }
    // ===================== 时间工具（跨天兼容） =====================
    private String getCurrentTimeStr(){
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        return String.format("%02d:%02d", h, m);
    }
    private boolean timeBetween(String now, String start, String end){
        try {
            if (now == null || start == null || end == null) return false;
            long nowMs = timeToMs(now, false, 0);
            long startMs = timeToMs(start, false, 0);
            long endMs = timeToMs(end, true, startMs);
            return nowMs >= startMs && nowMs < endMs;
        }catch (Exception e){
            SettingsActivity.logOperation("【时段匹配异常】"+e.getMessage());
            return false;
        }
    }
    private long timeToMs(String timeStr, boolean isEndTime, long startMs){
        try {
            String[] split = timeStr.split(":");
            int h = Integer.parseInt(split[0].trim());
            int m = Integer.parseInt(split[1].trim());
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long ms = cal.getTimeInMillis();
            if(isEndTime && ms <= startMs){
                cal.add(Calendar.DAY_OF_MONTH, 1);
                ms = cal.getTimeInMillis();
            }
            return ms;
        }catch (Exception e){
            SettingsActivity.logOperation("【时间转换失败】"+timeStr+" err:"+e.getMessage());
            return 0;
        }
    }
    // ===================== 资源释放 =====================
    public void release(){
        mainHandler.removeCallbacks(hideInfoBarTask);
        mainHandler.removeCallbacks(hideChannelNumTask);
        mainHandler.removeCallbacks(refreshProgressTask);
        currentPlayChannel = null;
        lastCurrItem = null;
        lastNextItem = null;
        context = null;
        tvChannelNum = null;
        infoBar = null;
        tvChannelName = null;
        tvTagFhd = null;
        tvTagAudio = null;
        tvBitrate = null;
        tvCurrentProgramName = null;
        tvCurrentTimeRange = null;
        progressProgram = null;
        tvRemainingTime = null;
        tvNextProgramName = null;
        tvNextTimeRange = null;
    }
}
