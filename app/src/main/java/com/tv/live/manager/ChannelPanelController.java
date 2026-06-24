package com.tv.live.manager;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.SettingsActivity;
import com.tv.live.config.AppConfig;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 频道面板控制器
 *
 * 【职责】
 * 统一管理所有和频道面板相关的逻辑，包括：
 * 1. 分组管理（分组列表、选中状态、分组筛选）
 * 2. 频道切换（上/下切台、分组内循环、防抖、反转）
 * 3. 面板控制（显示/隐藏、EPG 展开/收起、列表点击）
 * 4. 焦点管理（手机触屏 + 电视遥控器）
 * 5. 按键处理（左右键移动焦点、OK键选中、菜单键收藏）
 *
 * 【2026-06-21 新增：收藏 + 最近观看 + 菜单键】
 * 【功能说明】
 * 1. 分组列表增加「收藏」和「最近观看」两个特殊分组
 * 2. 菜单键（KEYCODE_MENU）可以快速收藏/取消收藏当前频道
 * 3. 切换频道时自动添加到最近观看
 *
 * 【2026-06-21 新增：长按收藏（触屏模式）】
 * 【功能说明】
 * 触屏模式下，长按频道项可以收藏/取消收藏该频道。
 *
 * 【2026-06-21 新增：排查日志】
 * 【说明】
 * 在关键位置加上日志，方便排查收藏和最近观看功能的问题。
 *
 * 【2026-06-21 新增：调试日志 - 频道名对比】
 * 【说明】
 * 加上详细的频道名对比日志，找出为什么匹配不上。
 *
 * 【2026-06-24 修改：集成 PanelCursorManager 光标管理器】
 * 【修改说明】
 * 用自定义的 PanelCursorManager 替代 Android 原生的 focus 机制，
 * 统一管理所有焦点状态和按键处理，更稳定、更可控。
 * 
 * 【修改点】
 * 1. 新增 cursorManager 成员变量
 * 2. 构造函数中初始化光标管理器（initCursorManager）
 * 3. dispatchKeyEvent 改用 cursorManager 处理
 * 4. 移除 initFocusListeners 方法（不再需要原生焦点监听）
 * 5. togglePanel / onEpgButtonClicked / onBackGroupClicked 中同步光标状态
 * 6. setChannels / onGroupClicked / playChannel 中同步选中位置
 * 7. 新增 updateChannelListByGroup 辅助方法
 * 8. 新增 updateFocusStyle 辅助方法
 * 9. 新增 handleCursorConfirm 辅助方法
 */
public class ChannelPanelController {
    // ====================== 常量 ======================
    /** 频道切换冷却时间（毫秒），300ms 内不允许连续切台 */
    private static final long CHANNEL_COOLDOWN = 300;

    // ====================== 上下文与视图 ======================
    private Context context;
    private View panelLayout;
    private ListView lvGroup;
    private ListView lvChannelList;
    private ListView lvChannelListEpg;
    private ListView lvDate;
    private ListView lvEpg;
    private TextView btnShowEpg;
    private TextView btnBackGroup;

    // ====================== 左右面板切换 ======================
    private View llLeftPanel;
    private View llRightPanel;
    private boolean rightPanelOpen = false;

    // ====================== 子管理器 ======================
    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private ChannelListManager channelListManagerEpg;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PanelManager panelManager;

    // ====================================================================
    // ✅ 2026-06-24 新增：光标管理器
    // ====================================================================
    /**
     * 自定义光标管理器
     * 
     * 【作用】
     * 统一管理左右面板的焦点位置和按键处理，
     * 替代 Android 原生的 focus 机制，更稳定可控。
     * 
     * 【为什么不用原生 focus？】
     * 1. 原生 focus 在复杂布局下容易乱跑
     * 2. 边界处理不灵活（不能循环）
     * 3. 状态分散，不好维护
     */
    private PanelCursorManager cursorManager;

    // ====================== 数据状态 ======================
    private List<Channel> channelSourceList = new ArrayList<>();
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    private String currentGroupName = "";
    private int currentPlayIndex = 0;
    private int currentSelectedDateIndex = 0;

    // ====================== 面板状态 ======================
    private boolean epgPanelOpen = false;
    private boolean epgEnable = true;

    // ====================================================================
    // 换台反转相关
    // ====================================================================
    /**
     * 是否开启换台反转
     * 默认 false = 不反转
     */
    private boolean isReverse = false;

