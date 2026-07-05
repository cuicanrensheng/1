package com.tv.live.widget.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import com.tv.live.R;
import com.tv.live.bean.LineModel;
import java.util.List;

public class LineSpinnerAdapter extends ArrayAdapter<LineModel> {
    private final List<LineModel> dataList;

    public LineSpinnerAdapter(Context context, List<LineModel> data) {
        super(context, 0, data);
        this.dataList = data;
    }

    // Spinner收起时单行样式
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = LayoutInflater.from(getContext()).inflate(R.layout.item_spinner_selected, parent, false);
        }
        TextView tvName = view.findViewById(R.id.tv_line_name);
        LineModel item = dataList.get(position);
        tvName.setText(item.getLineName());
        tvName.setTextColor(0xFFFFFFFF);
        view.setBackgroundColor(0xFF222222);
        return view;
    }

    // 下拉弹窗条目，TV遥控器焦点高亮
    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = LayoutInflater.from(getContext()).inflate(R.layout.item_spinner_drop, parent, false);
        }
        TextView tvName = view.findViewById(R.id.tv_line_name);
        LineModel item = dataList.get(position);
        tvName.setText(item.getLineName());

        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.setBackgroundColor(0xFF3366CC);
                tvName.setTextColor(0xFFFFFFFF);
            } else {
                v.setBackgroundColor(0xFF111111);
                tvName.setTextColor(0xFFCCCCCC);
            }
        });
        return view;
    }
}
