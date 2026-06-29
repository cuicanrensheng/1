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
 * 2、播放中条目（仅今日首位，无焦点）：蓝色字体、不加粗、透明无背景
 * 3、即将播放条目（仅今日）：橙色字体、不加粗、透明背景
 * 4、普通条目：白色常规文字、透明背景
 * 5、非今日完全不渲染播放中/即将播放样式
 */
public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    // 新增：下一档节目映射
    private final Map<Channel.EpgItem, Channel.EpgItem> nextEpgItemMap = new HashMap<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private int selectedPosition = 0; // 焦点选中位置
    private int playingIndex = -1;    // 播放中条目位置
    private int nextPlayIndex = -1;   // 新增：即将播放条目位置
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
        nextPlayIndex = -1; // 重置即将播放索引
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();
        nextEpgItemMap.clear(); // 清空下一档节目映射
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
                
                // 提前构建下一档节目映射（核心优化）
                buildNextEpgItemMap(data);
                
                // 区分今日/非今日
                if (dateIndex == 0) {
                    String now = getNow();
                    Channel.EpgItem playing = null;
                    Channel.EpgItem nextPlay = null;
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
                        curr.isNextPlay = false; // 新增：重置即将播放标记
                        String currEnd = epgEndTimeMap.get(curr);
                        
                        // 标记播放中
                        if (isTimeBetween(now, curr.time, currEnd)) {
                            curr.isPlaying = true;
                            playing = curr;
                            playingIndex = i;
                            // 立即定位下一档
                            if (i + 1 < data.size()) {
                                nextPlay = data.get(i + 1);
                                nextPlay.isNextPlay = true;
                                nextPlayIndex = i + 1;
                            }
                        }
                        // 标记未开始的下一档（播放中未找到时）
                        else if (playing == null && curr.time.compareTo(now) > 0 && nextPlay == null) {
                            nextPlay = curr;
                            nextPlay.isNextPlay = true;
                            nextPlayIndex = i;
                        }
                    }
                    // 播放节目置顶
                    if (playing != null && playingIndex > 0) {
                        data.remove(playing);
                        data.add(0, playing);
                        playingIndex = 0;
                        // 同步更新下一档索引
                        if (nextPlayIndex > playingIndex) {
                            nextPlayIndex -= 1;
                        } else if (nextPlayIndex == playingIndex) {
                            nextPlayIndex = 1;
                            if (data.size() > 1) {
                                data.get(1).isNextPlay = true;
                            }
                        }
                    }
                    // 无播放中时，下一档置顶（快速显示）
                    else if (playing == null && nextPlay != null && nextPlayIndex > 0) {
                        data.remove(nextPlay);
                        data.add(0, nextPlay);
                        nextPlayIndex = 0;
                    }
                } else {
                    playingIndex = -1;
                    nextPlayIndex = -1;
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
                        curr.isNextPlay = false;
                    }
                }
            }
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                SettingsActivity.log("【EPG包装】📱 主线程更新UI，节目数：" + finalData.size() + 
                                    " 播放中索引：" + playingIndex + " 即将播放索引：" + nextPlayIndex);
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }
                // 优先定位到即将播放/播放中条目（快速显示）
                int targetPos = nextPlayIndex != -1 ? nextPlayIndex : (playingIndex != -1 ? playingIndex : 0);
                if (targetPos >= finalData.size()) {
                    targetPos = Math.max(0, finalData.size() - 1);
                }
                selectedPosition = targetPos;
                lvEpg.setSelection(targetPos);
                adapter.notifyDataSetChanged();
                SettingsActivity.log("【EPG包装】✅ UI更新完成，定位到：" + targetPos);
            });
        }).start();
    }

    /**
     * 新增：构建下一档节目映射
     */
    private void buildNextEpgItemMap(List<Channel.EpgItem> data) {
        if (data == null || data.size() < 2) return;
        for (int i = 0; i < data.size() - 1; i++) {
            Channel.EpgItem curr = data.get(i);
            Channel.EpgItem next = data.get(i + 1);
            nextEpgItemMap.put(curr, next);
        }
        // 最后一个节目映射到自身
        Channel.EpgItem last = data.get(data.size() - 1);
        nextEpgItemMap.put(last, last);
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
                // 新增：即将播放标签
                holder.tv_nextTip = convertView.findViewById(R.id.tv_nextTip);
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
            
            // 新增：显示下一档提示
            holder.tv_nextTip.setVisibility(View.GONE);
            if (item.isNextPlay) {
                holder.tv_nextTip.setVisibility(View.VISIBLE);
                holder.tv_nextTip.setText("即将播放");
                holder.tv_nextTip.setTextColor(Color.parseColor("#FF9800"));
            }

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
            // 4、判断当前item是否是即将播放
            boolean isNextPlay = item.isNextPlay && dayIndex == 0;

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
            // 新增规则3：即将播放条目：橙色文字、不加粗、透明背景
            else if (isNextPlay) {
                holder.tv_dayName.setTextColor(Color.parseColor("#FF9800"));
                holder.tv_time.setTextColor(Color.parseColor("#FF9800"));
                holder.tv_title.setTextColor(Color.parseColor("#FF9800"));
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
                } else if (item.isNextPlay) {
                    // 新增：即将播放按钮样式
                    holder.tv_action.setText("即将播放");
                    holder.tv_action.setBackgroundColor(0xFFE67C73);
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
                        Toast.makeText(ctx, "已预约：" + item.title, Toast.LENGTH_SHORT).show();
                    }
                    notifyDataSetChanged();
                });
            }

            return convertView;
        }

        // 新增：即将播放标签
        private class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
            TextView tv_nextTip;
        }
    }

    // 新增：快速定位下一档节目
    public void jumpToNextPlay() {
        if (nextPlayIndex != -1 && adapter != null && nextPlayIndex < adapter.getCount()) {
            selectedPosition = nextPlayIndex;
            lvEpg.setSelection(nextPlayIndex);
            adapter.notifyDataSetChanged();
            Toast.makeText(context, "已定位到下一档节目", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, "暂无下一档节目", Toast.LENGTH_SHORT).show();
        }
    }

    // 新增：给Channel.EpgItem扩展isNextPlay字段（如果原类不可修改，可改用Map存储）
    static {
        try {
            // 兼容处理：如果原EpgItem没有isNextPlay字段，通过反射动态添加（可选）
            Class<?> epgItemClass = Class.forName("com.tv.live.Channel$EpgItem");
            if (!hasField(epgItemClass, "isNextPlay")) {
                // 实际项目中建议直接修改Channel.EpgItem类添加：public boolean isNextPlay;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean hasField(Class<?> clazz, String fieldName) {
        try {
            clazz.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
