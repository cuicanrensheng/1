package com.tv.live.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 日期列表管理器
 * 完全依赖系统原生焦点导航，样式由 XML 选择器控制
 */
public class DateListManager {
    private final ListView lvDate;
    private final Context context;
    private int selectedPosition = 0;
    private OnDateSelectedListener listener;
    private ArrayAdapter<String> adapter;
    private List<String> dateDisplayList;

    public interface OnDateSelectedListener {
        void onDateSelected(int position);
    }

    public void setOnDateSelectedListener(OnDateSelectedListener listener) {
        this.listener = listener;
    }

    public DateListManager(Context context, ListView lvDate) {
        this.context = context;
        this.lvDate = lvDate;
        lvDate.setItemsCanFocus(false);
        lvDate.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        lvDate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                selectedPosition = pos;
                // 样式完全由 XML 状态选择器自动处理，无需手动 notifyDataSetChanged
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    public void initDate() {
        dateDisplayList = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        String[] week = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

        for (int i = 0; i < 8; i++) {
            String weekStr;
            if (i == 0) {
                weekStr = "今天";
            } else if (i == 1) {
                weekStr = "明天";
            } else if (i == 2) {
                weekStr = "后天";
            } else {
                int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
                weekStr = week[dayOfWeek - 1];
            }

            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DAY_OF_MONTH);
            String dateStr = month + "/" + day;

            dateDisplayList.add(weekStr + "\n" + dateStr);
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        adapter = new ArrayAdapter<String>(context, R.layout.item_date, dateDisplayList) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);

                tv.setSingleLine(false);
                tv.setMaxLines(2);
                tv.setTextSize(14);
                tv.setGravity(android.view.Gravity.CENTER);

                // 🟢【核心修复】显式设置选中状态，触发 XML 选择器中的 state_selected！
                tv.setSelected(position == selectedPosition);

                // =========================================================
                // ✅【加粗逻辑】
                // 当列表拥有焦点，且当前条目为选中项时，字体加粗
                // =========================================================
                boolean hasFocus = lvDate.hasFocus();
                if (position == selectedPosition && hasFocus) {
                    tv.setTypeface(null, Typeface.BOLD);
                } else {
                    tv.setTypeface(null, Typeface.NORMAL);
                }

                return tv;
            }
        };

        lvDate.setAdapter(adapter);

        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            // 🟢 去掉 adapter.notifyDataSetChanged(); 因为它会导致全量重绘
            // 选中状态现在由 tv.setSelected() 配合 XML 完美接管
            if (listener != null) {
                listener.onDateSelected(position);
            }
        });
    }

    public void setSelectedPosition(int position) {
        if (dateDisplayList == null || adapter == null) return;
        if (position < 0 || position >= dateDisplayList.size()) return;
        if (this.selectedPosition == position) return;

        selectedPosition = position;
        // 如果外部代码（比如控制器）主动调用了 setSelectedPosition，
        // 我们只需更新变量，稍后 `getView` 更新时会自动带上新状态。
        adapter.notifyDataSetChanged();
    }
}
