package com.tv.live.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分组列表管理器
 *
 * 【2026-07-04 修改：移除收藏和最近观看功能】
 * 删除了 GROUP_FAVORITE 和 GROUP_RECENT 的添加逻辑，
 * 分组列表现在只显示【全部】和【实际存在的频道分组】。
 *
 * 【样式规范】
 * - 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
 * - 无焦点 + 选中：蓝色文字 + 透明背景
 * - 未选中：白色文字 + 透明背景
 */
public class GroupListManager {

    /** 分组列表 ListView */
    private final ListView lvGroup;
    /** 上下文 */
    private final Context context;
    /** 分组名称列表 */
    private List<String> groupList;
    /** 每个分组的频道数量 */
    private List<Integer> groupCountList;
    /** 当前选中位置 */
    private int selectedPosition = 0;
    /** 列表适配器 */
    private ArrayAdapter<String> adapter;
    /** 分组选中监听器（供外部回调） */
    private OnGroupSelectedListener listener;

    /**
     * 当前列表是否有焦点
     * - true = 当前光标在这个列表上，选中项用浅蓝色背景 + 蓝色文字 + 加粗
     * - false = 当前光标不在这个列表上，选中项用蓝色文字 + 透明背景
     */
    private boolean hasFocus = false;

    /** 特殊分组：全部频道 */
    public static final String GROUP_ALL = "全部";
    /** 特殊分组：收藏频道 (已废弃) */
    public static final String GROUP_FAVORITE = "收藏";
    /** 特殊分组：最近观看 (已废弃) */
    public static final String GROUP_RECENT = "最近观看";

    /**
     * 分组选中监听器接口
     */
    public interface OnGroupSelectedListener {
        void onGroupSelected(int position, String groupName);
    }

    /**
     * 设置分组选中监听器
     */
    public void setOnGroupSelectedListener(OnGroupSelectedListener listener) {
        this.listener = listener;
    }

    /**
     * 构造函数
     */
    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;
        lvGroup.setItemsCanFocus(false);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // 点击选中事件
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                setSelectedPosition(position);
            }
        });
    }

    /**
     * 设置当前列表是否有焦点
     */
    public void setFocused(boolean focused) {
        if (this.hasFocus == focused) return;
        this.hasFocus = focused;
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * 获取当前是否有焦点
     */
    public boolean isFocused() {
        return hasFocus;
    }

    /**
     * 设置分组列表
     * 
     * 【2026-07-04 修改】移除收藏和最近观看特殊分组，只保留“全部”和实际频道分组。
     *
     * @param channelSourceList 全部频道列表
     * @param favoriteCount 收藏频道数量 (已废弃)
     * @param recentCount 最近观看频道数量 (已废弃)
     */
    public void setGroups(List<Channel> channelSourceList, int favoriteCount, int recentCount) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        // 用 LinkedHashSet 提取分组，保持出现顺序
        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel c : channelSourceList) {
            groupSet.add(c.getGroup());
        }
        List<String> originalGroups = new ArrayList<>(groupSet);

        // ✅ 修改：只保留【全部】和【实际分组】
        groupList = new ArrayList<>();
        groupList.add(GROUP_ALL);       // 1. 全部
        groupList.addAll(originalGroups); // 2. 实际分组

        // ✅ 计算每个分组的频道数量
        groupCountList = new ArrayList<>();
        groupCountList.add(channelSourceList.size()); // 全部

        // 实际分组数量
        for (String group : originalGroups) {
            int count = 0;
            for (Channel c : channelSourceList) {
                if (group.equals(c.getGroup())) {
                    count++;
                }
            }
            groupCountList.add(count);
        }

        adapter = new ArrayAdapter<String>(lvGroup.getContext(),
                android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);
                
                // 🟢 修复：只有“全部”分组才显示数字，其他分组只显示纯文本名称
                String groupName = groupList.get(position);
                int count = groupCountList.get(position);
                if (position == 0 && GROUP_ALL.equals(groupName)) {
                    tv.setText(groupName + " (" + count + ")");
                } else {
                    tv.setText(groupName);
                }

                // 三种状态样式（区分焦点态）
                if (position == selectedPosition) {
                    if (hasFocus) {
                        // 有焦点 + 选中：浅蓝色背景 + 蓝色文字 + 加粗
                        tv.setTextColor(Color.parseColor("#40A9FF"));
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(0x3340A9FF); 
                    } else {
                        // 无焦点 + 选中：蓝色文字 + 透明背景（只是标记，不抢视线）
                        tv.setTextColor(Color.parseColor("#40A9FF"));
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(Color.TRANSPARENT);
                    }
                } else {
                    // 未选中：白色文字 + 透明背景
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return view;
            }
        };
        lvGroup.setAdapter(adapter);
        
        // 默认选中「全部」
        selectedPosition = 0;
        adapter.notifyDataSetChanged();
    }

    /**
     * 已废弃：由于移除了收藏和最近观看功能，此方法不再需要。
     */
    public void updateSpecialGroupCount(int favoriteCount, int recentCount) {
        // 此方法已被移除，不需要做任何事情
    }

    /**
     * 设置选中位置，立即刷新高亮
     */
    public void setSelectedPosition(int position) {
        if (groupList == null || adapter == null) return;
        if (position < 0 || position >= groupList.size()) return;
        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
        if (listener != null) {
            listener.onGroupSelected(position, groupList.get(position));
        }
    }

    /**
     * 获取指定位置的分组名称
     */
    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    /**
     * 根据分组名获取位置
     */
    public int getGroupPosition(String groupName) {
        if (groupList == null || groupName == null) return 0;
        for (int i = 0; i < groupList.size(); i++) {
            if (groupName.equals(groupList.get(i))) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 判断是不是「全部」分组
     */
    public boolean isAllGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return false;
        return GROUP_ALL.equals(groupList.get(position));
    }

    /**
     * 判断是不是特殊分组（全部）
     */
    public boolean isSpecialGroup(int position) {
        return position == 0; // 现在只有第 0 项（全部）是特殊的
    }

    public void onBackPressed() {}
}
