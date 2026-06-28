package com.tv.live.manager;
import android.content.Context;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.List;
import com.tv.live.Channel;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;

/**
 * 面板管理类【内存泄漏修复完整版】
 * 控制左侧频道面板、节目单的显示与隐藏
 * 优化点：打开面板保留上次选中的日期，不强制重置为今天
 */
public class PanelManager {
    // 弱引用上下文
    private final WeakReference<Context> ctxRef;
    // 面板根布局
    private final View panelLayout;
    // 子管理器
    private final ChannelListManager channelListManager;
    private final EpgManagerWrapper epgManagerWrapper;
    // 缓存频道列表引用
    private List<Channel> cacheChannelList;
    // 当前选中日期索引
    private int currentDateIndex = 0;

    // 改造构造：传入Context，包装WeakReference
    public PanelManager(Context context, View panelLayout,
                        ChannelListManager channelListManager,
                        EpgManagerWrapper epgManagerWrapper) {
        this.ctxRef = new WeakReference<>(context.getApplicationContext());
        this.panelLayout = panelLayout;
        this.channelListManager = channelListManager;
        this.epgManagerWrapper = epgManagerWrapper;
    }

    // 安全获取上下文
    private Context getCtx() {
        return ctx != null ? ctxRef.get() : null;
    }

    public void setCurrentDateIndex(int dateIndex) {
        this.currentDateIndex = dateIndex;
    }

    /**
     * 开关面板：显示 / 隐藏
     * @param channelList 频道列表
     * @param currentIndex 当前播放的频道下标
     * @param dateListManager 日期列表管理器，用于同步选中高亮
     */
    public void toggle(List<Channel> channelList, int currentIndex, DateListManager dateListManager) {
        this.cacheChannelList = channelList;
        if (panelLayout.getVisibility() == View.VISIBLE) {
            panelLayout.setVisibility(View.GONE);
        } else {
            panelLayout.setVisibility(View.VISIBLE);
            // 同步日期高亮
            dateListManager.setSelectedPosition(currentDateIndex);
            if (channelList != null && currentIndex >= 0 && currentIndex < channelList.size()) {
                Channel currentChannel = channelList.get(currentIndex);
                epgManagerWrapper.refresh(currentChannel, channelList, currentDateIndex);
            }
        }
    }

    // ========== 标准规范 release() 完整资源释放 ==========
    public void release() {
        // 1. 清空面板View所有监听（本类无自定义监听，解绑基础点击）
        if (panelLayout != null) {
            panelLayout.setOnClickListener(null);
        }

        // 2. 联动释放子管理器资源
        if (channelListManager != null) {
            channelListManager.release();
        }
        if (epgManagerWrapper != null) {
            epgManager.release();
        }

        // 3. 清空频道列表缓存
        if (cacheChannelList != null) {
            cacheChannelList.clear();
            cacheChannelList = null;
        }

        // 4. 清空上下文弱引用
        if (ctxRef != null) {
            ctxRef.clear();
        }

        // 5. 重置状态变量
        currentDateIndex = 0;
    }
}
