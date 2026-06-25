package com.tv.live;

import android.content.Context;
import android.widget.Toast;

import com.tv.live.config.AppConfig;
import com.tv.live.listener.PlayerStateListenerImpl;
import com.tv.live.manager.InfoDisplayManager;
import com.tv.live.TVPlayerManager;

import java.util.List;

/**
 * 频道播放管理器
 * 作用：统一管理频道切换、播放记录、信息更新、自动跳过失效频道等逻辑
 *
 * 【2026-06-25 新增：从 MainActivity 抽离】
 * 【修改说明】
 * 把 MainActivity 里的 playChannel()、playPrev()、playNext() 等方法
 * 和相关变量抽离到这里，统一管理频道播放的逻辑。
 *
 * 【职责分工】
 * - TVPlayerManager：控制播放器本身（播放、暂停、解码器等）
 * - ChannelPlayManager：管理频道切换（切台、记录历史、更新UI、自动跳过失效频道等）
 *
 * 【2026-06-25 合并：AutoSkipManager】
 * 【合并说明】
 * 把 AutoSkipManager 的自动跳过失效频道逻辑合并到这里，减少文件数量。
 * 原来的 AutoSkipManager 是独立的单例，现在直接作为 ChannelPlayManager 的一部分。
 * 自动跳过失效频道本来就是频道播放的附属功能，放在一起更合理。
 *
 * 【为什么合并？】
 * 1. AutoSkipManager 本身就很小，只有几个变量和方法
 * 2. 自动跳过失效频道是频道播放的附属功能，逻辑上紧密相关
 * 3. 减少文件数量，让项目结构更清晰
 */
public class ChannelPlayManager {

    // ====================== 单例模式 ======================
    private static ChannelPlayManager instance;

    private final Context appContext;

    // ====================== 常量 ======================

    // ====================================================================
    // ✅ 2026-06-25 合并：AutoSkipManager - 最大跳过次数
    // ====================================================================
    /**
     * 最大自动跳过次数
     *
     * 【为什么是 10 次？】
     * 10 个频道都失效的概率很低，
     * 超过 10 个说明可能是网络问题，
     * 这时候应该停下来让用户检查，而不是一直切。
     */
    private static final int MAX_CONSECUTIVE_SKIP = 10;

    // ====================== 数据状态 ======================
    private List<Channel> channelSourceList;
    private int currentPlayIndex = 0;

    // ====================== 依赖的管理器 ======================
    private TVPlayerManager playerManager;
    private InfoDisplayManager infoDisplayManager;
    private AppConfig appConfig;
    private PlayerStateListenerImpl playerStateListener;

    // ✅ 2026-06-25 合并：去掉 autoSkipManager 变量，直接用自己的方法
    // private AutoSkipManager autoSkipManager;

    private PictureInPictureManager pipManager;

    // ====================== 回调监听器 ======================
    private OnChannelPlayListener listener;

    // ====================================================================
    // ✅ 2026-06-25 合并：AutoSkipManager - 自动跳过相关成员变量
    // ====================================================================
    /**
     * 连续失效频道计数
     *
     * 【作用】
     * 记录连续遇到多少个失效频道，
     * 超过 MAX_CONSECUTIVE_SKIP 就停止自动跳过。
     *
     * 【什么时候重置？】
     * 播放成功时调用 resetAutoSkip() 重置为 0。
     */
    private int consecutiveFailedCount = 0;

    /**
     * 自动跳过监听器
     *
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     */
    private OnAutoSkipListener autoSkipListener;

    // ====================== 构造函数 ======================
    private ChannelPlayManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized ChannelPlayManager getInstance(Context context) {
        if (instance == null) {
            instance = new ChannelPlayManager(context);
        }
        return instance;
    }

    // ====================== 接口定义 ======================

    /**
     * 频道播放监听器
     */
    public interface OnChannelPlayListener {
        void onChannelChanged(Channel channel, int index);
    }

    // ====================================================================
    // ✅ 2026-06-25 合并：AutoSkipManager - 自动跳过监听器接口
    // ====================================================================
    /**
     * 自动跳过监听器
     *
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     * 【作用】
     * 当需要自动跳下一个频道时，回调给外部去处理。
     */
    public interface OnAutoSkipListener {
        /**
         * 跳下一个频道
         */
        void onSkipNext();
    }