    /**
     * 设置是否开启换台反转
     */
    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
        SettingsActivity.logOperation("【设置】反转状态同步到 ChannelPanelController：" 
                + (reverse ? "开启" : "关闭"));
    }

    /**
     * 获取当前反转状态
     */
    public boolean isReverse() {
        return isReverse;
    }

    // ====================== 切台防抖 ======================
    private long lastChannelChangeTime = 0;

    // ====================== 回调监听器 ======================
    private OnChannelChangeListener channelChangeListener;
    private OnPanelStateListener panelStateListener;

    // ====================== 接口定义 ======================
    public interface OnChannelChangeListener {
        void onChannelChanged(Channel channel, int index);
    }

    public interface OnPanelStateListener {
        void onPanelStateChanged(boolean isOpen);
    }

    // ====================== 构造函数 ======================
    public ChannelPanelController(
            Context context,
            View panelLayout,
            View llLeftPanel,
            View llRightPanel,
            ListView lvGroup,
            ListView lvChannelList,
            ListView lvChannelListEpg,
            ListView lvDate,
            ListView lvEpg,
            TextView btnShowEpg,
            TextView btnBackGroup,
            GroupListManager groupListManager,
            ChannelListManager channelListManager,
            ChannelListManager channelListManagerEpg,
            DateListManager dateListManager,
            EpgManagerWrapper epgManagerWrapper,
            PanelManager panelManager
    ) {
        this.context = context.getApplicationContext();
        this.panelLayout = panelLayout;
        this.llLeftPanel = llLeftPanel;
        this.llRightPanel = llRightPanel;
        this.lvGroup = lvGroup;
        this.lvChannelList = lvChannelList;
        this.lvChannelListEpg = lvChannelListEpg;
        this.lvDate = lvDate;
        this.lvEpg = lvEpg;
        this.btnShowEpg = btnShowEpg;
        this.btnBackGroup = btnBackGroup;
        this.groupListManager = groupListManager;
        this.channelListManager = channelListManager;
        this.channelListManagerEpg = channelListManagerEpg;
        this.dateListManager = dateListManager;
        this.epgManagerWrapper = epgManagerWrapper;
        this.panelManager = panelManager;

        initClickListeners();

        // ====================================================================
        // ✅ 2026-06-24 新增：初始化光标管理器
        // ====================================================================
        initCursorManager();
    }

    // ====================================================================
    // 1. 初始化点击事件
    // ====================================================================
    private void initClickListeners() {
        lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onGroupClicked(position);
            }
        });

        lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });

        lvChannelListEpg.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onChannelClicked(pos);
            }
        });

        // ✅ 2026-06-21 新增：长按收藏（左侧频道列表）【加了日志】
        channelListManager.setOnChannelLongClickListener(new ChannelListManager.OnChannelLongClickListener() {
            @Override
            public boolean onChannelLongClick(String channelName, int position) {
                // ✅ 日志：确认回调触发了
                SettingsActivity.logOperation("【面板】左侧长按回调触发，channelName=" + channelName);
                return handleChannelLongClick(channelName, false);
            }
        });

        // ✅ 2026-06-21 新增：长按收藏（右侧节目单页面的频道列表）【加了日志】
        channelListManagerEpg.setOnChannelLongClickListener(new ChannelListManager.OnChannelLongClickListener() {
            @Override
            public boolean onChannelLongClick(String channelName, int position) {
                // ✅ 日志：确认回调触发了
                SettingsActivity.logOperation("【面板】右侧长按回调触发，channelName=" + channelName);
                return handleChannelLongClick(channelName, true);
            }
        });

        btnShowEpg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onEpgButtonClicked();
            }
        });

        btnBackGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackGroupClicked();
            }
        });
    }

    // ====================================================================
    // ✅ 2026-06-24 新增：初始化光标管理器
    // ====================================================================
    /**
     * 初始化光标管理器
     * 
     * 【作用】
     * 创建 PanelCursorManager 实例，设置数据数量和监听器，
     * 统一管理所有焦点状态和按键处理。
     * 
     * 【监听器说明】
     * - onPanelChanged：面板切换（左 ↔ 右）→ 更新焦点样式
     * - onLeftFocusViewChanged：左面板焦点区域变化 → 更新焦点样式
     * - onRightFocusViewChanged：右面板焦点区域变化 → 更新焦点样式
     * - onGroupSelectionChanged：分组选中位置变化 → 更新 GroupListManager + 切换分组
     * - onLeftChannelSelectionChanged：左面板频道选中变化 → 更新 ChannelListManager
     * - onRightChannelSelectionChanged：右面板频道选中变化 → 更新 ChannelListManagerEpg + 刷新EPG
     * - onDateSelectionChanged：日期选中变化 → 切换日期
     * - onEpgSelectionChanged：EPG选中变化 → 滚动列表
     * - onConfirm：确认键 → 处理确认逻辑
     */
    private void initCursorManager() {
        cursorManager = new PanelCursorManager();

        // 启用循环模式（到顶后跳到最后，到底后跳到最前）
        cursorManager.setEnableCycle(true);

        // 设置光标变化监听器
        cursorManager.setOnCursorChangeListener(new PanelCursorManager.OnCursorChangeListener() {

            @Override
            public void onPanelChanged(PanelCursorManager.PanelType from, PanelCursorManager.PanelType to) {
                // 面板切换（左 ↔ 右）
                SettingsActivity.logOperation("【光标】面板切换：" + from + " → " + to);
                updateFocusStyle();
            }

            @Override
            public void onLeftFocusViewChanged(PanelCursorManager.LeftFocusView from, PanelCursorManager.LeftFocusView to) {
                // 左面板焦点区域变化
                SettingsActivity.logOperation("【光标】左面板焦点切换：" + from + " → " + to);
                updateFocusStyle();
            }

            @Override
            public void onRightFocusViewChanged(PanelCursorManager.RightFocusView from, PanelCursorManager.RightFocusView to) {
                // 右面板焦点区域变化
                SettingsActivity.logOperation("【光标】右面板焦点切换：" + from + " → " + to);
                updateFocusStyle();
            }

            @Override
            public void onGroupSelectionChanged(int position, boolean isSmooth) {
                // 分组列表选中位置变化
                groupListManager.setSelectedPosition(position);

                // 平滑滚动到选中位置
                if (isSmooth) {
                    lvGroup.smoothScrollToPositionFromTop(position, 
                            lvGroup.getHeight() / 2, 200);
                } else {
                    lvGroup.setSelection(position);
                }

                // 切换分组，更新右面板数据
                String groupName = groupListManager.getCurrentGroup(position);
                updateChannelListByGroup(groupName, position);
            }

            @Override
            public void onLeftChannelSelectionChanged(int position, boolean isSmooth) {
                // 左面板频道列表选中位置变化
                channelListManager.setSelectedPosition(position);

                // 平滑滚动到选中位置
                if (isSmooth) {
                    lvChannelList.smoothScrollToPositionFromTop(position, 
                            lvChannelList.getHeight() / 2, 200);
                } else {
                    lvChannelList.setSelection(position);
                }
            }

            @Override
            public void onRightChannelSelectionChanged(int position, boolean isSmooth) {
                // 右面板频道列表选中位置变化
                channelListManagerEpg.setSelectedPosition(position);

                // 平滑滚动到选中位置
                if (isSmooth) {
                    lvChannelListEpg.smoothScrollToPositionFromTop(position, 
                            lvChannelListEpg.getHeight() / 2, 200);
                } else {
                    lvChannelListEpg.setSelection(position);
                }

                // 切换频道时刷新 EPG
                if (position >= 0 && position < channelSourceList.size()) {
                    Channel ch = channelSourceList.get(position);
                    epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
                }
            }

            @Override
            public void onDateSelectionChanged(int position, boolean isSmooth) {
                // 日期列表选中位置变化
                setCurrentDateIndex(position);
            }

            @Override
            public void onEpgSelectionChanged(int position, boolean isSmooth) {
                // EPG列表选中位置变化
                // （EPG列表的选中逻辑比较简单，暂时只滚动）
                if (isSmooth) {
                    lvEpg.smoothScrollToPositionFromTop(position, 
                            lvEpg.getHeight() / 2, 200);
                } else {
                    lvEpg.setSelection(position);
                }
            }

            @Override
            public void onConfirm() {
                // 确认键
                handleCursorConfirm();
            }
        });

        SettingsActivity.logOperation("【光标】光标管理器初始化完成");
    }

    // ====================================================================
    // ✅ 2026-06-24 新增：更新焦点样式
    // ====================================================================
    /**
     * 根据光标管理器的状态，更新各个 View 的焦点样式
     * 
     * 【作用】
     * 当焦点区域变化时，更新对应 View 的选中/焦点样式，
     * 让用户知道当前光标在哪里。
     * 
     * 【样式说明】
     * - 有焦点的区域：高亮显示（用 setSelected 标记）
     * - 无焦点的区域：正常显示
     * 
     * 【注意】
     * GroupListManager 和 ChannelListManager 内部已经处理了选中样式，
     * 这里主要处理按钮（btnShowEpg、btnBackGroup）的焦点样式。
     * 如果需要更精细的列表项焦点样式，可以在对应的 Manager 中扩展。
     */
    private void updateFocusStyle() {
        // 判断当前在哪个面板
        boolean isLeftPanel = (cursorManager.getCurrentPanel() == PanelCursorManager.PanelType.LEFT);
        boolean isRightPanel = (cursorManager.getCurrentPanel() == PanelCursorManager.PanelType.RIGHT);

        if (isLeftPanel) {
            // 左面板：判断焦点在哪个区域
            PanelCursorManager.LeftFocusView focusView = cursorManager.getLeftFocusView();

            // EPG按钮：有焦点时高亮
            boolean epgBtnFocused = (focusView == PanelCursorManager.LeftFocusView.EPG_BTN);
            btnShowEpg.setSelected(epgBtnFocused);

        } else if (isRightPanel) {
            // 右面板：判断焦点在哪个区域
            PanelCursorManager.RightFocusView focusView = cursorManager.getRightFocusView();

            // 返回按钮：有焦点时高亮
            boolean backBtnFocused = (focusView == PanelCursorManager.RightFocusView.BACK_BTN);
            btnBackGroup.setSelected(backBtnFocused);
        }
    }

    // ====================================================================
    // ✅ 2026-06-24 新增：处理光标确认键
    // ====================================================================
    /**
     * 处理光标管理器的确认键事件
     * 
     * 【作用】
     * 根据当前焦点所在的区域，执行对应的确认操作。
     * 
     * 【确认逻辑】
     * 左面板：
     * - GROUP：切换分组（和选中效果一样）
     * - CHANNEL：播放该频道
     * - EPG_BTN：展开/收起节目单
     * 
     * 右面板：
     * - BACK_BTN：返回分组
     * - CHANNEL：播放该频道
     * - DATE：切换日期
     * - EPG：（暂不处理）
     */
    private void handleCursorConfirm() {
        boolean isLeftPanel = (cursorManager.getCurrentPanel() == PanelCursorManager.PanelType.LEFT);

        if (isLeftPanel) {
            // 左面板
            PanelCursorManager.LeftFocusView focusView = cursorManager.getLeftFocusView();

            switch (focusView) {
                case GROUP:
                    // 分组列表确认：切换分组
                    int groupPos = cursorManager.getGroupSelectedPosition();
                    onGroupClicked(groupPos);
                    break;

                case CHANNEL:
                    // 频道列表确认：播放该频道
                    int channelPos = cursorManager.getLeftChannelSelectedPosition();
                    onChannelClicked(channelPos);
                    break;

                case EPG_BTN:
                    // EPG按钮确认：展开/收起节目单
                    onEpgButtonClicked();
                    break;
            }

        } else {
            // 右面板
            PanelCursorManager.RightFocusView focusView = cursorManager.getRightFocusView();

            switch (focusView) {
                case BACK_BTN:
                    // 返回按钮确认：返回分组
                    onBackGroupClicked();
                    break;

                case CHANNEL:
                    // 频道列表确认：播放该频道
                    int channelPos = cursorManager.getRightChannelSelectedPosition();
                    onChannelClicked(channelPos);
                    break;

                case DATE:
                    // 日期列表确认：切换日期
                    int datePos = cursorManager.getDateSelectedPosition();
                    setCurrentDateIndex(datePos);
                    break;

                case EPG:
                    // EPG列表确认：（暂不处理）
                    break;
            }
        }
    }

    // ====================================================================
    // 2. 分组管理相关
    // ====================================================================

    /**
     * 设置频道列表
     *
     * 【2026-06-21 修改：初始化时获取收藏和最近观看数量】
     * 
     * 【2026-06-24 修改：同步数据数量到光标管理器】
     */
    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;

        // ✅ 新增：获取收藏和最近观看的数量
        int favoriteCount = 0;
        int recentCount = 0;
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();

            // 计算实际存在的频道数量
            for (String name : favorites) {
                for (Channel c : channels) {
                    if (name.equals(c.getName())) {
                        favoriteCount++;
                        break;
                    }
                }
            }
            for (String name : recent) {
                for (Channel c : channels) {
                    if (name.equals(c.getName())) {
                        recentCount++;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }

        // ✅ 修改：传入收藏和最近观看数量
        groupListManager.setGroups(channels, favoriteCount, recentCount);
        channelListManager.setChannels(channels, currentPlayIndex);
        channelListManagerEpg.setChannels(channels, currentPlayIndex);

        // ====================================================================
        // ✅ 2026-06-24 新增：同步数据数量到光标管理器
        // ====================================================================
        if (cursorManager != null) {
            // 分组数量 = 特殊分组（3个） + 实际分组数量
            int groupCount = 3; // 全部、收藏、最近观看
            try {
                // 计算实际分组数量
                Set<String> groupSet = new LinkedHashSet<>();
                for (Channel c : channels) {
                    groupSet.add(c.getGroup());
                }
                groupCount += groupSet.size();
            } catch (Exception e) {
                // 忽略
            }
            cursorManager.setGroupCount(groupCount);

            // 左面板频道数量
            cursorManager.setLeftChannelCount(channels.size());
            cursorManager.setLeftChannelSelectedPosition(
                    Math.min(currentPlayIndex, channels.size() - 1), false);

            // 右面板频道数量
            cursorManager.setRightChannelCount(channels.size());
            cursorManager.setRightChannelSelectedPosition(
                    Math.min(currentPlayIndex, channels.size() - 1), false);
        }
    }

    /**
     * 分组被点击了
     *
     * 【2026-06-21 修改：支持「全部」「收藏」「最近观看」三个特殊分组】
     */
    private void onGroupClicked(int position) {
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);

        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;

        updateChannelListByGroup(groupName, position);

        // ====================================================================
        // ✅ 2026-06-24 新增：同步光标管理器的分组选中位置
        // ====================================================================
        if (cursorManager != null) {
            cursorManager.setGroupSelectedPosition(position, false);
        }
    }

    // ====================================================================
    // ✅ 2026-06-24 新增：根据分组更新频道列表（从 onGroupClicked 抽取）
    // ====================================================================
    /**
     * 根据分组名称更新频道列表
     * 
     * @param groupName 分组名称
     * @param groupPosition 分组位置
     * 
     * 【说明】
     * 从 onGroupClicked 中抽取出来，方便光标管理器切换分组时调用。
     * 处理「全部」「收藏」「最近观看」和普通分组四种情况。
     */
    private void updateChannelListByGroup(String groupName, int groupPosition) {
        if (GroupListManager.GROUP_ALL.equals(groupName)) {
            // 「全部」分组：显示所有频道
            currentGroupChannelList.clear();
            currentGroupChannelList.addAll(channelSourceList);
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
            SettingsActivity.logOperation("【分组】选中「全部」，频道数：" + channelSourceList.size());

            // ✅ 同步光标管理器的左面板频道数量
            if (cursorManager != null) {
                cursorManager.setLeftChannelCount(channelSourceList.size());
                cursorManager.setLeftChannelSelectedPosition(
                        Math.min(currentPlayIndex, channelSourceList.size() - 1), false);
            }

        } else if (GroupListManager.GROUP_FAVORITE.equals(groupName)) {
            // ✅ 新增：「收藏」分组
            currentGroupChannelList.clear();
            try {
                AppConfig appConfig = AppConfig.getInstance(context);
                List<String> favorites = appConfig.getFavoriteChannels();
                for (String name : favorites) {
                    for (Channel c : channelSourceList) {
                        if (name.equals(c.getName())) {
                            currentGroupChannelList.add(c);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略
            }

            // 用筛选后的列表刷新
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
            SettingsActivity.logOperation("【分组】选中「收藏」，频道数：" + currentGroupChannelList.size());

            // ✅ 同步光标管理器的左面板频道数量
            if (cursorManager != null) {
                cursorManager.setLeftChannelCount(currentGroupChannelList.size());
                cursorManager.setLeftChannelSelectedPosition(0, false);
            }

        } else if (GroupListManager.GROUP_RECENT.equals(groupName)) {
            // ✅ 新增：「最近观看」分组
            currentGroupChannelList.clear();
            try {
                AppConfig appConfig = AppConfig.getInstance(context);
                List<String> recent = appConfig.getRecentChannels();
                for (String name : recent) {
                    for (Channel c : channelSourceList) {
                        if (name.equals(c.getName())) {
                            currentGroupChannelList.add(c);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略
            }

            // 用筛选后的列表刷新
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
            SettingsActivity.logOperation("【分组】选中「最近观看」，频道数：" + currentGroupChannelList.size());

            // ✅ 同步光标管理器的左面板频道数量
            if (cursorManager != null) {
                cursorManager.setLeftChannelCount(currentGroupChannelList.size());
                cursorManager.setLeftChannelSelectedPosition(0, false);
            }

        } else {
            // 普通分组：按分组筛选
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            SettingsActivity.logOperation("【分组】选中分组：" + groupName
                    + "，频道数：" + currentGroupChannelList.size());

            // ✅ 同步光标管理器的左面板频道数量
            if (cursorManager != null) {
                cursorManager.setLeftChannelCount(currentGroupChannelList.size());
                // 找到当前播放频道在分组中的位置
                int playIndexInGroup = 0;
                String currentChannelName = "";
                if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                    currentChannelName = channelSourceList.get(currentPlayIndex).getName();
                }
                for (int i = 0; i < currentGroupChannelList.size(); i++) {
                    if (currentChannelName.equals(currentGroupChannelList.get(i).getName())) {
                        playIndexInGroup = i;
                        break;
                    }
                }
                cursorManager.setLeftChannelSelectedPosition(playIndexInGroup, false);
            }
        }
    }

    public String getCurrentGroupName() {
        return currentGroupName;
    }

    public List<Channel> getCurrentGroupChannels() {
        return currentGroupChannelList;
    }

    public void setEpgEnable(boolean enable) {
        this.epgEnable = enable;
    }

    // ====================================================================
    // 3. 频道切换相关（核心）
    // ====================================================================
    /**
     * 播放上一个频道（分组内循环）- 底层方法
     */
    public void playPrev() {
        // 防抖检查
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            SettingsActivity.logOperation("【切台】playPrev 防抖拦截，距离上次：" 
                    + (now - lastChannelChangeTime) + "ms");
            return;
        }
        lastChannelChangeTime = now;

        if (channelSourceList == null || channelSourceList.isEmpty()) {
            SettingsActivity.logOperation("【切台】playPrev 失败：频道列表为空");
            return;
        }

        // 获取当前频道和分组
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();

        // 筛选当前分组的频道
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }

        if (groupChannels.size() <= 1) {
            SettingsActivity.logOperation("【切台】playPrev 失败：分组内只有1个频道");
            return;
        }

        // 找到当前频道在分组中的索引
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;

        // 计算上一个频道的索引（分组内循环）
        int prevGroupIndex = (groupIndex - 1 + groupChannels.size()) % groupChannels.size();
        Channel prevChannel = groupChannels.get(prevGroupIndex);

        int globalIndex = channelSourceList.indexOf(prevChannel);
        if (globalIndex != -1) {
            SettingsActivity.logOperation("【切台】playPrev 上一台 → " 
                    + currentPlayIndex + " → " + globalIndex 
                    + "（" + prevChannel.getName() + "）");
            playChannel(globalIndex);
        }
    }

    /**
     * 播放下一个频道（分组内循环）- 底层方法
     */
    public void playNext() {
        // 防抖检查
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            SettingsActivity.logOperation("【切台】playNext 防抖拦截，距离上次：" 
                    + (now - lastChannelChangeTime) + "ms");
            return;
        }
        lastChannelChangeTime = now;

        if (channelSourceList == null || channelSourceList.isEmpty()) {
            SettingsActivity.logOperation("【切台】playNext 失败：频道列表为空");
            return;
        }

        // 获取当前频道和分组
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();

        // 筛选当前分组的频道
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }

        if (groupChannels.size() <= 1) {
            SettingsActivity.logOperation("【切台】playNext 失败：分组内只有1个频道");
            return;
        }

        // 找到当前频道在分组中的索引
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;

        // 计算下一个频道的索引（分组内循环）
        int nextGroupIndex = (groupIndex + 1) % groupChannels.size();
        Channel nextChannel = groupChannels.get(nextGroupIndex);

        int globalIndex = channelSourceList.indexOf(nextChannel);
        if (globalIndex != -1) {
            SettingsActivity.logOperation("【切台】playNext 下一台 → " 
                    + currentPlayIndex + " → " + globalIndex 
                    + "（" + nextChannel.getName() + "）");
            playChannel(globalIndex);
        }
    }

    // ====================================================================
    // 带反转的切台方法（统一入口）
    // ====================================================================
    /**
     * 按上键时调用（自动考虑反转）
     */
    public void switchUp() {
        SettingsActivity.logOperation("【切台】switchUp 上键 → 反转状态：" 
                + (isReverse ? "开启" : "关闭") 
                + " → 实际方向：" + (isReverse ? "下一台" : "上一台"));
        
        if (isReverse) {
            // 反转开启：上键 = 下一台
            playNext();
        } else {
            // 反转关闭：上键 = 上一台
            playPrev();
        }
    }

    /**
     * 按下键时调用（自动考虑反转）
     */
    public void switchDown() {
        SettingsActivity.logOperation("【切台】switchDown 下键 → 反转状态：" 
                + (isReverse ? "开启" : "关闭") 
                + " → 实际方向：" + (isReverse ? "上一台" : "下一台"));
        
        if (isReverse) {
            // 反转开启：下键 = 上一台
            playPrev();
        } else {
            // 反转关闭：下键 = 下一台
            playNext();
        }
    }

    /**
     * 播放指定索引的频道
     *
     * 【2026-06-21 修改：同步分组时处理特殊分组的情况】
     * 
     * 【2026-06-24 修改：同步光标管理器的选中位置】
     */
    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null) return;

        // 切换频道后同步分组选中状态
        String channelGroup = ch.getGroup();
        if (channelGroup != null && !channelGroup.isEmpty()) {
            // 如果当前是特殊分组（全部/收藏/最近观看），不用切换分组
            boolean isSpecialGroup = GroupListManager.GROUP_ALL.equals(currentGroupName)
                    || GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                    || GroupListManager.GROUP_RECENT.equals(currentGroupName)
                    || currentGroupName.isEmpty();
            if (!isSpecialGroup && !channelGroup.equals(currentGroupName)) {
                // 不是特殊分组且分组不一致 → 同步切换分组
                currentGroupName = channelGroup;
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (channelGroup.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                int groupPos = groupListManager.getGroupPosition(channelGroup);
                groupListManager.setSelectedPosition(groupPos);

                // ✅ 同步光标管理器的分组选中位置
                if (cursorManager != null) {
                    cursorManager.setGroupSelectedPosition(groupPos, false);
                }
            }
        }

        // 更新主页面频道列表的选中状态
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) 
                || currentGroupName.isEmpty() 
                || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, index);

            // ✅ 同步光标管理器的左面板频道选中位置
            if (cursorManager != null) {
                cursorManager.setLeftChannelSelectedPosition(index, false);
            }

        } else if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                || GroupListManager.GROUP_RECENT.equals(currentGroupName)) {
            // ✅ 新增：特殊分组用筛选后的列表
            channelListManager.setFilteredChannels(currentGroupChannelList, ch.getName());

            // ✅ 同步光标管理器的左面板频道选中位置
            if (cursorManager != null) {
                // 找到当前频道在筛选列表中的位置
                int posInFiltered = 0;
                for (int i = 0; i < currentGroupChannelList.size(); i++) {
                    if (ch.getName().equals(currentGroupChannelList.get(i).getName())) {
                        posInFiltered = i;
                        break;
                    }
                }
                cursorManager.setLeftChannelSelectedPosition(posInFiltered, false);
            }

        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);

            // ✅ 同步光标管理器的左面板频道选中位置
            if (cursorManager != null) {
                // 找到当前频道在分组中的位置
                int posInGroup = 0;
                for (int i = 0; i < currentGroupChannelList.size(); i++) {
                    if (ch.getName().equals(currentGroupChannelList.get(i).getName())) {
                        posInGroup = i;
                        break;
                    }
                }
                cursorManager.setLeftChannelSelectedPosition(posInGroup, false);
            }
        }

        // 同步更新节目单页面的频道列表选中状态
        channelListManagerEpg.setChannels(channelSourceList, index);

        // ✅ 同步光标管理器的右面板频道选中位置
        if (cursorManager != null) {
            cursorManager.setRightChannelSelectedPosition(index, false);
        }

        // 刷新 EPG
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

        // 回调给外部（MainActivity）去实际播放
        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
        }

        // ✅ 新增：添加到最近观看
        addToRecent(ch.getName());
    }

    // ====================================================================
    // ✅ 2026-06-21 新增：添加到最近观看【加了调试日志】
    // ====================================================================
    /**
     * 添加到最近观看
     */
    private void addToRecent(String channelName) {
        // ✅ 日志 1：确认方法被调用
        SettingsActivity.logOperation("【最近观看】addToRecent 被调用，channelName=" + channelName);
        
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            appConfig.addRecentChannel(channelName);
            
            // ✅ 日志 2：添加成功
            List<String> recent = appConfig.getRecentChannels();
            SettingsActivity.logOperation("【最近观看】添加成功，当前最近观看数量=" + recent.size());
            
            // 更新分组列表的数量
            int favoriteCount = 0;
            int recentCount = 0;
            List<String> favorites = appConfig.getFavoriteChannels();

            // ✅ 新增：调试日志 - 看看为什么匹配不上
            SettingsActivity.logOperation("【最近-调试】recent.size=" + recent.size() 
                    + ", channelSourceList.size=" + channelSourceList.size());
            if (recent.size() > 0 && channelSourceList.size() > 0) {
                String firstRecent = recent.get(0);
                String firstChannel = channelSourceList.get(0).getName();
                SettingsActivity.logOperation("【最近-调试】第一个最近名：[" + firstRecent + "]");
                SettingsActivity.logOperation("【最近-调试】第一个源频道名：[" + firstChannel + "]");
                SettingsActivity.logOperation("【最近-调试】是否相等：" + firstRecent.equals(firstChannel));
                SettingsActivity.logOperation("【最近-调试】最近名长度：" + firstRecent.length() 
                        + ", 源频道名长度：" + firstChannel.length());
            }

            for (String name : favorites) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) {
                        favoriteCount++;
                        break;
                    }
                }
            }
            for (String name : recent) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) {
                        recentCount++;
                        break;
                    }
                }
            }
            groupListManager.updateSpecialGroupCount(favoriteCount, recentCount);
            
            // ✅ 日志 3：数量更新完成
            SettingsActivity.logOperation("【最近观看】分组数量更新完成，收藏=" + favoriteCount 
                    + ", 最近观看=" + recentCount);
            
        } catch (Exception e) {
            // ✅ 日志 4：异常
            SettingsActivity.logOperation("【最近观看】添加失败，异常=" + e.getMessage());
        }
    }

    // ====================================================================
    // ✅ 2026-06-21 新增：长按收藏处理【加了调试日志】
    // ====================================================================
    /**
     * 处理频道长按事件（触屏模式收藏）
     *
     * @param channelName 被长按的频道名称
     * @param isRightPanel 是否是右侧面板
     * @return true 表示消费了事件
     */
    private boolean handleChannelLongClick(String channelName, boolean isRightPanel) {
        // ✅ 日志 1：确认方法被调用
        SettingsActivity.logOperation("【收藏】handleChannelLongClick 被调用，channelName=" 
                + channelName + ", isRightPanel=" + isRightPanel);
        
        if (channelName == null || channelName.isEmpty()) {
            SettingsActivity.logOperation("【收藏】handleChannelLongClick 失败：频道名为空");
            return false;
        }

        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            boolean isFavorite = appConfig.toggleFavorite(channelName);
            
            // ✅ 日志 2：收藏操作结果
            SettingsActivity.logOperation("【收藏】长按操作结果=" + (isFavorite ? "已收藏" : "已取消"));
            
            // 更新分组列表的数量
            int favoriteCount = 0;
            int recentCount = 0;
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();

            // ✅ 新增：调试日志 - 看看为什么匹配不上
            SettingsActivity.logOperation("【收藏-调试】favorites.size=" + favorites.size() 
                    + ", channelSourceList.size=" + channelSourceList.size());
            if (favorites.size() > 0 && channelSourceList.size() > 0) {
                String firstFav = favorites.get(0);
                String firstChannel = channelSourceList.get(0).getName();
                SettingsActivity.logOperation("【收藏-调试】第一个收藏名：[" + firstFav + "]");
                SettingsActivity.logOperation("【收藏-调试】第一个源频道名：[" + firstChannel + "]");
                SettingsActivity.logOperation("【收藏-调试】是否相等：" + firstFav.equals(firstChannel));
                SettingsActivity.logOperation("【收藏-调试】收藏名长度：" + firstFav.length() 
                        + ", 源频道名长度：" + firstChannel.length());
            }

            for (String name : favorites) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) {
                        favoriteCount++;
                        break;
                    }
                }
            }
            for (String name : recent) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) {
                        recentCount++;
                        break;
                    }
                }
            }
            groupListManager.updateSpecialGroupCount(favoriteCount, recentCount);
            
            // ✅ 日志 3：数量更新完成
            SettingsActivity.logOperation("【收藏】分组数量更新完成，收藏=" + favoriteCount 
                    + ", 最近观看=" + recentCount);
            
            // 如果当前在「收藏」分组，刷新列表
            if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)) {
                // 重新筛选收藏列表
                currentGroupChannelList.clear();
                for (String name : favorites) {
                    for (Channel c : channelSourceList) {
                        if (name.equals(c.getName())) {
                            currentGroupChannelList.add(c);
                            break;
                        }
                    }
                }
                if (!isRightPanel) {
                    channelListManager.setFilteredChannels(currentGroupChannelList, channelName);
                } else {
                    channelListManagerEpg.setFilteredChannels(currentGroupChannelList, channelName);
                }
                SettingsActivity.logOperation("【收藏】在收藏分组，已刷新频道列表");

                // ✅ 同步光标管理器的左面板频道数量
                if (cursorManager != null && !isRightPanel) {
                    cursorManager.setLeftChannelCount(currentGroupChannelList.size());
                }
            }

            SettingsActivity.logOperation("【收藏】长按" + (isFavorite ? "添加" : "取消")
                    + "收藏：" + channelName);
            return true;
        } catch (Exception e) {
            // ✅ 日志 4：异常
            SettingsActivity.logOperation("【收藏】长按操作失败，异常=" + e.getMessage());
            return false;
        }
    }

    /**
     * 切换当前频道的收藏状态（菜单键调用）
     *
     * @return 操作后的状态（true=已收藏，false=已取消）
     */
    public boolean toggleCurrentFavorite() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return false;
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) return false;
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        if (currentChannel == null) return false;

        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            boolean isFavorite = appConfig.toggleFavorite(currentChannel.getName());

            // 更新分组列表的数量
            int favoriteCount = 0;
            int recentCount = 0;
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();

            for (String name : favorites) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) {
                        favoriteCount++;
                        break;
                    }
                }
            }
            for (String name : recent) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) {
                        recentCount++;
                        break;
                    }
                }
            }
            groupListManager.updateSpecialGroupCount(favoriteCount, recentCount);

            // 如果当前在「收藏」分组，刷新列表
            if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)) {
                // 重新筛选收藏列表
                currentGroupChannelList.clear();
                for (String name : favorites) {
                    for (Channel c : channelSourceList) {
                        if (name.equals(c.getName())) {
                            currentGroupChannelList.add(c);
                            break;
                        }
                    }
                }
                channelListManager.setFilteredChannels(currentGroupChannelList, currentChannel.getName());
            }

            SettingsActivity.logOperation("【收藏】" + (isFavorite ? "添加" : "取消") 
                    + "收藏：" + currentChannel.getName());
            return isFavorite;
        } catch (Exception e) {
            SettingsActivity.logOperation("【收藏】操作失败：" + e.getMessage());
            return false;
        }
    }

    private void onChannelClicked(int position) {
        if (!currentGroupChannelList.isEmpty() && position < currentGroupChannelList.size()
                && !rightPanelOpen) {
            // 左侧面板（分组筛选模式）
            Channel selectedChannel = currentGroupChannelList.get(position);
            int globalIndex = channelSourceList.indexOf(selectedChannel);
            if (globalIndex != -1) {
                SettingsActivity.logOperation("【列表】点击频道：" + selectedChannel.getName());
                playChannel(globalIndex);
                togglePanel();
            }
        } else {
            // 右侧面板（全部频道模式）
            if (position < channelSourceList.size()) {
                Channel ch = channelSourceList.get(position);
                SettingsActivity.logOperation("【列表】点击频道：" + ch.getName());
                playChannel(position);
            }
        }
    }

    public int getCurrentPlayIndex() {
        return currentPlayIndex;
    }

    public void setCurrentPlayIndex(int index) {
        this.currentPlayIndex = index;
    }

    public void setTotalChannelCount(int count) {
        // 预留方法
    }

    // ====================================================================
    // 4. 面板控制相关
    // ====================================================================

    public void togglePanel() {
        // 处理特殊分组
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) 
                || currentGroupName.isEmpty() 
                || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        } else if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                || GroupListManager.GROUP_RECENT.equals(currentGroupName)) {
            // ✅ 新增：特殊分组
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        }
        channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);

        boolean isOpen = isPanelOpen();
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);

        if (!isOpen) {
            // ====================================================================
            // ✅ 2026-06-24 修改：用光标管理器设置默认焦点
            // ====================================================================
            // 打开面板时，默认焦点在左面板的频道列表
            if (cursorManager != null) {
                cursorManager.setCurrentPanel(PanelCursorManager.PanelType.LEFT, false);
                cursorManager.setLeftFocusView(PanelCursorManager.LeftFocusView.CHANNEL, false);
                updateFocusStyle();
            }

            panelLayout.post(new Runnable() {
                @Override
                public void run() {
                    lvChannelList.setSelection(getChannelListSelection());
                }
            });
        }

        if (panelStateListener != null) {
            panelStateListener.onPanelStateChanged(!isOpen);
        }

        SettingsActivity.logOperation("【面板】" + (isOpen ? "关闭" : "打开") + "频道面板");
    }

    public void showPanel() {
        if (!isPanelOpen()) {
            togglePanel();
        }
    }

    public void hidePanel() {
        if (isPanelOpen()) {
            togglePanel();
        }
    }

    public boolean isPanelOpen() {
        return panelLayout.getVisibility() == View.VISIBLE;
    }

    // ====================================================================
    // 右侧面板是否打开
    // ====================================================================
    public boolean isRightPanelOpen() {
        return rightPanelOpen;
    }

    private void onEpgButtonClicked() {
        if (!epgEnable) {
            SettingsActivity.logOperation("【EPG】节目单功能已关闭，无法展开");
            return;
        }

        if (!rightPanelOpen) {
            llLeftPanel.setVisibility(View.GONE);
            llRightPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = true;
            epgPanelOpen = true;
                        channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);

            // ====================================================================
            // ✅ 2026-06-24 修改：同步光标管理器状态
            // ====================================================================
            if (cursorManager != null) {
                cursorManager.setCurrentPanel(PanelCursorManager.PanelType.RIGHT, false);
                cursorManager.setRightFocusView(PanelCursorManager.RightFocusView.CHANNEL, false);
                cursorManager.setRightChannelSelectedPosition(currentPlayIndex, false);
                updateFocusStyle();
            }

            llRightPanel.post(new Runnable() {
                @Override
                public void run() {
                    lvChannelListEpg.setSelection(currentPlayIndex);
                }
            });

            SettingsActivity.logOperation("【面板】展开节目单面板");

            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        } else {
            llRightPanel.setVisibility(View.GONE);
            llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;

            // ====================================================================
            // ✅ 2026-06-24 修改：同步光标管理器状态
            // ====================================================================
            if (cursorManager != null) {
                cursorManager.setCurrentPanel(PanelCursorManager.PanelType.LEFT, false);
                cursorManager.setLeftFocusView(PanelCursorManager.LeftFocusView.CHANNEL, false);
                updateFocusStyle();
            }

            llLeftPanel.post(new Runnable() {
                @Override
                public void run() {
                    lvChannelList.setSelection(getChannelListSelection());
                }
            });

            SettingsActivity.logOperation("【面板】收起节目单面板");
        }
    }

    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            llRightPanel.setVisibility(View.GONE);
            llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;

            // ====================================================================
            // ✅ 2026-06-24 修改：同步光标管理器状态
            // ====================================================================
            if (cursorManager != null) {
                cursorManager.setCurrentPanel(PanelCursorManager.PanelType.LEFT, false);
                cursorManager.setLeftFocusView(PanelCursorManager.LeftFocusView.EPG_BTN, false);
                updateFocusStyle();
            }

            llLeftPanel.post(new Runnable() {
                @Override
                public void run() {
                    lvChannelList.setSelection(getChannelListSelection());
                }
            });

            SettingsActivity.logOperation("【面板】返回频道分组");
        }
    }

    public boolean isEpgPanelOpen() {
        return epgPanelOpen;
    }

    public void setCurrentDateIndex(int index) {
        this.currentSelectedDateIndex = index;
        panelManager.setCurrentDateIndex(index);

        if (!channelSourceList.isEmpty()
                && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel curr = channelSourceList.get(currentPlayIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
        }

        // ✅ 同步光标管理器的日期选中位置
        if (cursorManager != null) {
            cursorManager.setDateSelectedPosition(index, false);
        }
    }

    public int getCurrentSelectedDateIndex() {
        return currentSelectedDateIndex;
    }

    private int getChannelListSelection() {
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) 
                || currentGroupName.isEmpty() 
                || currentGroupChannelList.isEmpty()) {
            return currentPlayIndex;
        } else if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                || GroupListManager.GROUP_RECENT.equals(currentGroupName)) {
            // ✅ 新增：特殊分组，找在筛选列表中的索引
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannelName)) {
                    return i;
                }
            }
            return 0;
        } else {
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
                    return i;
                }
            }
            return 0;
        }
    }

    // ====================================================================
    // 5. 返回键处理
    // ====================================================================
    public boolean handleBackPressed() {
        if (isPanelOpen()) {
            if (rightPanelOpen) {
                onBackGroupClicked();
                return true;
            }
            hidePanel();
            return true;
        }
        return false;
    }

    // ====================================================================
    // 按键事件分发
    // ====================================================================

    /**
     * 按键事件分发
     * 
     * 【2026-06-24 修改：改用光标管理器处理】
     * 
     * 【修改说明】
     * 原来的实现是用原生 focus 机制，通过 handleLeftKey / handleRightKey / handleOkKey
     * 手动管理各个 View 的焦点切换，代码繁琐且容易出错。
     * 
     * 现在改用 PanelCursorManager 统一管理，代码更简洁，逻辑更清晰。
     * 
     * 【保留的按键】
     * - 菜单键（KEYCODE_MENU）：收藏/取消收藏，和光标无关，保留
     * 
     * 【交给光标管理器的按键】
     * - 上下左右键（DPAD_UP/DOWN/LEFT/RIGHT）
     * - 确认键（DPAD_CENTER / ENTER）
     */
    public boolean dispatchKeyEvent(int keyCode) {
        if (!isPanelOpen()) {
            return false;
        }

        switch (keyCode) {
            // ✅ 新增：菜单键（收藏/取消收藏）
            case KeyEvent.KEYCODE_MENU:
                toggleCurrentFavorite();
                return true;

            // ====================================================================
            // ✅ 2026-06-24 修改：其他按键交给光标管理器处理
            // ====================================================================
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (cursorManager != null) {
                    return cursorManager.handleKeyEvent(keyCode);
                }
                return false;

            default:
                return false;
        }
    }

    // ====================================================================
    // 6. 监听器设置
    // ====================================================================
    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    // ====================================================================
    // 7. 资源释放
    // ====================================================================
    public void release() {
        context = null;
        panelLayout = null;
        llLeftPanel = null;
        llRightPanel = null;
        lvGroup = null;
        lvChannelList = null;
        lvChannelListEpg = null;
        lvDate = null;
        lvEpg = null;
        btnShowEpg = null;
        btnBackGroup = null;
        channelSourceList = null;
        currentGroupChannelList = null;
        channelChangeListener = null;
        panelStateListener = null;
        groupListManager = null;
        channelListManager = null;
        channelListManagerEpg = null;
        dateListManager = null;
        epgManagerWrapper = null;
        panelManager = null;

        // ✅ 2026-06-24 新增：释放光标管理器
        cursorManager = null;
    }
}
