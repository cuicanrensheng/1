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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分组列表管理器【内存泄漏修复完整版】
 */
public class GroupListManager {
    public static final String GROUP_ALL = "全部";
    public static final String GROUP_FAVORITE = "收藏";
    public static final String GROUP_RECENT = "最近观看";

    private final ListView lvGroup;
    private final WeakReference<Context> ctxRef;

    private List<String> groupList;
    private List<Integer> groupCountList;
    private int selectedPosition = 0;
    private boolean hasFocus = false;
    private ArrayAdapter<String> adapter;
    private OnGroupSelectedListener listener;

    public interface OnGroupSelectedListener {
        void onGroupSelected(int position, String groupName);
    }

    // ===================== 静态弱引用监听器 =====================
    private static class GroupSelectListener implements AdapterView.OnItemSelectedListener {
        private final WeakReference<GroupListManager> mgrRef;
        public GroupSelectListener(GroupListManager mgr) {
            mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
            GroupListManager manager = mgr.get();
            if (manager == null) return;
            manager.selectedPosition = pos;
            if (manager.adapter != null) manager.adapter.notifyDataSetChanged();
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    }

    private static class GroupClickListener implements AdapterView.OnItemClickListener {
        private final WeakReference<GroupListManager> mgrRef;
        public GroupClickListener(GroupListManager mgr) {
            mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            GroupListManager manager = mgr.get();
            if (manager == null) return;
            manager.setSelectedPosition(position);
        }
    }

    // 构造：弱引用包装ApplicationContext
    public GroupListManager(Context context, ListView lvGroup) {
        this.ctxRef = new WeakReference<>(context.getApplicationContext());
        this.lvGroup = lvGroup;
        lvGroup.setItemsCanFocus(false);
        lvGroup.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        // 绑定静态监听，移除匿名内部类
        lvGroup.setOnItemSelectedListener(new GroupSelectListener(this));
        lvGroup.setOnItemClickListener(new GroupClickListener(this));
    }

    // 安全获取上下文
    private Context getCtx() {
        return ctx != null ? ctxRef.get() : null;
    }

    public void setOnGroupSelectedListener(OnGroupSelectedListener listener) {
        this.listener = listener;
    }

    public void setFocused(boolean focused) {
        if (this.hasFocus == focused) return;
        this.hasFocus = focused;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    public boolean isFocused() {
        return hasFocus;
    }

    public void setGroups(List<Channel> channelSourceList, int favoriteCount, int recentCount) {
        Context ctx = getCtx();
        if (ctx == null || channelSourceList == null || channelSourceList.isEmpty()) return;

        Set<String> groupSet = new LinkedHashSet<>();
        for (Channel c : channelSourceList) groupSet.add(c.getGroup());
        List<String> originalGroups = new ArrayList<>(groupSet);

        groupList = new ArrayList<>();
        groupList.add(GROUP_ALL);
        groupList.add(GROUP_FAVORITE);
        groupList.add(GROUP_RECENT);
        groupList.addAll(originalGroups);

        groupCountList = new ArrayList<>();
        groupCountList.add(channelSourceList.size());
        groupCountList.add(favoriteCount);
        groupCountList.add(recentCount);

        for (String group : originalGroups) {
            int cnt = 0;
            for (Channel c : channelSourceList) {
                if (group.equals(c.getGroup())) cnt++;
            }
            groupCountList.add(cnt);
        }

        adapter = new ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, groupList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView tv = view.findViewById(android.R.id.text1);
                tv.setTextSize(16);
                tv.setPadding(20, 15, 20, 15);

                String name = groupList.get(position);
                int num = groupCountList.get(position);
                tv.setText(name + " (" + num + ")");

                if (position == selectedPosition) {
                    if (hasFocus) {
                        tv.setTextColor(Color.parseColor("#40A9FF"));
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(0x3340A9FF);
                    } else {
                        tv.setTextColor(Color.parseColor("#40A9FF"));
                        tv.setTypeface(null, Typeface.BOLD);
                        tv.setBackgroundColor(Color.TRANSPARENT);
                    }
                } else {
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return view;
            }
        };
        lvGroup.setAdapter(adapter);
        selectedPosition = 0;
        adapter.notifyDataSetChanged();
    }

    public void updateSpecialGroupCount(int favoriteCount, int recentCount) {
        if (groupCountList == null || groupCountList.size() < 3) return;
        groupCountList.set(1, favoriteCount);
        groupCountList.set(2, recentCount);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        if (groupList == null || adapter == null) return;
        if (position < 0 || position >= groupList.size()) return;
        selectedPosition = position;
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        adapter.notifyDataSetChanged();
        if (listener != null) listener.onGroupSelected(position, groupList.get(position));
    }

    public String getCurrentGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return "";
        return groupList.get(position);
    }

    public int getGroupPosition(String groupName) {
        if (groupList == null || groupName == null) return 0;
        for (int i = 0; i < groupList.size(); i++) {
            if (groupName.equals(groupList.get(i))) return i;
        }
        return 0;
    }

    public boolean isAllGroup(int position) {
        if (groupList == null || position < 0 || position >= groupList.size()) return false;
        return GROUP_ALL.equals(groupList.get(position));
    }

    public boolean isSpecialGroup(int position) {
        return position < 3;
    }

    public void onBackPressed() {}

    // ========== 标准完整release() 规范资源释放 ==========
    public void release() {
        // 1. 清空业务监听器
        listener = null;

        // 2. 解绑ListView全部监听
        if (lvGroup != null) {
            lvGroup.setOnItemSelectedListener(null);
            lvGroup.setOnItemClickListener(null);
            lvGroup.setAdapter(null);
        }

        // 3. 清空上下文弱引用
        if (ctxRef != null) ctxRef.clear();

        // 4. 清空集合、适配器、控件引用
        if (groupList != null) {
            groupList.clear();
            groupList = null;
        }
        if (groupCountList != null) {
            groupCountList.clear();
            groupCountList = null;
        }
        adapter = null;

        // 5. 重置状态标记
        selectedPosition = 0;
        hasFocus = false;
    }
}