    // ====================== Setter 方法 ======================

    public void setOnChannelPlayListener(OnChannelPlayListener listener) {
        this.listener = listener;
    }

    public void setChannelSourceList(List<Channel> list) {
        this.channelSourceList = list;
    }

    public List<Channel> getChannelSourceList() {
        return channelSourceList;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public Channel getCurrentChannel() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return null;
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) return null;
        return channelSourceList.get(currentPlayIndex);
    }

    public void setPlayerManager(TVPlayerManager manager) {
        this.playerManager = manager;
    }

    public void setInfoDisplayManager(InfoDisplayManager manager) {
        this.infoDisplayManager = manager;
    }

    public void setAppConfig(AppConfig config) {
        this.appConfig = config;
    }

    public void setPlayerStateListener(PlayerStateListenerImpl listener) {
        this.playerStateListener = listener;
    }

    // ====================================================================
    // ✅ 2026-06-25 合并：保留 setAutoSkipManager 方法用于向后兼容
    // ====================================================================
    /**
     * 【2026-06-25 合并：保留用于向后兼容】
     * 现在 AutoSkipManager 已经合并到 ChannelPlayManager 里了，
     * 这个方法主要是为了不让旧代码报错。
     *
     * @deprecated 已合并到 ChannelPlayManager，直接调用本类的方法即可
     */
    @Deprecated
    public void setAutoSkipManager(Object manager) {
        // 空实现，向后兼容
        SettingsActivity.logOperation("【兼容】setAutoSkipManager 已废弃，AutoSkipManager 已合并到 ChannelPlayManager");
    }

    public void setPipManager(PictureInPictureManager manager) {
        this.pipManager = manager;
    }

    // ====================================================================
    // ✅ 2026-06-25 合并：AutoSkipManager - 自动跳过监听器设置
    // ====================================================================
    /**
     * 设置自动跳过监听器
     *
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     */
    public void setOnAutoSkipListener(OnAutoSkipListener listener) {
        this.autoSkipListener = listener;
    }

    // ====================================================================
    // 1. 频道播放相关（核心）
    // ====================================================================

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        if (index < 0 || index >= channelSourceList.size()) return;
        Channel channel = channelSourceList.get(index);
        playChannel(channel, index);
    }

    /**
     * 播放指定频道
     *
     * 【2026-06-25 修改：合并 AutoSkipManager 后重置跳过计数】
     * 【修改说明】
     * 原来调用 autoSkipManager.reset()，
     * 现在直接调用自己的 resetAutoSkip() 方法。
     */
    public void playChannel(Channel channel, int index) {
        if (channel == null || channel.getPlayUrl() == null) return;
        currentPlayIndex = index;

        SettingsActivity.logOperation("【播放】========================================");
        SettingsActivity.logOperation("【播放】频道名称：" + channel.getName());
        SettingsActivity.logOperation("【播放】播放地址：" + channel.getPlayUrl());
        SettingsActivity.logOperation("【播放】当前索引：" + index);
        SettingsActivity.logOperation("【播放】========================================");

        if (playerStateListener != null && channel.getName() != null) {
            playerStateListener.setCurrentChannelName(channel.getName());
        }
        if (appConfig != null) {
            appConfig.setLastPlayIndex(index);
        }
        if (playerManager != null) {
            playerManager.playUrl(channel.getPlayUrl());
        }

        TVPlayerManager.LiveInfo live = null;
        if (playerManager != null) {
            live = playerManager.getLiveInfo();
        }

        if (infoDisplayManager != null) {
            infoDisplayManager.showInfoBar(channel, live);
            infoDisplayManager.showChannelNum(index + 1);
        }

        try {
            if (appConfig != null && channel.getName() != null) {
                appConfig.addRecentChannel(channel.getName());
            }
        } catch (Exception e) {
        }

        // ✅ 2026-06-25 合并：重置自动跳过计数
        // 原来：if (autoSkipManager != null) { autoSkipManager.reset(); }
        // 现在：直接调用自己的方法
        resetAutoSkip();

        if (pipManager != null && pipManager.isInPipMode() && channel != null) {
            try {
                String bitrate = live != null ? live.bitrate : "";
                String channelName = channel.getName() != null ? channel.getName() : "";
                pipManager.updateChannelInfo(index + 1, channelName, bitrate);
            } catch (Exception e) {
                SettingsActivity.logOperation("【画中画】同步频道信息失败：" + e.getMessage());
            }
        }

        if (listener != null) {
            listener.onChannelChanged(channel, index);
        }
    }

    public void playPrev() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIndex = currentPlayIndex - 1;
        if (newIndex < 0) {
            newIndex = channelSourceList.size() - 1;
        }
        playChannel(newIndex);
    }

    public void playNext() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        int newIndex = currentPlayIndex + 1;
        if (newIndex >= channelSourceList.size()) {
            newIndex = 0;
        }
        playChannel(newIndex);
    }

    public void updateChannelSourceList(List<Channel> list) {
        if (channelSourceList != null) {
            channelSourceList.clear();
            channelSourceList.addAll(list);
        } else {
            channelSourceList = list;
        }
    }

    // ====================================================================
    // 2. ✅ 2026-06-25 合并：AutoSkipManager - 自动跳过相关方法
    // ====================================================================

    /**
     * 处理源失效（自动跳过）
     *
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     * 【工作流程】
     * 1. 连续失效计数 +1
     * 2. 判断是否超过最大跳过次数
     * 3. 没超过 → 回调 onSkipNext，让外部去切下一个频道
     * 4. 超过了 → 弹 Toast 提示，返回 false
     *
     * @param channelName 失效的频道名称
     * @return true=继续跳过下一个，false=已达上限，停止跳过
     */
    public boolean handleSourceFailed(String channelName) {
        consecutiveFailedCount++;

        SettingsActivity.logOperation("【自动切台】频道「" + channelName
                + "」源失效，连续失效第 " + consecutiveFailedCount + " 个");

        // 判断是否超过最大跳过次数
        if (consecutiveFailedCount >= MAX_CONSECUTIVE_SKIP) {
            SettingsActivity.logOperation("【自动切台】已连续跳过 "
                    + MAX_CONSECUTIVE_SKIP + " 个失效频道，停止自动跳过");

            // 弹 Toast 提示用户
            Toast.makeText(appContext, "已跳过 " + MAX_CONSECUTIVE_SKIP
                    + " 个失效频道，请检查直播源", Toast.LENGTH_SHORT).show();

            return false;
        }

        // 没超过上限，回调让外部去切下一个频道
        SettingsActivity.logOperation("【自动切台】自动切换到下一个频道");
        if (autoSkipListener != null) {
            autoSkipListener.onSkipNext();
        }

        return true;
    }

    /**
     * 重置自动跳过计数
     *
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     * 【什么时候调用？】
     * 1. 播放成功时（playChannel 里自动调用）
     * 2. 用户手动切台时（playPrev / playNext / playChannel 里自动调用）
     *
     * 【为什么要重置？】
     * 因为连续失效计数是针对"一次连续切台"的，
     * 播放成功了或者用户手动切台了，就应该重新计数。
     */
    public void resetAutoSkip() {
        consecutiveFailedCount = 0;
    }

    /**
     * 获取当前连续失效次数
     *
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     */
    public int getConsecutiveFailedCount() {
        return consecutiveFailedCount;
    }

    /**
     * 获取最大自动跳过次数
     *
     * 【2026-06-25 合并：从 AutoSkipManager 移过来】
     */
    public int getMaxConsecutiveSkip() {
        return MAX_CONSECUTIVE_SKIP;
    }

    // ====================================================================
    // 3. 资源释放
    // ====================================================================

    public void release() {
        listener = null;
        playerManager = null;
        infoDisplayManager = null;
        appConfig = null;
        playerStateListener = null;

        // ✅ 2026-06-25 合并：去掉 autoSkipManager，释放自己的变量
        // autoSkipManager = null;
        autoSkipListener = null;
        consecutiveFailedCount = 0;

        pipManager = null;
        if (channelSourceList != null) {
            channelSourceList.clear();
            channelSourceList = null;
        }
    }
}
