package com.tv.live.widget;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
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
 * 样式更新规则
 * 1、焦点选中条目（最高优先级）：蓝色字体+加粗+浅蓝色半透明背景
 * 2、播放中条目（今日，蓝色文字、不加粗、透明无背景）
 * 3、普通条目：白色常规文字、透明背景
 * 4、非今日完全不渲染播放中蓝色样式
 * 
 * 【2026-06-27 修改：取消播放中节目置顶】
 * 【修改说明】
 * 原来播放中的节目会被移到列表第一位（置顶），
 * 现在取消置顶功能，节目严格按时间顺序排列，
 * 播放中的节目只通过蓝色文字样式标识，不改变位置。
 */
public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private int selectedPosition = 0; // 焦点选中位置
    private int playingIndex = -1;    // 播放中条目位置
    private int selectDayIndex = 0;
    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        lvEpg.setItemsCanFocus(true);
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedPosition = -1;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        });
        lvEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
        });
        registerReminderReceiver();
    }
    /**
     * 刷新指定日期节目单
     */
    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) {
            SettingsActivity.log("【EPG包装】❌ refresh被调用，但currentChannel为空");
            return;
        }
        SettingsActivity.log("【EPG包装】🔄 开始刷新，频道：" + currentChannel.getName() + "，日期索引：" + dateIndex);
        playingIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();
        new Thread(() -> {
            List<Channel.EpgItem> originEpgList;
            try {
                List<Channel.EpgItem> temp = EpgManager.getInstance().getEpg(currentChannel.getName());
                originEpgList = temp == null ? new ArrayList<>() : new ArrayList<>(temp);
            } catch (Exception e) {
                SettingsActivity.log("【EPG包装】获取EPG异常：" + e.getMessage());
                originEpgList = new ArrayList<>();
            }
            SettingsActivity.log("【EPG包装】📋 原始节目数：" + originEpgList.size());
            List<Channel.EpgItem> data = new ArrayList<>();
            if (!originEpgList.isEmpty()) {
                String targetDay;
                String targetWeekDay = null;
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, dateIndex);
                int w = cal.get(Calendar.DAY_OF_WEEK);
                String[] weekMap = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                String weekDay = weekMap[w - 1];
                if (dateIndex == 0) {
                    targetDay = "今天";
                    targetWeekDay = weekDay;
                } else if (dateIndex == 1) {
                    targetDay = "明天";
                    targetWeekDay = weekDay;
                } else if (dateIndex == 2) {
                    targetDay = "后天";
                    targetWeekDay = weekDay;
                } else {
                    targetDay = weekDay;
                }
                SettingsActivity.log("【EPG包装】🎯 目标日期：" + targetDay + "，对应周几：" + weekDay);
                int matchCount = 0;
                for (Channel.EpgItem item : originEpgList) {
                    if (item.dayName == null) continue;
                    String dayName = item.dayName.trim();
                    boolean match = targetDay.equals(dayName);
                    if (!match && targetWeekDay != null) match = targetWeekDay.equals(dayName);
                    if (match) {
                        data.add(item);
                        matchCount++;
                    }
                }
                SettingsActivity.log("【EPG包装】✅ 筛选后节目数：" + matchCount);
                Collections.sort(data, Comparator.comparing(o -> o.time));
                // 区分今日/非今日
                if (dateIndex == 0) {
                    String now = getNow();
                    Channel.EpgItem playing = null;
                    for (int i = 0; i < data.size(); i++) {
                        Channel.EpgItem curr = data.get(i);
                        if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                            curr.time = curr.time.split("-")[0].trim();
                        if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                            if (i + 1 < data.size())
                                epgEndTimeMap.put(curr, data.get(i + 1).time.split("-")[0].trim());
                            else
                                epgEndTimeMap.put(curr, addOneHour(curr.time));
                        }
                        curr.isPlaying = false;
                        String currEnd = epgEndTimeMap.get(curr);
                        if (isTimeBetween(now, curr.time, currEnd)) {
                            curr.isPlaying = true;
                            playing = curr;
                            playingIndex = i;
                        }
                    }
                    // ====================================================================
                    // ✅ 2026-06-27 修改：取消播放中节目置顶
                    // ====================================================================
                    // 【原逻辑】
                    // 播放中的节目会被移到列表第一位（置顶），方便用户快速找到当前播放的节目。
                    // 
                    // 【为什么取消？】
                    // 用户要求节目严格按时间顺序排列，不希望播放中的节目改变位置。
                    // 播放状态只通过蓝色文字样式标识即可，不需要置顶。
                    // 
                    // 【原代码（已注释）】
                    // if (playing != null && playingIndex > 0) {
                    //     data.remove(playing);
                    //     data.add(0, playing);
                    //     playingIndex = 0;
                    // }
                    // ====================================================================
                    SettingsActivity.log("【EPG包装】✅ 播放中节目位置：第 " + (playingIndex + 1) + " 个（按时间顺序，不置顶）");
                } else {
                    playingIndex = -1;
                    for (int i = 0; i < data.size(); i++) {
                        Channel.EpgItem curr = data.get(i);
                        if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                            curr.time = curr.time.split("-")[0].trim();
                        if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                            if (i + 1 < data.size())
                                epgEndTimeMap.put(curr, data.get(i + 1).time.split("-")[0].trim());
                            else
                                epgEndTimeMap.put(curr, addOneHour(curr.time));
                        }
                        curr.isPlaying = false;
                    }
                }
            }
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                SettingsActivity.logOperation("【EPG包装】📱 主线程更新UI，节目数：" + finalData.size());
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }
                if (selectedPosition >= finalData.size()) {
                    selectedPosition = Math.max(0, finalData.size() - 1);
                }
                lvEpg.setSelection(selectedPosition);
                adapter.notifyDataSetChanged();
                SettingsActivity.logOperation("【EPG包装】✅ UI更新完成");
            });
        }).start();
    }
    private boolean isTimeBetween(String now, String start, String end) {
        try {
            if (now == null || start == null || end == null) return false;
            return now.contains(":") && start.contains(":") && end.contains(":")
                    && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }
    private String addOneHour(String hm) {
        try {
            if (hm == null || !hm.contains(":")) return "23:59";
            hm = hm.trim();
            if (hm.contains("-")) hm = hm.split("-")[0].trim();
            String[] arr = hm.split(":");
            int h = Integer.parseInt(arr[0].trim());
            int m = Integer.parseInt(arr[1].trim());
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.add(Calendar.MINUTE, 60);
            return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        } catch (Exception e) {
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
                    Toast.makeText(context, "节目提醒：" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_REMINDER));
    }
    // EPG适配器
    private class EpgAdapter extends ArrayAdapter<Channel.EpgItem> {
        private final Context ctx;
        private Channel currentChannel;
        private List<Channel.EpgItem> list;
        private final LayoutInflater inflater;
        private int dayIndex;
        private final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyyMMddHHmmss", Locale.CHINA);
        public EpgAdapter(Context ctx, Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            super(ctx, R.layout.item_epg, list);
            this.ctx = ctx;
            this.currentChannel = currentChannel;
            this.list = list;
            this.inflater = LayoutInflater.from(ctx);
            this.dayIndex = dayIndex;
        }
        public void setData(Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            this.currentChannel = currentChannel;
            this.list.clear();
            this.list.addAll(list);
            this.dayIndex = dayIndex;
            notifyDataSetChanged();
        }
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_epg, parent, false);
                holder = new ViewHolder();
                holder.tv_dayName = convertView.findViewById(R.id.tv_dayName);
                holder.tv_time = convertView.findViewById(R.id.tv_time);
                holder.tv_title = convertView.findViewById(R.id.tv_title);
                holder.tv_action = convertView.findViewById(R.id.tv_action);
                // 修复变量名错误 convertView
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            // 边界保护
            if (position < 0 || position >= list.size()) {
                return convertView;
            }
            Channel.EpgItem item = list.get(position);
            String endTime = epgEndTimeMap.get(item);
            holder.tv_dayName.setText(item.dayName);
            holder.tv_time.setText(item.time + "-" + endTime);
            holder.tv_title.setText(item.title);
            // 1、先重置所有样式（彻底清空缓存）
            holder.tv_dayName.setTextColor(Color.WHITE);
            holder.tv_time.setTextColor(Color.LTGRAY);
            holder.tv_title.setTextColor(Color.WHITE);
            holder.tv_title.setTypeface(null, Typeface.NORMAL);
            convertView.setBackgroundColor(Color.TRANSPARENT);
            convertView.setSelected(false);
            // 2、判断当前item是否是焦点选中
            boolean isFocused = (position == selectedPosition) && lvEpg.hasFocus();
            // 3、判断当前item是否是播放中
            boolean isPlaying = item.isPlaying && dayIndex == 0;
            // 规则1：焦点选中条目（浅蓝色背景 + 蓝色文字 + 加粗）
            if (isFocused) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.BOLD);
                convertView.setBackgroundColor(0x3340A9FF);
            }
            // 规则2：无焦点播放中：蓝色文字、不加粗、透明背景
            else if (isPlaying) {
                holder.tv_dayName.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_time.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTextColor(Color.parseColor("#40A9FF"));
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }
            // ========== 按钮逻辑 ==========
            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            try { isPast = item.time.compareTo(getNow()) < 0; } catch (Exception ignored) {}
            if (dayIndex == 0) {
                if (item.isPlaying) {
                    holder.tv_action.setText("播放中");
                    holder.tv_action.setBackgroundColor(0xFFFF9800);
                    holder.tv_action.setEnabled(false);
                    holder.tv_action.setOnClickListener(null);
                } else if (isPast) {
                    holder.tv_action.setText("回看");
                    holder.tv_action.setBackgroundColor(0xFF607D8B);
                    holder.tv_action.setEnabled(true);
                    holder.tv_action.setOnClickListener(v -> {
                        try {
                            String liveUrl = currentChannel.getPlayUrl();
                            if (TextUtils.isEmpty(liveUrl)) {
                                Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            Calendar playDay = Calendar.getInstance();
                            playDay.add(Calendar.DAY_OF_YEAR, dayIndex);
                            String[] startHm = item.time.split(":");
                            Calendar startCal = (Calendar) playDay.clone();
                            startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(startHm[0].trim()));
                            startCal.set(Calendar.MINUTE, Integer.parseInt(startHm[1].trim()));
                            startCal.set(Calendar.SECOND, 0);
                            String[] endHm = endTime.split(":");
                            Calendar endCal = (Calendar) playDay.clone();
                            endCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(endHm[0].trim()));
                            endCal.set(Calendar.MINUTE, Integer.parseInt(endHm[1].trim()));
                            endCal.set(Calendar.SECOND, 0);
                            String startStr = sdfFull.format(startCal.getTime());
                            String endStr = sdfFull.format(endCal.getTime());
                            String catchUrl = liveUrl.contains("PLTV") ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                            catchUrl += catchUrl.contains("?") ? "&playseek=" + startStr + "-" + endStr : "?playseek=" + startStr + "-" + endStr;
                            ((MainActivity) ctx).mPlayerManager.playUrl(catchUrl);
                            Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    holder.tv_action.setText("预约");
                    holder.tv_action.setBackgroundColor(0xFF4CAF50);
                    holder.tv_action.setEnabled(true);
                    holder.tv_action.setOnClickListener(v -> {
                        if (bookedSet.contains(key)) {
                            bookedSet.remove(key);
                            Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
                        } else {
                            bookedSet.add(key);
                            Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                        }
                        notifyDataSetChanged();
                    });
                }
            } else {
                holder.tv_action.setText("预约");
                holder.tv_action.setBackgroundColor(0xFF4CAF50);
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(v -> {
                    if (bookedSet.contains(key)) {
                        bookedSet.remove(key);
                        Toast.makeText(ctx, "已取消预约", Toast.LENGTH_SHORT).show();
                    } else {
                        bookedSet.add(key);
                        Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT);
                    }
                    notifyDataSetChanged();
                });
            }
            return convertView;
        }
        // 修复1：去掉 static 关键字，内部适配器不允许静态ViewHolder
        private class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }
    }
}
