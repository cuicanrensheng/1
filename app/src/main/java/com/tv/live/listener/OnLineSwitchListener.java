package com.tv.live.listener;

import com.tv.live.bean.LineModel;

public interface OnLineSwitchListener {
    // 线路手动/自动切换回调
    void onLineChanged(LineModel line, int index);
    // 切换频道，线路列表刷新回调
    void onChannelLineListRefresh();
}
