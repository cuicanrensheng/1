package com.tv.live.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.tv.live.Channel;
import com.tv.live.EpgManager;
import com.tv.live.MainActivity;
import com.tv.live.R;
import com.tv.live.SettingsActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * EPG 节目单包装管理器
 * 修复1：时间字符串带空格"00 - 03"数字转换崩溃报错
 * 修复2：切新频道不自动滚动到下一档，切回才正常
 * 修复3：全链路强制时间清洗，双层容错解析时分
 */
public class EpgManagerWrapper {
    private static final String TAG = "EPG_DEBUG";
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private int selectedPosition = 0;
    private int playingIndex = -1;
    private int nextPlayIndex = -1;
    private int selectDayIndex = 0;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                printLog(TAG, "手动选中 position=" + pos);
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedPosition = -1;
                printLog(TAG, "取消选中");
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        });
        lvEpg.setOnFocusChangeListener((v, hasFocus) -> {
            printLog(TAG, "ListView焦点变更 hasFocus=" + hasFocus);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        registerReminderReceiver();
    }

    /** 统一日志打印 Info级别 Release不丢失 */
    private void printLog(String tag, String msg) {
        SettingsActivity.log("[" + tag + "] " + msg);
        Log.i(tag, msg);
    }

    /** 异常日志 */
    private void printErrLog(String tag, String msg, Throwable e) {
        SettingsActivity.log("[" + tag + "] ERROR:" + msg);
        Log.e(tag, msg, e);
    }

    /**
     * 全局时间清洗：清除全部空格、只保留数字和冒号，兼容破碎时段字符串
     */
    private String cleanTimeStr(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        // 清除所有空白字符
        String str = raw.replaceAll("\\s+", "");
        // 只保留数字、冒号，删除横杠/其他符号
        str = str.replaceAll("[^0-9:]", "");
        // 截断为标准HH:mm长度
        if (str.length() > 5) {
            str = str.substring(0, 5);
        }
        if (!str.contains(":")) {
            return "";
        }
        return str;
    }

    /** 遥控器跳转下一档 */
    public void jumpNextProgram() {
        printLog(TAG, "外部跳转下一档，缓存下标=" + nextPlayIndex);
        if (adapter == null || adapter.getCount() == 0 || nextPlayIndex == -1) {
            Toast.makeText(context, "暂无下一档节目", Toast.LENGTH_SHORT).show();
            return;
        }
        lvEpg.postDelayed(() -> {
            selectedPosition = nextPlayIndex;
            lvEpg.setSelection(nextPlayIndex);
            adapter.notifyDataSetChanged();
        }, 150);
    }

    /** 切换频道刷新入口 */
    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) {
            printErrLog(TAG, "刷新频道为空", null);
            return;
        }
        printLog(TAG, "=====切换频道刷新 频道名=" + currentChannel.getName() + " dateIndex=" + dateIndex);
        playingIndex = -1;
        nextPlayIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();
        selectedPosition = 0;

        new Thread(() -> {
            List<Channel.EpgItem> rawList;
            try {
                List<Channel.EpgItem> temp = EpgManager.getInstance().getEpg(currentChannel.getName());
                rawList = temp == null ? new ArrayList<>() : new ArrayList<>(temp);
            } catch (Exception e) {
                printErrLog(TAG, "拉取EPG数据失败", e);
                rawList = new ArrayList<>();
            }
            printLog(TAG, "原始节目总数：" + rawList.size());
            List<Channel.EpgItem> data = new ArrayList<>();

            if (!rawList.isEmpty()) {
                String targetDay;
                String targetWeekDay = null;
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, dateIndex);
                int w = cal.get(Calendar.DAY_OF_WEEK);
                String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                String weekDay = weekMap[w - 1];
                if (dateIndex == 0) {
                    targetDay = "今天"; targetWeekDay = weekDay;
                } else if (dateIndex == 1) {
                    targetDay = "明天"; targetWeekDay = weekDay;
                } else if (dateIndex == 2) {
                    targetDay = "后天"; targetWeekDay = weekDay;
                } else {
                    targetDay = weekDay;
                }

                // 筛选节目
                int matchCount = 0;
                for (Channel.EpgItem item : rawList) {
                    if (TextUtils.isEmpty(item.dayName)) continue;
                    String dayName = item.dayName.trim();
                    boolean match = targetDay.equals(dayName);
                    if (!match && targetWeekDay != null) match = targetWeekDay.equals(dayName);
                    if (match) {
                        data.add(item);
                        matchCount++;
                    }
                }
                printLog(TAG, "筛选后节目：" + matchCount);
                Collections.sort(data, Comparator.comparing(o -> cleanTimeStr(o.time)));

                String now = getNow();
                Channel.EpgItem playing = null;
                for (int i = 0; i < data.size(); i++) {
                    Channel.EpgItem curr = data.get(i);
                    curr.time = cleanTimeStr(curr.time);
                    String endStr;
                    if (i + 1 < data.size()) {
                        // 下一条时间双重清洗兜底
                        String nextRaw = data.get(i + 1).time;
                        String nextClean = cleanTimeStr(nextRaw);
                        endStr = nextClean;
                    } else {
                        endStr = addOneHour(curr.time);
                    }
                    epgEndTimeMap.put(curr, endStr);
                    curr.isPlaying = false;
                    if (isTimeBetween(now, curr.time, endStr)) {
                        curr.isPlaying = true;
                        playing = curr;
                        playingIndex = i;
                    }
                    printLog(TAG, "[" + i + "] " + curr.title + " " + curr.time + "~" + endStr + " 播放=" + curr.isPlaying);
                }

                // 计算下一档下标
                if (dateIndex == 0) {
                    if (playingIndex != -1) {
                        nextPlayIndex = playingIndex + 1;
                        if (nextPlayIndex >= data.size()) nextPlayIndex = -1;
                        printLog(TAG, "原始下一档下标：" + nextPlayIndex);
                    } else {
                        nextPlayIndex = -1;
                        for (int i = 0; i < data.size(); i++) {
                            String t = cleanTimeStr(data.get(i).time);
                            if (t.compareTo(now) > 0) {
                                nextPlayIndex = i;
                                printLog(TAG, "首个未播下标：" + i);
                                break;
                            }
                        }
                    }
                } else {
                    nextPlayIndex = -1;
                    printLog(TAG, "非今日无下一档");
                }

                // 播放节目置顶，修正下标
                if (playing != null && playingIndex > 0) {
                    data.remove(playing);
                    data.add(0, playing);
                    playingIndex = 0;
                    if (nextPlayIndex != -1) nextPlayIndex += 1;
                    printLog(TAG, "置顶修正后下一档：" + nextPlayIndex);
                }
            }

            // 主线程更新数据，不复建Adapter
            final List<Channel.EpgItem> finalData = new ArrayList<>(data);
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                // 只在第一次创建适配器，切台复用旧实例
                if (adapter == null) {
                    printLog(TAG, "首次创建适配器");
                    adapter = new EpgAdapter(context, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    printLog(TAG, "切换频道，复用适配器，仅更新数据");
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }
                adapter.notifyDataSetChanged();
                int targetScrollPos = nextPlayIndex != -1 ? nextPlayIndex : playingIndex;
                // 边界校验，防止下标越界
                if (targetScrollPos < 0 || targetScrollPos >= finalData.size()) {
                    targetScrollPos = 0;
                }
                printLog(TAG, "目标滚动下标：" + targetScrollPos + " 列表总条数：" + finalData.size());
                // 先重置到顶部，再延迟滚动到下一档
                lvEpg.scrollTo(0, 0);
                // 延时150ms等待布局完整渲染
                lvEpg.postDelayed(() -> {
                    selectedPosition = targetScrollPos;
                    lvEpg.setSelection(targetScrollPos);
                    printLog(TAG, "延迟滚动执行完成 pos=" + targetScrollPos);
                }, 150);
            });
        }).start();
    }

    private boolean isTimeBetween(String now, String start, String end) {
        try {
            String sNow = cleanTimeStr(now);
            String sStart = cleanTimeStr(start);
            String sEnd = cleanTimeStr(end);
            if (TextUtils.isEmpty(sNow) || TextUtils.isEmpty(sStart) || TextUtils.isEmpty(sEnd))
                return false;
            return sNow.contains(":") && sStart.contains(":") && sEnd.contains(":")
                    && sNow.compareTo(sStart) >= 0 && sNow.compareTo(sEnd) < 0;
        } catch (Exception e) {
            printErrLog(TAG, "时间区间对比异常", e);
            return false;
        }
    }

    private String addOneHour(String hm) {
        try {
            String cleanHm = cleanTimeStr(hm);
            if (!cleanHm.contains(":")) return "23:59";
            String[] arr = cleanHm.split(":");
            int h, m;
            // 单独捕获小时解析错误
            try {
                h = Integer.parseInt(arr[0]);
            } catch (Exception e) {
                printErrLog(TAG, "小时数字解析失败 raw=" + hm + " clean=" + cleanHm, e);
                return "23:59";
            }
            // 单独捕获分钟解析错误
            try {
                m = Integer.parseInt(arr[1]);
            } catch (Exception e) {
                printErrLog(TAG, "分钟数字解析失败 raw=" + hm + " clean=" + cleanHm, e);
                return "23:59";
            }
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.add(Calendar.MINUTE, 60);
            return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        } catch (Exception e) {
            printErrLog(TAG, "时间加1小时整体解析失败", e);
            return "23:59";
        }
    }

    private String getNow() {
        return String.format("%02d:%02d",
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE));
    }

    private void registerReminderReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REMINDER.equals(intent.getAction())) {
                    String title = intent.getStringExtra("title");
                    printLog(TAG, "节目提醒：" + title);
                    Toast.makeText(context, "节目提醒：" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_REMINDER));
    }

    // 适配器
    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final Context ctx;
        private Channel currentChannel;
        private List<Channel.EpgItem> list;
        private final LayoutInflater inflater;
        private int dayIndex;
        private final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);

        public EpgAdapter(Context ctx, List<Channel.EpgItem> list, int dayIndex) {
            super(ctx, R.layout.item_epg, list);
            this.ctx = ctx;
            this.list = new ArrayList<>(list);
            this.inflater = LayoutInflater.from(ctx);
            this.dayIndex = dayIndex;
        }

        public void setData(Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            this.currentChannel = currentChannel;
            this.list.clear();
            this.list.addAll(new ArrayList<>(list));
            this.dayIndex = dayIndex;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            boolean newView = convertView == null;
            if (newView) {
                convertView = inflater.inflate(R.layout.item_epg, parent, false);
                holder = new ViewHolder();
                holder.tv_dayName = convertView.findViewById(R.id.tv_dayName);
                holder.tv_time = convertView.findViewById(R.id.tv_time);
                holder.tv_title = convertView.findViewById(R.id.tv_title);
                holder.tv_action = convertView.findViewById(R.id.tv_action);
                convertView.setTag(holder);
                printLog(TAG, "创建ItemView pos=" + position);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            // 清空复用残留
            holder.tv_dayName.setText("");
            holder.tv_time.setText("");
            holder.tv_title.setText("");
            holder.tv_action.setText("");
            holder.tv_action.setOnClickListener(null);
            holder.tv_dayName.setTextColor(Color.WHITE);
            holder.tv_time.setTextColor(Color.LTGRAY);
            holder.tv_title.setTextColor(Color.WHITE);
            holder.tv_title.setTypeface(null, Typeface.NORMAL);
            convertView.setBackgroundColor(Color.TRANSPARENT);

            if (position < 0 || position >= list.size()) return convertView;
            Channel.EpgItem item = list.get(position);
            String endTime = epgEndTimeMap.get(item);
            String dayText = TextUtils.isEmpty(item.dayName) ? "" : item.dayName;
            String titleText = TextUtils.isEmpty(item.title) ? "" : item.title;
            // 展示时段也清洗，避免界面显示破碎时间
            String startClean = cleanTimeStr(item.time);
            String endClean = cleanTimeStr(endTime);
            String timeText = startClean + "-" + (TextUtils.isEmpty(endClean) ? "23:59" : endClean);

            holder.tv_dayName.setText(dayText);
            holder.tv_time.setText(timeText);
            holder.tv_title.setText(titleText);

            boolean isFocused = (position == selectedPosition) && lvEpg.hasFocus();
            boolean isPlaying = item.isPlaying && dayIndex == 0;
            if (isFocused) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
            } else if (isPlaying) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            try {
                String t = cleanTimeStr(item.time);
                isPast = t.compareTo(getNow()) < 0;
            } catch (Exception ignored) {}
            if (item.isPlaying) {
                holder.tv_action.setText("播放中");
                holder.tv_action.setBackgroundColor(0xFFFF9800);
                holder.tv_action.setEnabled(false);
            } else if (isPast) {
                holder.tv_action.setText("回看");
                holder.tv_action.setBackgroundColor(0xFF607D8B);
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(v -> {
                    printLog(TAG, "点击回看：" + item.title);
                    try {
                        String liveUrl = currentChannel.getPlayUrl();
                        if (TextUtils.isEmpty(liveUrl)) {
                            Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Calendar playDay = Calendar.getInstance();
                        playDay.add(Calendar.DAY_OF_YEAR, dayIndex);
                        // 回看起止时间强制清洗
                        String startRaw = item.time;
                        String startClean = cleanTimeStr(startRaw);
                        String[] startHm = startClean.split(":");
                        int h = Integer.parseInt(startHm[0].trim());
                        int m = Integer.parseInt(startHm[1].trim());
                        Calendar startCal = Calendar.getInstance();
                        startCal.set(Calendar.HOUR_OF_DAY, h);
                        startCal.set(Calendar.MINUTE, m);
                        startCal.set(Calendar.SECOND, 0);

                        String endRaw = endTime;
                        String endClean = cleanTimeStr(endRaw);
                        String[] endHm = endClean.split(":");
                        int eh = Integer.parseInt(endHm[0].trim());
                        int em = Integer.parseInt(endHm[1].trim());
                        Calendar endCal = Calendar.getInstance();
                        endCal.set(Calendar.HOUR_OF_DAY, eh);
                        endCal.set(Calendar.MINUTE, em);
                        endCal.set(Calendar.SECOND, 0);

                        String sStart = sdfFull.format(startCal.getTime());
                        String sEnd = sdfFull.format(endCal.getTime());
                        String url = liveUrl.contains("PLTV") ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                        url += url.contains("?") ? "&playseek=" + sStart + "-" + sEnd : "?playseek=" + sStart;
                        ((MainActivity) ctx).mPlayerManager.playUrl(url);
                        Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        printErrLog(TAG, "回看播放失败", e);
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                String btnTxt = bookedSet.contains(key) ? "已预约" : "预约";
                holder.tv_action.setText(btnTxt);
                holder.tv_action.setBackgroundColor(bookedSet.contains(key) ? 0xFF607D8B : 0xFF4CAF50);
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(v -> {
                    if (bookedSet.contains(key)) bookedSet.remove(key);
                    else bookedSet.add(key);
                    notifyDataSetChanged();
                });
            }
            return convertView;
        }

        static class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }
    }
}
