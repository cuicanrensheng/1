package com.tv.live.manager;
import android.view.View;
import com.tv.live.Channel;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import java.util.List;

/**
 * 面板管理类
 * 控制左侧频道面板、节目单的显示与隐藏
 * 优化点：打开面板保留上次选中的日期，不强制重置为今天
 * 新增：面板打开/关闭状态回调，联动右上角/右下角信息栏刷新
 */
public class PanelManager {
    // 面板根布局
    private final View panelLayout;
    // 频道列表管理器
    private final ChannelListManager channelListManager;
    // 节目单管理器
    private final EpgManagerWrapper epgManagerWrapper;
    // 当前选中的日期索引，默认今天=0
    private int currentDateIndex = 0;
    // 面板是否打开标记
    private boolean isPanelOpen = false;

    // ===================== 新增：面板显隐回调接口 =====================
    public interface OnPanelVisibilityListener {
        void onPanelVisible(boolean visible);
    }
    private OnPanelVisibilityListener visibilityListener;

    public void setOnPanelVisibilityListener(OnPanelVisibilityListener listener) {
        this.visibilityListener = listener;
    }

    /**
     * 构造方法
     * @param panelLayout 整个左侧面板布局
     * @param channelListManager 频道列表管理
     * @param epgManagerWrapper 节目单管理
     */
    public PanelManager(View panelLayout, ChannelListManager channelListManager, EpgManagerWrapper epgManagerWrapper) {
        this.panelLayout = panelLayout;
        this.channelListManager = channelListManager;
        this.epgManagerWrapper = epgManagerWrapper;
        // 初始化面板状态
        isPanelOpen = panelLayout.getVisibility() == View.VISIBLE;
    }

    /**
     * 设置当前选中的日期索引
     * 切换日期时调用，同步更新面板内的日期状态
     * @param dateIndex 日期索引
     */
    public void setCurrentDateIndex(int dateIndex) {
        this.currentDateIndex = dateIndex;
    }

    /**
     * 获取当前选中日期下标
     */
    public int getCurrentDateIndex() {
        return currentDateIndex;
    }

    /**
     * 判断面板是否处于打开状态
     */
    public boolean isPanelOpen() {
        return isPanelOpen;
    }

    /**
     * 简化版面板开关（无参数版）
     * 仅切换显隐，不处理频道/日期同步（适合仅需纯显隐场景）
     */
    public void togglePanel() {
        isPanelOpen = !isPanelOpen;
        panelLayout.setVisibility(isPanelOpen ? View.VISIBLE : View.GONE);
        // 回调通知面板状态变更
        if (visibilityListener != null) {
            visibilityListener.onPanelVisible(isPanelOpen);
        }
    }

    /**
     * 完整版面板开关（带频道/日期同步）
     * @param channelList 频道列表
     * @param currentIndex 当前播放的频道下标
     * @param dateListManager 日期列表管理器，用于同步选中高亮
     */
    public void toggle(List<Channel> channelList, int currentIndex, DateListManager dateListManager) {
        if (panelLayout.getVisibility() == View.VISIBLE) {
            // 面板当前显示 → 隐藏
            panelLayout.setVisibility(View.GONE);
            isPanelOpen = false;
        } else {
            // 面板当前隐藏 → 打开
            panelLayout.setVisibility(View.VISIBLE);
            isPanelOpen = true;
            // 同步日期列表选中高亮（保留上次选择的日期，不重置今天）
            dateListManager.setSelectedPosition(currentDateIndex);
            // 刷新对应日期EPG节目单
            if (channelList != null && currentIndex >= 0 && currentIndex < channelList.size()) {
                Channel currentChannel = channelList.get(currentIndex);
                epgManagerWrapper.refresh(currentChannel, channelList, currentDateIndex);
            }
        }
        // 回调通知面板状态变更
        if (visibilityListener != null) {
            visibilityListener.onPanelVisible(isPanelOpen);
        }
    }
}
