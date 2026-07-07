package com.tv.live;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.List;

public class SwitchSourceAdapter extends ArrayAdapter<SourceManager.SourceItem> {

    private int selectedPosition = -1;
    private OnDeleteClickListener deleteListener;

    // 🟢 修复：只保留一个抽象方法，使接口成为“函数式接口”，完美兼容 Lambda 写法
    public interface OnDeleteClickListener {
        void onDelete(int position);
    }

    public SwitchSourceAdapter(Context context, List<SourceManager.SourceItem> items) {
        super(context, 0, items);
    }

    public void setOnDeleteClickListener(OnDeleteClickListener listener) {
        this.deleteListener = listener;
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
        notifyDataSetChanged();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_switch_source, parent, false);
        }

        SourceManager.SourceItem item = getItem(position);
        if (item == null) return convertView;

        RadioButton rbSelect = convertView.findViewById(R.id.rb_select);
        TextView tvUrl = convertView.findViewById(R.id.tv_url);
        Button btnDelete = convertView.findViewById(R.id.btn_delete_source);

        // 完整显示链接
        tvUrl.setText(item.url);
        tvUrl.setSingleLine(false);
        tvUrl.setEllipsize(null);

        // 选中状态
        rbSelect.setChecked(position == selectedPosition);

        // 默认源无删除按钮
        if (item.isDefault) {
            btnDelete.setVisibility(View.GONE);
        } else {
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onDelete(position);
                }
            });
        }

        // 整行点击：只更新选中样式，实际的切换逻辑交给 ListView 的 onItemClickListener 处理
        convertView.setOnClickListener(v -> {
            setSelectedPosition(position);
        });

        return convertView;
    }
}
