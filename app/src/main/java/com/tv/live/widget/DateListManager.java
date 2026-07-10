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
                // 不再手动 notifyDataSetChanged，因为样式由 XML 选择器自动处理
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // 删除 setFocused() 和 isFocused()

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

                // =========================================================
                // ✅【补充：加粗逻辑】
                // 当列表拥有焦点，且当前条目为选中项时，字体加粗
                // =========================================================
                boolean hasFocus = lvDate.hasFocus();
                if (position == selectedPosition && hasFocus) {
                    tv.setTypeface(null, Typeface.BOLD);
                } else {
                    tv.setTypeface(null, Typeface.NORMAL);
                }

                // 样式完全由 XML 选择器（state_focused / state_selected）自动控制
                return tv;
            }
        };

        lvDate.setAdapter(adapter);

        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
            // 通知适配器更新，但样式由 XML 选择器控制，我们仍需要更新 selectedPosition 以便点击后生效
            adapter.notifyDataSetChanged();
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
        // 仍需刷新适配器，因为 selectedPosition 变化会影响显示（如播放指示）
        adapter.notifyDataSetChanged();
    }
}
