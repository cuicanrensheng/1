package com.tv.live;

import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class ChannelListActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        ListView listView = new ListView(this);
        setContentView(listView);

        // ✅ 2026-06-25 修复：改用 ChannelPlayManager 获取频道列表
        // 【修复原因】
        // MainActivity 里的 channelSourceList、currentPlayIndex、playChannel()
        // 都已经移到 ChannelPlayManager 里了，直接访问会编译报错。
        // ChannelPlayManager 是单例模式，直接 getInstance() 获取更稳定。
        
        ChannelPlayManager channelPlayManager = ChannelPlayManager.getInstance(this);
        List<Channel> channelSourceList = channelPlayManager.getChannelSourceList();

        // 安全判断
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            finish();
            return;
        }

        // 用当前真正播放的下标定位
        final int currentRealIndex = channelPlayManager.getCurrentPlayIndex();

        List<String> names = new ArrayList<>();
        for (Channel c : channelSourceList) {
            names.add(c.getName());
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, names);
        listView.setAdapter(adapter);
        listView.setSelection(currentRealIndex);

        // 点击就用当前列表真实 position，100% 准
        listView.setOnItemClickListener((parent, view, position, id) -> {
            channelPlayManager.playChannel(position);
            finish();
        });
    }
}
