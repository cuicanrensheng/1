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
 * EPG（电子节目指南）管理器包装类
 * 新增：自动识别、快速定位下一档节目
 * 修复：条目空白、编译数组trim错误、参数不匹配
 */
public class EpgManagerWrapper {
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private int selectedPosition = 0;
    private int playingIndex = -1;
    // 缓存下一档节目索引
    private int nextPlayIndex = -1;
    private int selectDayIndex = 0;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        // 移除过时setItemsCanFocus 消除警告
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

        lvEpg.setOnFocusChangeListener((v, hasFocus) -> {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
        registerReminderReceiver();
    }

    /** 遥控器手动跳转到下一档 */
    public void jumpNextProgram() {
        if (adapter == null || adapter.getCount() == 0 || nextPlayIndex == -1) {
            Toast.makeText(context, "暂无下一档节目", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedPosition = nextPlayIndex;
        lvEpg.setSelection(nextPlayIndex);
        adapter.notifyDataSetChanged();
    }

    /** 刷新节目单 */
    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) {
            SettingsActivity.log("【EPG包装】❌ refresh被调用，但currentChannel为空");
            return;
        }
        SettingsActivity.log("【EPG包装】🔄 开始刷新，频道：" + currentChannel.getName() + "，日期索引：" + dateIndex);
        playingIndex = -1;
        nextPlayIndex = -1;
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
                    if (TextUtils.isEmpty(item.dayName)) continue;
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

                String now = getNow();
                Channel.EpgItem playing = null;
                for (int i = 0; i < data.size(); i++) {
                    Channel.EpgItem curr = data.get(i);
                    if (!TextUtils.isEmpty(curr.time) && curr.time.contains("-"))
                        curr.time = curr.time.split("-")[0].trim();

                    if (TextUtils.isEmpty(epgEndTimeMap.get(curr))) {
                        String endStr;
                        if (i + 1 < data.size()) {
                            Channel.EpgItem next = data.get(i + 1);
                            String nextRaw = next.time;
                            endStr = nextRaw.contains("-") ? nextRaw.split("-")[0].trim() : nextRaw;
                        } else {
                            endStr = addOneHour(curr.time);
                        }
                        epgEndTimeMap.put(curr, endStr);
                    }

                    curr.isPlaying = false;
                    String currEnd = epgEndTimeMap.get(curr);
                    if (isTimeBetween(now, curr.time, currEnd)) {
                        curr.isPlaying = true;
                        playing = curr;
                        playingIndex = i;
                    }
                }

                // 计算下一档索引
                if (dateIndex == 0) {
                    if (playingIndex != -1) {
                        nextPlayIndex = playingIndex + 1;
                        if (nextPlayIndex >= data.size()) nextPlayIndex = -1;
                    } else {
                        nextPlayIndex = -1;
                        for (int i = 0; i < data.size(); i++) {
                            String itemTime = data.get(i).time;
                            if (itemTime.compareTo(now) > 0) {
                                nextPlayIndex = i;
                                break;
                            }
                        }
                    }
                } else {
                    nextPlayIndex = -1;
                }

                // 播放节目置顶
                if (playing != null && playingIndex > 0) {
                    data.remove(playing);
                    data.add(0, playing);
                    playingIndex = 0;
                    if (nextPlayIndex != -1) nextPlayIndex -= 1;
                }
            }

            // 主线程更新UI
            final List<Channel.EpgItem> finalData = data;
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                SettingsActivity.logOperation("【EPG包装】📱 UI刷新，节目数：" + finalData.size()
                        + " 播放下标：" + playingIndex + " 下一档下标：" + nextPlayIndex);
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, finalData, selectDayIndex);
                }

                adapter.notifyDataSetChanged();
                int targetScrollPos;
                if (nextPlayIndex != -1) {
                    targetScrollPos = nextPlayIndex;
                } else if (playingIndex != -1) {
                    targetScrollPos = playingIndex;
                } else {
                    targetScrollPos = 0;
                }
                targetScrollPos = Math.min(targetScrollPos, finalData.size() - 1);
                selectedPosition = targetScrollPos;
                lvEpg.setSelection(targetScrollPos);
                SettingsActivity.logOperation("【EPG包装】✅ UI刷新完成");
            });
        }).start();
    }

    private boolean isTimeBetween(String now, String start, String end) {
        try {
            if (TextUtils.isEmpty(now) || TextUtils.isEmpty(start) || TextUtils.isEmpty(end))
                return false;
            return now.contains(":") && start.contains(":")
                    && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String addOneHour(String hm) {
        try {
            if (TextUtils.isEmpty(hm) || !hm.contains(":")) return "23:59";
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
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            // 清空复用残留，解决滑动空白
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
            String timeText = (TextUtils.isEmpty(item.time) ? "" : item.time) + "-" + (TextUtils.isEmpty(endTime) ? "23:59" : endTime);
            String titleText = TextUtils.isEmpty(item.title) ? "" : item.title;

            holder.tv_day.setText(dayText);
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
                holder.tv_title.setTypeface(null, Typeface.NORMAL);
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            String key = currentChannel.getName() + "_" + position;
            boolean isPast = false;
            try {
                if (!TextUtils.isEmpty(item.time)) isPast = item.time.compareTo(getNow()) < 0;
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
                    try {
                        String liveUrl = currentChannel.getPlayUrl();
                        if (TextUtils.isEmpty(liveUrl)) {
                            Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Calendar playDay = Calendar.getInstance();
                        playDay.add(Calendar.DAY_OF_YEAR, dayIndex);
                        String[] startHm = item.time.split(":");
                        // ========== 修复数组trim错误：先取下标0再trim ==========
                        int h = Integer.parseInt(startHm[0].trim());
                        int m = Integer.parseInt(startHm[1].trim());
                        Calendar startCal = (Calendar) playDay.clone();
                        startCal.set(Calendar.HOUR_OF_DAY, h);
                        startCal.set(Calendar.MINUTE, m);
                        startCal.set(Calendar.SECOND, 0);
                        String[] endHm = endTime.split(":");
                        int eh = Integer.parseInt(endHm[0].trim());
                        int em = Integer.parseInt(endHm[1].trim());
                        Calendar endCal = (Calendar) playDay.clone();
                        endCal.set(Calendar.HOUR_OF_DAY, eh);
                        endCal.set(Calendar.MINUTE, em);
                        endCal.set(Calendar.SECOND, 0);
                        String startStr = sdfFull.format(startCal.getTime());
                        String endStr = sdfFull.format(endCal.getTime());
                        String catchUrl = liveUrl.contains("PLTV") ? liveUrl.replace("PLTV", "TVOD") : liveUrl;
                        catchUrl += catchUrl.contains("?") ? "&playseek=" + startStr + "-" + endStr : "?playseek=" + startStr;
                        ((MainActivity) ctx).mPlayerManager.playUrl(catchUrl);
                        Toast.makeText(ctx, "回看：" + item.title, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                holder.tv_action.setText(bookedSet.contains(key) ? "已预约" : "预约");
                holder.tv_action.setBackgroundColor(bookedSet.contains(key) ? 0xFF607D8B : 0xFF4CAF50);
                holder.tv_action.setEnabled(true);
                holder.tv_action.setOnClickListener(v -> {
                    if (bookedSet.contains(key)) bookedSet.remove(key);
                    else bookedSet.add(key);
                    notifyDataSetChanged();
                    Toast.makeText(ctx, bookedSet.contains(key) ? "已预约：" + item.title : "已取消预约", Toast.LENGTH_SHORT);
                });
            }
            return convertView;
        }

        class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }
    }
}
