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
import com.tv.live.R;
import com.tv.live.SettingsActivity;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 日期列表管理器【内存泄漏修复完整版】
 */
public class DateListManager {
    // 弱引用存储上下文与ListView，杜绝强持有View/Activity
    private final WeakReference<Context> ctxRef;
    private final WeakReference<ListView> lvRef;

    private int selectedPosition = 0;
    private boolean hasFocus = false;
    private ArrayAdapter<String> adapter;
    private List<String> dateDisplayList;

    public interface OnDateSelectedListener {
        void onDateSelected(int position);
    }
    private OnDateSelectedListener listener;

    // ===================== 全部静态弱引用监听器/适配器 =====================
    // 列表选中监听
    private static class DateSelectListener implements AdapterView.OnItemSelectedListener {
        private final WeakReference<DateListManager> mgrRef;
        public DateSelectListener(DateListManager mgr) {
            mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
            DateListManager manager = mgr.get();
            if (manager == null) return;
            manager.selectedPosition = pos;
            if (manager.adapter != null) manager.adapter.notifyDataSetChanged();
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    }

    // 列表点击监听
    private static class DateClickListener implements AdapterView.OnItemClickListener {
        private final WeakReference<DateListManager> mgrRef;
        public DateClickListener(DateListManager mgr) {
            mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
            DateListManager manager = mgr.get();
            if (manager == null) return;
            manager.selectedPosition = pos;
            if (manager.adapter != null) manager.adapter.notifyDataSetChanged();

            Context ctx = manager.ctxRef.get();
            if (ctx == null || manager.dateDisplayList == null) return;
            SettingsActivity.logOperation("【日期列表】👆 点击：位置" + pos + "，" + manager.dateDisplayList.get(pos));

            if (manager.listener != null) {
                SettingsActivity.logOperation("【日期列表】✅ 触发回调");
                manager.listener.onDateSelected(pos);
            } else {
                SettingsActivity.logOperation("【日期列表】❌ listener为空，未触发回调");
            }
        }
    }

    // 静态弱引用适配器，消除匿名Adapter持有外部类
    private static class DateAdapter extends ArrayAdapter<String> {
        private final WeakReference<DateListManager> mgrRef;
        public DateAdapter(Context ctx, int res, List<String> data, DateListManager mgr) {
            super(ctx, res, data);
            mgrRef = new WeakReference<>(mgr);
        }
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            DateListManager manager = mgr.get();
            Context ctx = getContext();
            if (convertView == null) {
                convertView = android.view.LayoutInflater.from(ctx).inflate(R.layout.item_date, parent, false);
            }
            TextView tv = (TextView) convertView;
            tv.setSingleLine(false);
            tv.setMaxLines(2);
            tv.setTextSize(14);
            tv.setGravity(android.view.Gravity.CENTER);

            String text = getItem(position);
            tv.setText(text);

            if (manager == null) return convertView;
            // 焦点/选中样式
            if (position == manager.selectedPosition) {
                if (manager.hasFocus) {
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
            return convertView;
        }
    }

    // 构造：弱引用包装ApplicationContext与ListView
    public DateListManager(Context context, ListView lvDate) {
        this.ctxRef = new WeakReference<>(context.getApplicationContext());
        this.lvRef = new WeakReference<>(lvDate);

        ListView lv = lvRef.get();
        if (lv != null) {
            lv.setItemsCanFocus(false);
            lv.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            // 绑定静态弱引用监听，不再使用匿名内部类
            lv.setOnItemSelectedListener(new DateSelectListener(this));
        }
    }

    // 安全获取上下文
    private Context getCtx() {
        return ctx != null ? ctxRef.get() : null;
    }

    // 安全获取ListView
    private ListView getLv() {
        return lvRef != null ? lvRef.get() : null;
    }

    public void setOnDateSelectedListener(OnDateSelectedListener listener) {
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

    /** 初始化8天日期列表 */
    public void initDate() {
        Context ctx = getCtx();
        ListView lv = getLv();
        if (ctx == null || lv == null) return;

        dateDisplayList = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        String[] weekArr = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        for (int i = 0; i < 8; i++) {
            String weekStr;
            if (i == 0) weekStr = "今天";
            else if (i == 1) weekStr = "明天";
            else if (i == 2) weekStr = "后天";
            else {
                int weekIdx = cal.get(Calendar.DAY_OF_WEEK) - 1;
                weekStr = weekArr[weekIdx];
            }
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DAY_OF_MONTH);
            String dateText = weekStr + "\n" + month + "/" + day;
            dateDisplayList.add(dateText);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        SettingsActivity.logOperation("【日期列表】初始化：" + dateDisplayList);

        // 使用静态适配器
        adapter = new DateAdapter(ctx, R.layout.item_date, dateDisplayList, this);
        lv.setAdapter(adapter);
        // 绑定静态点击监听
        lv.setOnItemClickListener(new DateClickListener(this));
    }

    public void setSelectedPosition(int position) {
        this.selectedPosition = position;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    // ========== 标准完整release 全部资源释放 ==========
    public void release() {
        // 1 清空业务监听器
        listener = null;

        // 2 解绑ListView所有监听、清空适配器
        ListView lv = getLv();
        if (lv != null) {
            lv.setOnItemClickListener(null);
            lv.setOnItemSelectedListener(null);
            lv.setAdapter(null);
        }

        // 3 清空弱引用
        if (ctxRef != null) ctxRef.clear();
        if (lvRef != null) lvRef.clear();

        // 4 清空日期数据集合
        if (dateDisplayList != null) {
            dateDisplayList.clear();
            dateDisplayList = null;
        }
        adapter = null;

        // 5 重置状态标记
        selectedPosition = 0;
        hasFocus = false;
    }
}
