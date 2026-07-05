package com.tv.live.manager;

import android.content.Context;
import android.content.SharedPreferences;
import com.tv.live.bean.IptvChannel;
import com.tv.live.bean.LineModel;
import com.tv.live.listener.OnLineSwitchListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class LineSelectManager {
    private static final String SP_NAME = "channel_line_sp";
    private static LineSelectManager instance;
    private Context mAppContext;
    private IptvChannel currentPlayChannel;
    private final CopyOnWriteArrayList<LineModel> currChannelLines;
    private int currSelectIndex;
    private final List<OnLineSwitchListener> listeners;

    private LineSelectManager() {
        currChannelLines = new CopyOnWriteArrayList<>();
        listeners = new ArrayList<>();
        currSelectIndex = 0;
    }

    public static LineSelectManager getInstance() {
        if (instance == null) {
            instance = new LineSelectManager();
        }
        return instance;
    }

    // Application全局初始化
    public void init(Context context) {
        mAppContext = context.getApplicationContext();
    }

    public void addListener(OnLineSwitchListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(OnLineSwitchListener listener) {
        listeners.remove(listener);
    }

    // 切换播放频道，加载该频道全部备用线路并恢复缓存选中下标
    public void bindPlayChannel(IptvChannel channel) {
        currentPlayChannel = channel;
        currChannelLines.clear();
        currChannelLines.addAll(channel.getSourceList());
        SharedPreferences sp = getSp();
        currSelectIndex = sp.getInt("ch_" + channel.getChannelId(), 0);
        // 下标越界保护
        if (currSelectIndex >= currChannelLines.size()) {
            currSelectIndex = 0;
        }
        notifyListRefresh();
    }

    public List<LineModel> getCurrentAllLines() {
        return currChannelLines;
    }

    // 获取当前选中线路
    public LineModel getSelectedLine() {
        if (currChannelLines.isEmpty()) return null;
        int safeIndex = Math.max(0, Math.min(currSelectIndex, currChannelLines.size() - 1));
        return currChannelLines.get(safeIndex);
    }

    public int getCurrentIndex() {
        return currSelectIndex;
    }

    // Spinner手动选中线路
    public void selectLine(int index) {
        if (currChannelLines.isEmpty()) return;
        int targetIdx = Math.max(0, Math.min(index, currChannelLines.size() - 1));
        currSelectIndex = targetIdx;
        saveSelectIndexToSp();
        LineModel targetLine = currChannelLines.get(currSelectIndex);
        for (OnLineSwitchListener listener : listeners) {
            listener.onLineChanged(targetLine, currSelectIndex);
        }
    }

    // 播放失败自动切换下一条备用线路
    public boolean autoSwitchNextBackup() {
        LineModel currLine = getSelectedLine();
        if (currLine == null || !currLine.isEnableAutoSwitch()) return false;
        currLine.setFailCount(currLine.getFailCount() + 1);
        int total = currChannelLines.size();
        if (total <= 1) return false;
        int nextIdx = (currSelectIndex + 1) % total;
        if (nextIdx == currSelectIndex) return false;
        selectLine(nextIdx);
        return true;
    }

    // 遥控器左键：上一条线路
    public void selectPrevLine() {
        if (currChannelLines.isEmpty()) return;
        int target = currSelectIndex - 1;
        if (target < 0) target = currChannelLines.size() - 1;
        selectLine(target);
    }

    // 遥控器右键：下一条线路
    public void selectNextLine() {
        if (currChannelLines.isEmpty()) return;
        int target = (currSelectIndex + 1) % currChannelLines.size();
        selectLine(target);
    }

    // 切换频道重置当前频道所有线路失败计数
    public void resetCurrentChannelFailCount() {
        for (LineModel line : currChannelLines) {
            line.setFailCount(0);
        }
    }

    // 清空全部频道线路缓存
    public void clearAllCache() {
        getSp().edit().clear().apply();
        currChannelLines.clear();
        currentPlayChannel = null;
        currSelectIndex = 0;
        notifyListRefresh();
    }

    // 持久化保存当前频道选中下标
    private void saveSelectIndexToSp() {
        if (currentPlayChannel == null) return;
        getSp().edit()
                .putInt("ch_" + currentPlayChannel.getChannelId(), currSelectIndex)
                .apply();
    }

    private SharedPreferences getSp() {
        return mAppContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    // 通知UI刷新线路下拉列表
    private void notifyListRefresh() {
        for (OnLineSwitchListener listener : listeners) {
            listener.onChannelLineListRefresh();
        }
    }
}
