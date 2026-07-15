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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 日期列表管理器（已移除遥控器焦点）
 */
public class DateListManager {
    private final ListView lvDate;
    private Context context;
    private int selectedPosition = 0;
    private OnDateSelectedListener listener;
    private ArrayAdapter<String> adapter;
    private List<String> dateDisplayList;

    private static final int COLOR_BLUE = 0xFF40A9FF;
    private static final int COLOR_BG_BLUE = 0x3340A9FF;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    // hasFocus 已删除

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

        // 已删除 setOnItemSelectedListener
    }

    // setFocused / isFocused 已删除

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
            String dateStr = String.format("%d/%d", month, day);

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

                if (position == selectedPosition) {
                    tv.setTextColor(COLOR_BLUE);
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setBackgroundColor(COLOR_BG_BLUE);
                } else {
                    tv.setTextColor(COLOR_WHITE);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setBackgroundColor(Color.TRANSPARENT);
                }
                return tv;
            }
        };

        lvDate.setAdapter(adapter);

        lvDate.setOnItemClickListener((parent, view, position, id) -> {
            selectedPosition = position;
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
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    public void release() {
        if (adapter != null) {
            adapter.clear();
            adapter = null;
        }
        if (lvDate != null) {
            lvDate.setAdapter(null);
            lvDate.setOnItemClickListener(null);
        }
        if (dateDisplayList != null) {
            dateDisplayList.clear();
            dateDisplayList = null;
        }
        listener = null;
        context = null;
    }
}
