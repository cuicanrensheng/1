package com.tv.live.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分组列表管理器
 * 完全依赖系统原生焦点导航，样式由 XML 选择器控制
 */
public class GroupListManager {

    private final ListView lvGroup;
    private final Context context;
    private List<String> groupDisplayList;
    private List<String> groupNameList;
    private int selectedPosition = 0;
    private ArrayAdapter<String> adapter;
    private OnGroupSelectedListener listener;

    public static final String GROUP_ALL = "全部";

    public interface OnGroupSelectedListener {
        void onGroupSelected(int position, String groupName);
    }

    public void setOnGroupSelectedListener(OnGroupSelectedListener listener) {
        this.listener = listener;
    }

    public GroupListManager(Context context, ListView lvGroup) {
        this.context = context;
        this.lvGroup = lvGroup;
        lvGroup.setItemsCanFocus(false);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        lvGroup.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        lvGroup.setOnItemClickListener((parent, view, position, id) -> setSelectedPosition(position));
    }

    public void setGroups(List<Channel> channelSourceList) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;

        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel c : channelSourceList) groupSet.add(c.getGroup());
        List<String> originalGroups = new ArrayList<>(groupSet);

        groupNameList = new ArrayList<>();
        groupNameList.add(GROUP_ALL);
        groupNameList.addAll(originalGroups);

        groupDisplayList = new ArrayList<>();
        groupDisplayList.add(GROUP_ALL + " (" + channelSourceList.size() + ")");
        for (String group : originalGroups) {
            int count = 0;
            for (Channel c : channelSourceList) {
                if (group.equals(c.getGroup())) count++;
            }
            groupDisplayList.add(group);
        }

        // 🟢【改用自定义 ViewHolder+LayoutInflater，彻底避开 super.getView() 依赖 android.R.id.text1 的问题】
        adapter = new ArrayAdapter<String>(lvGroup.getContext(), R.layout.item_group, groupDisplayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ViewHolder holder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_group, parent, false);
                    holder = new ViewHolder();
                    // 假设您的 item_group.xml 里 TextView 的 id 是 tv_group
                    holder.tvGroup = convertView.findViewById(R.id.tv_group);
                    convertView.setTag(holder);
                } else {
                    holder = (ViewHolder) convertView.getTag();
                }

                if (holder.tvGroup != null) {
                    holder.tvGroup.setText(groupDisplayList.get(position));
                }

                // 🟢【核心修复】显式设置选中状态，让 XML 选择器的 state_selected 生效！
                convertView.setSelected(position == selectedPosition);

                // ✅【加粗逻辑】：当列表拥有焦点，且当前条目为选中项时，字体加粗
                boolean hasFocus = lvGroup.hasFocus();
                if (holder.tvGroup != null) {
                    if (position == selectedPosition && hasFocus) {
                        holder.tvGroup.setTypeface(null, Typeface.BOLD);
                    } else {
                        holder.tvGroup.setTypeface(null, Typeface.NORMAL);
                    }
                }

                // 颜色、背景全部由 R.layout.item_group 中的 XML 选择器自动控制
                return convertView;
            }
        };
        lvGroup.setAdapter(adapter);
        selectedPosition = 0;
        adapter.notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        if (groupDisplayList == null || adapter == null) return;
        if (position < 0 || position >= groupDisplayList.size()) return;
        if (selectedPosition == position) return;

        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
        if (listener != null) {
            listener.onGroupSelected(position, groupNameList.get(position));
        }
    }

    public String getCurrentGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return "";
        return groupNameList.get(position);
    }

    public int getGroupPosition(String groupName) {
        if (groupNameList == null || groupName == null) return 0;
        for (int i = 0; i < groupNameList.size(); i++) {
            if (groupName.equals(groupNameList.get(i))) return i;
        }
        return 0;
    }

    public boolean isAllGroup(int position) {
        if (groupNameList == null || position < 0 || position >= groupNameList.size()) return false;
        return GROUP_ALL.equals(groupNameList.get(position));
    }

    public boolean isSpecialGroup(int position) {
        return position == 0;
    }

    public void onBackPressed() {}

    // 🟢 增加 ViewHolder 静态内部类
    private static class ViewHolder {
        TextView tvGroup;
    }
}
