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
 * 信息展示管理器【Java完整版】
 * 适配改造后的MainActivity，所有底部信息栏、频道数字、EPG、进度逻辑完全内聚，页面仅调用对外API
 * 2026-06-25 优化：EPG精确+模糊匹配、全流程日志、分辨率自动分级、定时进度刷新
 * 统一管控所有UI控件、定时任务、时间计算，消除Activity耦合
 */
public class InfoDisplayManager {
    // ===================== 定时延时常量（统一管理，方便全局修改） =====================
    /** 底部信息栏自动隐藏 3000ms */
    private static final long INFO_BAR_HIDE_DELAY = 3000;
    /** 右上角频道数字弹窗自动隐藏 3000ms */
    private static final long CHANNEL_NUM_HIDE_DELAY = 3000;
    /** EPG节目进度刷新间隔 60000ms(1分钟)，减少性能消耗 */
    private static final long PROGRAM_PROGRESS_INTERVAL = 60000;

    // ===================== UI控件引用 =====================
    private Context context;
    private TextView tvChannelNum;          // 右上角频道号
    private View infoBar;                   // 底部信息栏根布局
    private TextView tvChannelName;         // 频道名称
    private TextView tvTagFhd;              // 画质标签 FHD/HD/SD
    private TextView tvTagAudio;            // 音频标识（固定立体声）
    private TextView tvBitrate;             // 实时码率
    private TextView tvCurrentProgramName;  // 当前节目名
    private TextView tvCurrentTimeRange;    // 当前节目时段
    private ProgressBar progressProgram;    // 节目进度条
    private TextView tvRemainingTime;       // 已播放时长
    private TextView tvNextProgramName;     // 下一档节目
    private TextView tvNextTimeRange;       // 下一档时段（预留）

    // ===================== 调度、缓存变量 =====================
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Channel currentPlayChannel; // 当前播放频道缓存（用于定时刷新EPG）

    // 自动隐藏任务Runnable
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
    // 每分钟刷新EPG进度任务
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
    /**
     * 构造器：一次性注入所有信息栏控件，ApplicationContext防内存泄漏
     * @param context 页面上下文
     * @param tvChannelNum 频道数字TextView
     * @param infoBar 底部信息栏根View
     * @param tvChannelName 频道名称
     * @param tvTagFhd 画质标签
     * @param tvTagAudio 音频标签
     * @param tvBitrate 码率
     * @param tvCurrentProgramName 当前节目
     * @param tvCurrentTimeRange 当前节目时间
     * @param progressProgram 进度条
     * @param tvRemainingTime 已播放时长
     * @param tvNextProgramName 下一档节目
     * @param tvNextTimeRange 下一档时段
     */
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
        // 使用应用上下文，避免持有Activity造成泄漏
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

