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
 * 新增全链路调试日志：定位下一条不显示、空白条目问题
 * 修复：先刷新再滚动、List深拷贝、getView全量重置控件
 * 区分今日/非今日播放样式，仅今日标记播放中
 */
public class EpgManagerWrapper {
    private static final String TAG = "EPG_DEBUG"; // 统一日志TAG
    private final ListView lvEpg;
    private final Context context;
    private EpgAdapter adapter;
    private final Set<String> bookedSet = new HashSet<>();
    private final Map<Channel.EpgItem, String> epgEndTimeMap = new HashMap<>();
    private static final String ACTION_REMINDER = "com.tv.live.EPG_REMINDER";
    private int selectedPosition = 0;
    private int playingIndex = -1;
    private int nextPlayIndex = -1; // 缓存下一档下标
    private int selectDayIndex = 0;

    public EpgManagerWrapper(Context context, ListView lvEpg) {
        this.context = context;
        this.lvEpg = lvEpg;
        lvEpg.setItemsCanFocus(false); // 移除过时警告
        lvEpg.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        lvEpg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                Log.d(TAG, "选中条目 position=" + pos);
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedPosition = -1;
                Log.d(TAG, "取消选中");
                if (adapter != null) adapter.notifyDataSetChanged();
            }
        });
        lvEpg.setOnFocusChangeListener((v, hasFocus) -> {
            Log.d(TAG, "ListView焦点变更 hasFocus=" + hasFocus);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
        registerReminderReceiver();
    }

    /** 外部遥控器：手动跳转下一档 */
    public void jumpNextProgram() {
        Log.d(TAG, "调用跳转下一档，缓存nextPlayIndex=" + nextPlayIndex);
        if (adapter == null || adapter.getCount() == 0 || nextPlayIndex == -1) {
            Toast.makeText(context, "暂无下一档节目", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedPosition = nextPlayIndex;
        lvEpg.setSelection(nextPlayIndex);
        adapter.notifyDataSetChanged();
    }

    /** 刷新核心方法，增加完整数据日志 */
    public void refresh(Channel currentChannel, List<Channel> channelSourceList, int dateIndex) {
        if (currentChannel == null) {
            SettingsActivity.log("【EPG包装】❌ refresh被调用，但current为空");
            Log.e(TAG, "刷新入参频道null，直接返回");
            return;
        }
        Log.d(TAG, "===== 开始刷新 ==== 频道=" + current.getName() + " dateIndex=" + dateIndex);
        playingIndex = -1;
        nextPlayIndex = -1;
        selectDayIndex = dateIndex;
        epgEndTimeMap.clear();

        new Thread(() -> {
            List<Channel.EpgItem> rawList;
            try {
                List<Channel.EpgItem> temp = EpgManager.getInstance().getEpg(currentChannel.getName());
                rawList = temp == null ? new ArrayList<>() : new ArrayList<>(temp);
            } catch (Exception e) {
                SettingsActivity.log("【EPG包装】获取EPG异常：" + e.getMessage());
                Log.e(TAG, "拉取原始EPG失败", e);
                rawList = new ArrayList<>();
            }
            Log.d(TAG, "原始EPG总数=" + rawList.size());
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
                Log.d(TAG, "筛选匹配日期 targetDay=" + targetDay + " week=" + weekDay);

                // 筛选
                int matchCount = 0;
                for (Channel.EpgItem item : rawList) {
                    if (TextUtils.isEmpty(item.dayName)) continue;
                    String dayName = item.dayName.trim();
                    boolean match = targetDay.equals(dayName);
                    if (!match && targetWeekDay != null) match = targetWeekDay.equals(dayName);
                    if (match) {
                        data.add(item); matchCount++;
                    }
                }
                Log.d(TAG, "筛选后节目数量=" + matchCount);
                Collections.sort(data, Comparator.comparing(o -> o.time));

                String now = getNow();
                Channel.EpgItem playing = null;
                // 遍历计算结束时间 + 打印每条节目日志
                for (int i = 0; i < data.size(); i++) {
                    Channel.EpgItem curr = data.get(i);
                    String rawTime = TextUtils.isEmpty(curr.time) ? "" : curr.time.trim();
                    String showStart = rawTime.contains("-") ? rawTime.split("-")[0].trim() : rawTime;
                    curr.time = showStart;
                    // 计算结束时间
                    String endStr;
                    if (i + 1 < data.size()) {
                        Channel.EpgItem nextItem = data.get(i + 1);
                        String nextRaw = TextUtils.isEmpty(nextItem.time) ? "" : nextItem.time;
                        endStr = nextRaw.contains("-") ? nextRaw.split("-")[0].trim() : nextRaw;
                    } else {
                        endStr = addOneHour(curr.time);
                    }
                    epgEndTimeMap.put(curr, endStr);
                    // 判断播放
                    curr.isPlaying = false;
                    if (isTimeBetween(now, curr.time, endStr)) {
                        curr.isPlaying = true;
                        playing = curr;
                        playingIndex = i;
                    }
                    // 单条节目日志
                    Log.d(TAG, "节目[" + i + "] title=" + curr.title + " time=" + curr.time + " end=" + endStr + " isPlaying=" + curr.isPlaying);
                }

                // 计算下一档下标
                if (dateIndex == 0) {
                    if (playingIndex != -1) {
                        nextPlayIndex = playingIndex + 1;
                        if (nextPlayIndex >= data.size()) nextPlayIndex = -1;
                        Log.d(TAG, "存在播放节目，下一档下标=" + nextPlayIndex);
                    } else {
                        nextPlayIndex = -1;
                        for (int i = 0; i < data.size(); i++) {
                            String t = data.get(i).time;
                            if (t.compareTo(now) > 0) {
                                nextPlayIndex = i;
                                Log.d(TAG, "无播放，首个未播下标=" + i);
                                break;
                            }
                        }
                    }
                } else {
                    nextPlayIndex = -1;
                    Log.d(TAG, "非今日，无实时下一档");
                }

                // 播放节目置顶
                if (playing != null && playingIndex > 0) {
                    data.remove(playing);
                    data.add(0, playing);
                    playingIndex = 0;
                    if (nextPlayIndex != -1) nextPlayIndex--;
                    Log.d(TAG, "播放节目置顶完成，修正后下一档=" + nextPlayIndex);
                }
            }

            // UI线程更新（修复：先notify再setSelection）
            final List<Channel.EpgItem> finalData = new ArrayList<>(data); // 深拷贝，避免引用错乱
            final Channel finalChannel = currentChannel;
            ((MainActivity) context).runOnUiThread(() -> {
                Log.d(TAG, "UI刷新回调，列表总数=" + finalData.size() + " 目标滚动pos=" + nextPlayIndex);
                if (adapter == null) {
                    adapter = new EpgAdapter(context, finalChannel, finalData, selectDayIndex);
                    lvEpg.setAdapter(adapter);
                } else {
                    adapter.setData(finalChannel, new ArrayList<>(finalData), selectDayIndex);
                }
                // 关键修复：先刷新数据，再滚动，防止条目消失
                adapter.notifyDataSetChanged();
                int targetScrollPos = 0;
                if (nextPlayIndex != -1) targetScrollPos = nextPlayIndex;
                else if (playingIndex != -1) targetScrollPos = playingIndex;
                targetScrollPos = Math.min(targetScrollPos, finalData.size() - 1);
                selectedPosition = targetScrollPos;
                lvEpg.setSelection(targetScrollPos);
                Log.d(TAG, "滚动完成，最终选中=" + targetScrollPos);
            });
        }).start();
    }

    private boolean isTimeBetween(String now, String start, String end) {
        try {
            if (TextUtils.isEmpty(now) || TextUtils.isEmpty(start) || TextUtils.isEmpty(end)) return false;
            return now.contains(":") && start.contains(":") && now.compareTo(start) >= 0 && now.compareTo(end) < 0;
        } catch (Exception e) {
            Log.w(TAG, "时间对比异常", e);
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
            Log.w(TAG, "时间加1小时异常", e);
            return "23:59";
        }
    }

    private String getNow() {
        return String.format("%02d:%02d", Calendar.getInstance().get(Calendar.HOUR_OF_DAY), Calendar.getInstance().get(Calendar.MINUTE));
    }

    private void registerReminderReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REMINDER.equals(intent.getAction())) {
                    String title = intent.getStringExtra("title");
                    Log.d(TAG, "节目提醒广播 title=" + title);
                    Toast.makeText(context, "节目提醒：" + title, Toast.LENGTH_LONG).show();
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_REMINDER));
    }

    // 适配器：getView增加逐行打印日志，前置清空控件解决复用空白
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
            this.list = new ArrayList<>(list);
            this.inflater = LayoutInflater.from(ctx);
            this.dayIndex = dayIndex;
            Log.d(TAG, "适配器初始化，数据条数=" + list.size());
        }

        public void setData(Channel currentChannel, List<Channel.EpgItem> list, int dayIndex) {
            this.currentChannel = currentChannel;
            this.list.clear();
            this.list.addAll(new ArrayList<>(list)); // 深拷贝，外部list修改不影响适配器
            this.dayIndex = dayIndex;
            Log.d(TAG, "适配器更新数据，新条数=" + list.size());
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            boolean isNewView = convertView == null;
            if (isNewView) {
                convertView = inflater.inflate(R.layout.item_epg, parent, false);
                holder = new ViewHolder();
                holder.tv_dayName = convertView.findViewById(R.id.tv_dayName);
                holder.tv_time = convertView.findViewById(R.id.tv_time);
                holder.tv_title = convertView.findViewById(R.id.tv_title);
                holder.tv_action = convertView.findViewById(R.id.tv_action);
                convertView.setTag(holder);
                Log.d(TAG, "getView 创建新View position=" + position);
            } else {
                holder = (ViewHolder) convertView.getTag();
                Log.d(TAG, "getView 复用旧View position=" + position);
            }

            // 【关键修复】复用前全量清空所有控件，杜绝残留空白/错乱
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

            // 下标越界保护
            if (position < 0 || position >= list.size()) {
                Log.w(TAG, "getView 下标越界 position=" + position + " listSize=" + list.size());
                return convertView;
            }
            Channel.EpgItem item = list.get(position);
            String endTime = epgEndTimeMap.get(item);
            String dayText = TextUtils.isEmpty(item.dayName) ? "" : item.dayName;
            String timeText = (TextUtils.isEmpty(item.time) ? "" : item.time) + "-" + (TextUtils.isEmpty(endTime) ? "23:59" : endTime);
            String titleText = TextUtils.isEmpty(item.title) ? "" : item.title;

            // 绑定数据 + 日志打印每条渲染内容
            holder.tv_dayName.setText(dayText);
            holder.tv_time.setText(timeText);
            holder.tv_title.setText(titleText);
            Log.d(TAG, "渲染条目[" + position + "] title=" + titleText + " time=" + timeText + " isPlaying=" + item.isPlaying);

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

            // 按钮逻辑日志
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
                    Log.d(TAG, "点击回看 position=" + position + " title=" + item.title);
                    try {
                        String liveUrl = currentChannel.getPlayUrl();
                        if (TextUtils.isEmpty(liveUrl)) {
                            Toast.makeText(ctx, "无播放地址", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Calendar playDay = Calendar.getInstance();
                        playDay.add(Calendar.DAY_OF_YEAR, dayIndex);
                        String[] startHm = item.time.split(":");
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
                        Log.e(TAG, "回看播放失败", e);
                        Toast.makeText(ctx, "回看失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                String btnTxt = bookedSet.contains(key) ? "已预约" : "预约";
                holder.tv_action.setText(btnTxt);
                holder.tv_action.setBackgroundColor(bookedSet.contains(key) ? 0xFF607D8B : 0xFF4CAF50);
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

        class ViewHolder {
            TextView tv_dayName;
            TextView tv_time;
            TextView tv_title;
            TextView tv_action;
        }
    }
}