        // 固定音频文字：立体声
        if(tvTagAudio != null){
            tvTagAudio.setText("立体声");
        }
    }

    // ===================== 1. 频道数字弹窗 API =====================
    /** 显示频道号，自动3秒消失 */
    public void showChannelNum(int num){
        if(tvChannelNum == null) return;
        tvChannelNum.setText(String.valueOf(num));
        tvChannelNum.setVisibility(View.VISIBLE);
        // 移除旧延时任务，重置倒计时
        mainHandler.removeCallbacks(hideChannelNumTask);
        mainHandler.postDelayed(hideChannelNumTask, CHANNEL_NUM_HIDE_DELAY);
    }

    /** 立刻隐藏频道数字弹窗 */
    public void hideChannelNum(){
        if(tvChannelNum == null) return;
        mainHandler.removeCallbacks(hideChannelNumTask);
        tvChannelNum.setVisibility(View.GONE);
    }

    // ===================== 2. 底部信息栏对外API =====================
    /**
     * 展示完整信息栏：填充频道、直播流、EPG，自动3秒后隐藏，启动进度定时刷新
     * @param channel 当前播放频道
     * @param liveInfo 播放器返回码率、分辨率信息
     */
    public void showInfoBar(Channel channel, TVPlayerManager.LiveInfo liveInfo){
        if(infoBar == null || channel == null) return;
        currentPlayChannel = channel;
        // 显示信息栏，重置隐藏计时
        infoBar.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hideInfoBarTask);
        mainHandler.postDelayed(hideInfoBarTask, INFO_BAR_HIDE_DELAY);

        // 频道名称
        if(tvChannelName != null) tvChannelName.setText(channel.getName());
        // 更新码率、画质
        updateLiveInfo(liveInfo);
        // 更新EPG节目信息
        updateEpgInternal(channel);
        // 启动每分钟进度刷新
        startProgressLoop();
    }

    /** 立刻隐藏底部信息栏 */
    public void hideInfoBar(){
        if(infoBar == null) return;
        mainHandler.removeCallbacks(hideInfoBarTask);
        infoBar.setVisibility(View.GONE);
    }

    /** 更新直播流画质、码率标签 */
    public void updateLiveInfo(TVPlayerManager.LiveInfo info){
        if(info == null) return;
        // 自动解析分辨率分级 FHD/HD/SD
        if(tvTagFhd != null){
            tvTagFhd.setText(parseQualityText(info.resolution));
        }
        // 码率直接赋值
        if(tvBitrate != null){
            tvBitrate.setText(info.bitrate);
        }
    }

    /**
     * 解析分辨率，返回画质文字
     * @param resolution 如 1920×1080
     * @return FHD / HD / SD / 未知
     */
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
            SettingsActivity.log("【信息栏分辨率解析异常】" + resolution + " err:" + e.getMessage());
        }
        return resolution;
    }

    // ===================== 3. EPG对外接口 =====================
    /** 外部主动刷新EPG（切换日期、刷新源时调用） */
    public void updateEpgInfo(Channel channel){
        if(channel == null) return;
        currentPlayChannel = channel;
        updateEpgInternal(channel);
    }

    /** EPG核心处理逻辑（精确匹配→模糊匹配→筛选今日→排序→匹配当前节目） */
    private void updateEpgInternal(Channel channel){
        if(channel == null || tvCurrentProgramName == null) return;
        String channelName = channel.getName();
        try {
            SettingsActivity.logOperation("【EPG匹配】开始匹配频道:" + channelName);
            // 1.精确匹配节目列表
            List<Channel.EpgItem> epgList = EpgManager.getInstance().getEpg(channelName);
            // 2.精确无数据，走模糊匹配
            if((epgList == null || epgList.isEmpty()) && channelName != null){
                SettingsActivity.logOperation("【EPG匹配】精确匹配为空，执行模糊匹配");
                epgList = fuzzyMatchEpg(channelName);
            }
            // 3.完全无节目
            if(epgList == null || epgList.size() == 0){
                SettingsActivity.logOperation("【EPG匹配】未获取任何节目");
                setEpgEmptyUi();
                return;
            }
            SettingsActivity.logOperation("【EPG匹配】获取节目总数:" + epgList.size());
            // 4.筛选今日节目
            List<Channel.EpgItem> todayEpg = filterTodayEpg(epgList);
            if(todayEpg.isEmpty()){
                SettingsActivity.logOperation("【EPG匹配】今日无节目");
                setEpgEmptyUi();
                return;
            }
            SettingsActivity.logOperation("【EPG匹配】今日节目:" + todayEpg.size());
            // 5.按时段升序排序
            sortEpgByTime(todayEpg);
            // 6.匹配当前时段节目
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
            if(currItem != null){
                SettingsActivity.logOperation("【EPG匹配】当前节目:" + currItem.title);
            }else {
                SettingsActivity.logOperation("【EPG匹配】当前无正在播放节目");
            }
            // 7.刷新UI
            refreshCurrProgramUi(currItem, currIndex, todayEpg, nowTime);
            refreshNextProgramUi(nextItem, currIndex, todayEpg);
        }catch (Exception e){
            e.printStackTrace();
            SettingsActivity.logOperation("【EPG匹配异常】" + e.getMessage());
            setEpgEmptyUi();
        }
    }

    /** 模糊匹配占位方法（待EpgManager开放全频道接口后完善） */
    private List<Channel.EpgItem> fuzzyMatchEpg(String rawName){
        if(rawName == null || rawName.isEmpty()) return null;
        try {
            String clean = rawName.replaceAll("\\s+","").toLowerCase();
            SettingsActivity.logOperation("【EPG模糊匹配】功能暂未完成，需EpgManager提供全频道列表接口");
        }catch (Exception e){
            SettingsActivity.logOperation("【EPG模糊匹配失败】" + e.getMessage());
        }
        return null;
    }

    /** 过滤今日节目（匹配“今天”/星期文字） */
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

    /** EPG节目按时间升序 */
    private void sortEpgByTime(List<Channel.EpgItem> list){
        Collections.sort(list, new Comparator<Channel.EpgItem>() {
            @Override
            public int compare(Channel.EpgItem o1, Channel.EpgItem o2) {
                return o1.time.compareTo(o2.time);
            }
        });
    }

    /** 刷新当前节目UI（名称、时段、进度、已播放时长） */
    private void refreshCurrProgramUi(Channel.EpgItem currItem, int currIdx, List<Channel.EpgItem> todayList, String now){
        if(currItem != null){
            tvCurrentProgramName.setText(currItem.title);
            String start = currItem.time;
            String end = (currIdx+1 < todayList.size()) ? todayList.get(currIdx).time : "23:59";
            if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText(start + " - ");

            long nowMs = timeToMs(now);
            long sMs = timeToMs(start);
            long eMs = timeToMs(end);
            if(eMs > sMs && progressProgram != null){
                int progress = (int) ((nowMs - sMs) * 100 / (eMs - sMs));
                progress = Math.max(0, Math.min(100, progress));
                progressProgram.setProgress(progress);
                long playedMin = (nowMs - sMs) / 1000 / 60;
                if(tvRemainingTime != null){
                    if(playedMin >=60){
                        int h = (int) (playedMin /60);
                        int m = (int) (playedMin %60);
                        tvRemainingTime.setText("已播放"+h+"时"+m+"分");
                    }else {
                        tvRemainingTime.setText("已播放"+playedMin+"分钟");
                    }
                }
            }
        }else {
            tvCurrentProgramName.setText("暂无节目信息");
            if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
            if(progressProgram != null) progressProgram.setProgress(0);
            if(tvRemainingTime != null) tvRemainingTime.setText("");
        }
    }

    /** 刷新下一档节目UI */
    private void refreshNextProgramUi(Channel.EpgItem nextItem, int currIdx, List<Channel.EpgItem> todayList){
        if(nextItem != null && tvNextProgramName != null){
            String s = nextItem.time;
            String e = (currIdx +2 < todayList.size()) ? todayList.get(currIdx+2).time : "23:59";
            tvNextProgramName.setText(s + " - " + e + "  " + nextItem.title);
            if(tvNextTimeRange != null) tvNextTimeRange.setText("");
        }else {
            if(tvNextProgramName != null) tvNextProgramName.setText("暂无下一档节目");
            if(tvNextTimeRange != null) tvNextTimeRange.setText("");
        }
    }

    /** EPG无数据时清空所有节目UI */
    private void setEpgEmptyUi(){
        if(tvCurrentProgramName != null) tvCurrentProgramName.setText("暂无节目信息");
        if(tvCurrentTimeRange != null) tvCurrentTimeRange.setText("");
        if(tvNextProgramName != null) tvNextProgramName.setText("");
        if(tvNextTimeRange != null) tvNextTimeRange.setText("");
        if(progressProgram != null) progressProgram.setProgress(0);
        if(tvRemainingTime != null) tvRemainingTime.setText("");
    }

    // ===================== 定时进度启停 =====================
    /** 启动每分钟EPG进度循环刷新 */
    public void startProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
        mainHandler.postDelayed(refreshProgressTask, PROGRAM_PROGRESS_INTERVAL);
    }

    /** 停止进度定时刷新（切台、退出播放调用） */
    public void stopProgressLoop(){
        mainHandler.removeCallbacks(refreshProgressTask);
    }

    // ===================== 时间工具私有方法 =====================
    /** 获取HH:mm格式当前时间 */
    private String getCurrentTimeStr(){
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        return String.format("%02d:%02d", h, m);
    }

    /** 判断now是否在[start, end)时段内 */
    private boolean timeBetween(String now, String start, String end){
        try {
            if(now == null || start == null || end == null) return false;
            if(!now.contains(":") || !start.contains(":") || !end.contains(":")) return false;
            return now.compareTo(start) >=0 && now.compareTo(end) <0;
        }catch (Exception e){
            return false;
        }
    }

    /** HH:mm 时间字符串转为当天毫秒时间戳 */
    private long timeToMs(String timeStr){
        try {
            String[] split = timeStr.split(":");
            int h = Integer.parseInt(split[0].trim());
            int m = Integer.parseInt(split[1].trim());
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        }catch (Exception e){
            return 0;
        }
    }

    // ===================== 资源释放（页面销毁调用，防内存泄漏） =====================
    /** 释放所有Handler任务、清空控件引用 */
    public void release(){
        // 移除全部延时任务
        mainHandler.removeCallbacks(hideInfoBarTask);
        mainHandler.removeCallbacks(hideChannelNumTask);
        mainHandler.removeCallbacks(refreshProgressTask);
        // 清空频道缓存
        currentPlayChannel = null;
        // 全部控件置空，切断引用链
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
