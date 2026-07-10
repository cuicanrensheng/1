package com.tv.live.manager;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.tv.live.Channel;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 频道面板控制器
 * 
 * 【2026-07-10 修复：完全基于真实 View 焦点管理，删除虚拟焦点标记】
 * 【2026-07-10 完善：跨列表导航全链路支持】
 */
public class ChannelPanelController {

    private static final long CHANNEL_COOLDOWN = 300;
    private static final int MAX_AUTO_SKIP = 10;

    private static final long FIRST_LAUNCH_HIDE_DELAY_MS = 5000;
    private static final long NORMAL_HIDE_DELAY_MS = 20000;

    private Context context;
    private View panelLayout;
    private ListView lvGroup;
    private ListView lvChannelList;
    private ListView lvChannelListEpg;
    private ListView lvDate;
    private ListView lvEpg;
    private TextView btnShowEpg;
    private TextView btnBackGroup;

    private View llLeftPanel;
    private View llRightPanel;
    private boolean rightPanelOpen = false;

    private GroupListManager groupListManager;
    private ChannelListManager channelListManager;
    private ChannelListManager channelListManagerEpg;
    private DateListManager dateListManager;
    private EpgManagerWrapper epgManagerWrapper;
    private PanelManager panelManager;

    private List<Channel> channelSourceList = new ArrayList<>();
    private List<Channel> currentGroupChannelList = new ArrayList<>();
    private String currentGroupName = "";
    private int currentPlayIndex = 0;
    private int currentSelectedDateIndex = 0;

    private boolean epgPanelOpen = false;
    private boolean epgEnable = true;

    private Handler mAutoHideHandler;
    private Runnable mAutoHideRunnable;
    private long mAutoHideDelayMs = 5000;
    private boolean mAutoHideEnabled = true;
    private boolean mIsFirstLaunch = true;

    private boolean isReverse = false;
    private long lastChannelChangeTime = 0;

    private String lastSwitchDirection = "";
    private boolean isSwitchingChannel = false;
    private int autoSkipCount = 0;

    private OnChannelChangeListener channelChangeListener;
    private OnPanelStateListener panelStateListener;

    public interface OnChannelChangeListener {
        void onChannelChanged(Channel channel, int index);
    }

    public interface OnPanelStateListener {
        void onPanelStateChanged(boolean isOpen);
    }

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
        initFocusListeners();
        initAutoHide();
    }

    private void initClickListeners() {
        lvGroup.setOnItemClickListener((parent, view, position, id) -> onGroupClicked(position));
        lvChannelList.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));
        lvChannelListEpg.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));
        channelListManager.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, false));
        channelListManagerEpg.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, true));
        btnShowEpg.setOnClickListener(v -> onEpgButtonClicked());
        btnBackGroup.setOnClickListener(v -> onBackGroupClicked());
    }

    private void initFocusListeners() {
        // 所有焦点变化时统一刷新样式（不再依赖内存标记）
        View.OnFocusChangeListener focusChangeListener = (v, hasFocus) -> {
            if (hasFocus) {
                syncFocusStyle();
            }
        };
        lvGroup.setOnFocusChangeListener(focusChangeListener);
        lvChannelList.setOnFocusChangeListener(focusChangeListener);
        btnShowEpg.setOnFocusChangeListener(focusChangeListener);
        lvChannelListEpg.setOnFocusChangeListener(focusChangeListener);
        lvDate.setOnFocusChangeListener(focusChangeListener);
        lvEpg.setOnFocusChangeListener(focusChangeListener);
        btnBackGroup.setOnFocusChangeListener(focusChangeListener);
    }

    private void initAutoHide() {
        mAutoHideHandler = new Handler(Looper.getMainLooper());
        mAutoHideRunnable = () -> hidePanel();
        mAutoHideEnabled = true;
        mAutoHideDelayMs = 5000;
    }

    // ===================== 样式同步（基于真实焦点） =====================
    private void clearAllFocusStyles() {
        groupListManager.setFocused(false);
        channelListManager.setFocused(false);
        channelListManagerEpg.setFocused(false);
        dateListManager.setFocused(false);
        btnShowEpg.setTextColor(0xFFFFFFFF);
        btnShowEpg.setTypeface(null, Typeface.NORMAL);
        btnShowEpg.setBackgroundColor(0x00000000);
        btnBackGroup.setTextColor(0xFFFFFFFF);
        btnBackGroup.setTypeface(null, Typeface.NORMAL);
        btnBackGroup.setBackgroundColor(0x00000000);
    }

    private void syncFocusStyle() {
        if (panelLayout == null) return;
        View focused = panelLayout.findFocus();
        clearAllFocusStyles();
        if (focused == null) return;
        if (focused == lvGroup) {
            groupListManager.setFocused(true);
        } else if (focused == lvChannelList) {
            channelListManager.setFocused(true);
        } else if (focused == btnShowEpg) {
            btnShowEpg.setTextColor(0xFFFFFFFF);
            btnShowEpg.setTypeface(null, Typeface.BOLD);
            btnShowEpg.setBackgroundColor(0x3340A9FF);
        } else if (focused == lvChannelListEpg) {
            channelListManagerEpg.setFocused(true);
        } else if (focused == lvDate) {
            dateListManager.setFocused(true);
        } else if (focused == lvEpg) {
            // EPG 列表焦点样式由 Adapter 自身管理，这里不额外设置
        } else if (focused == btnBackGroup) {
            btnBackGroup.setTextColor(0xFFFFFFFF);
            btnBackGroup.setTypeface(null, Typeface.BOLD);
            btnBackGroup.setBackgroundColor(0x3340A9FF);
        }
    }

    // ===================== 数据设置 =====================
    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;
        groupListManager.setGroups(channels);
        channelListManager.setChannels(channels, currentPlayIndex);
        channelListManagerEpg.setChannels(channels, currentPlayIndex);
    }

    private void onGroupClicked(int position) {
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;
        if (GroupListManager.GROUP_ALL.equals(groupName)) {
            currentGroupChannelList.clear();
            currentGroupChannelList.addAll(channelSourceList);
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        } else {
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
        }
        // 焦点停留在频道列表
        lvChannelList.requestFocus();
        syncFocusStyle();
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

    // 播放控制（切台）
    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) groupChannels.add(c);
        }
        if (groupChannels.size() <= 1) return;
        int groupIndex = groupChannels.indexOf(currentChannel);
        if (groupIndex == -1) return;
        int prevGroupIndex = (groupIndex - 1 + groupChannels.size()) % groupChannels.size();
        Channel prevChannel = groupChannels.get(prevGroupIndex);
        int globalIndex = channelSourceList.indexOf(prevChannel);
        if (globalIndex != -1) playChannel(globalIndex);
    }

    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) groupChannels.add(c);
        }
        if (groupChannels.size() <= 1) return;
        int groupIndex = groupChannels.indexOf(currentChannel);
        if (groupIndex == -1) return;
        int nextGroupIndex = (groupIndex + 1) % groupChannels.size();
        Channel nextChannel = groupChannels.get(nextGroupIndex);
        int globalIndex = channelSourceList.indexOf(nextChannel);
        if (globalIndex != -1) playChannel(globalIndex);
    }

    public void switchUp() {
        lastSwitchDirection = "up";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) playNext();
        else playPrev();
    }

    public void switchDown() {
        lastSwitchDirection = "down";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) playPrev();
        else playNext();
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null) return;
        String channelGroup = ch.getGroup();
        if (channelGroup != null && !channelGroup.isEmpty()) {
            if (!channelGroup.equals(currentGroupName)) {
                currentGroupName = channelGroup;
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (channelGroup.equals(c.getGroup())) currentGroupChannelList.add(c);
                }
                int groupPos = groupListManager.getGroupPosition(channelGroup);
                groupListManager.setSelectedPosition(groupPos);
            }
        }
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) || currentGroupName.isEmpty() || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, index);
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        }
        channelListManagerEpg.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
        }
    }

    private boolean handleChannelLongClick(String channelName, boolean isRightPanel) {
        return false; // 暂不实现
    }

    public boolean toggleCurrentFavorite() {
        return false; // 暂不实现
    }

    private void onChannelClicked(int position) {
        if (!currentGroupChannelList.isEmpty() && position < currentGroupChannelList.size() && !rightPanelOpen) {
            Channel selectedChannel = currentGroupChannelList.get(position);
            int globalIndex = channelSourceList.indexOf(selectedChannel);
            if (globalIndex != -1) {
                lastSwitchDirection = "";
                isSwitchingChannel = false;
                autoSkipCount = 0;
                playChannel(globalIndex);
                togglePanel();
            }
        } else {
            if (position < channelSourceList.size()) {
                Channel ch = channelSourceList.get(position);
                lastSwitchDirection = "";
                isSwitchingChannel = false;
                autoSkipCount = 0;
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
    }

    // 面板开关
    public void togglePanel() {
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) || currentGroupName.isEmpty() || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
        }
        channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
        boolean isOpen = isPanelOpen();
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
        if (!isOpen) {
            panelLayout.post(() -> {
                // 默认焦点放在左侧频道列表
                lvChannelList.requestFocus();
                lvChannelList.setSelection(getChannelListSelection());
                syncFocusStyle();
                resetAutoHide();
            });
        } else {
            cancelAutoHide();
        }
        if (panelStateListener != null) {
            panelStateListener.onPanelStateChanged(!isOpen);
        }
    }

    public void showPanel() {
        if (!isPanelOpen()) togglePanel();
    }

    public void hidePanel() {
        if (isPanelOpen()) {
            cancelAutoHide();
            togglePanel();
        }
    }

    public boolean isPanelOpen() {
        return panelLayout.getVisibility() == View.VISIBLE;
    }

    // 自动隐藏
    public void resetAutoHide() {
        if (!mAutoHideEnabled) return;
        if (mAutoHideHandler != null && mAutoHideRunnable != null) {
            mAutoHideHandler.removeCallbacks(mAutoHideRunnable);
            if (isPanelOpen()) {
                mAutoHideHandler.postDelayed(mAutoHideRunnable, mAutoHideDelayMs);
            }
        }
    }

    public void cancelAutoHide() {
        if (mAutoHideHandler != null && mAutoHideRunnable != null) {
            mAutoHideHandler.removeCallbacks(mAutoHideRunnable);
        }
    }

    public void setAutoHideDelay(long delayMs) {
        this.mAutoHideDelayMs = delayMs;
    }

    public void setAutoHideEnabled(boolean enabled) {
        this.mAutoHideEnabled = enabled;
        if (!enabled) cancelAutoHide();
    }

    public void handleFirstLaunch() {
        if (!mIsFirstLaunch) return;
        setAutoHideDelay(FIRST_LAUNCH_HIDE_DELAY_MS);
        resetAutoHide();
        setAutoHideDelay(NORMAL_HIDE_DELAY_MS);
        mIsFirstLaunch = false;
    }

    public boolean isFirstLaunch() {
        return mIsFirstLaunch;
    }

    public boolean isRightPanelOpen() {
        return rightPanelOpen;
    }

    // EPG 按钮点击
    private void onEpgButtonClicked() {
        if (!epgEnable) return;
        if (!rightPanelOpen) {
            // 打开右侧面板
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.GONE);
            if (llRightPanel != null) llRightPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = true;
            epgPanelOpen = true;
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            if (llRightPanel != null) {
                llRightPanel.post(() -> {
                    lvChannelListEpg.requestFocus();
                    lvChannelListEpg.setSelection(currentPlayIndex);
                    syncFocusStyle();
                });
            }
            if (!channelSourceList.isEmpty() && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        } else {
            // 关闭右侧面板
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            if (llLeftPanel != null) {
                llLeftPanel.post(() -> {
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                    syncFocusStyle();
                });
            }
        }
    }

    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            if (llLeftPanel != null) {
                llLeftPanel.post(() -> {
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                    syncFocusStyle();
                });
            }
        }
    }

    public boolean isEpgPanelOpen() {
        return epgPanelOpen;
    }

    public void setCurrentDateIndex(int index) {
        this.currentSelectedDateIndex = index;
        panelManager.setCurrentDateIndex(index);
        if (!channelSourceList.isEmpty() && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
            Channel curr = channelSourceList.get(currentPlayIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
        }
    }

    public int getCurrentSelectedDateIndex() {
        return currentSelectedDateIndex;
    }

    private int getChannelListSelection() {
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) || currentGroupName.isEmpty() || currentGroupChannelList.isEmpty()) {
            return currentPlayIndex;
        } else {
            if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) return 0;
            Channel currentChannel = channelSourceList.get(currentPlayIndex);
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
                    return i;
                }
            }
            return 0;
        }
    }

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

    public void onPlaySuccess() {
        isSwitchingChannel = false;
        autoSkipCount = 0;
    }

    public boolean canAutoSkip() {
        return isSwitchingChannel && !"".equals(lastSwitchDirection) && autoSkipCount < MAX_AUTO_SKIP;
    }

    public boolean autoSkipFailedChannel() {
        if (!canAutoSkip()) return false;
        autoSkipCount++;
        if ("up".equals(lastSwitchDirection)) {
            if (isReverse) playNext();
            else playPrev();
        } else if ("down".equals(lastSwitchDirection)) {
            if (isReverse) playPrev();
            else playNext();
        }
        return true;
    }

    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
    }

    public boolean isReverse() {
        return isReverse;
    }

    // ===================== 核心导航分发 =====================
    public boolean dispatchKeyEvent(int keyCode) {
        View currentFocus = panelLayout.findFocus();
        if (currentFocus == null) return false;

        if (rightPanelOpen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    return moveFocusRightUp(currentFocus);
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return moveFocusRightDown(currentFocus);
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return moveFocusRightLeft(currentFocus);
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return moveFocusRightRight(currentFocus);
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    performRightItemClick(currentFocus);
                    return true;
                default:
                    return false;
            }
        } else {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    return moveFocusLeftUp(currentFocus);
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return moveFocusLeftDown(currentFocus);
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return moveFocusLeftLeft(currentFocus);
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return moveFocusLeftRight(currentFocus);
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    performLeftItemClick(currentFocus);
                    return true;
                default:
                    return false;
            }
        }
    }

    // ---------- 左侧面板导航 ----------
    private boolean moveFocusLeftUp(View current) {
        if (current == lvGroup) {
            // 分组列表第一行按上，无操作（或可循环到末尾，但分组列表通常不需要循环）
            return false;
        } else if (current == lvChannelList) {
            // 频道列表向上：移动到分组列表
            lvGroup.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == btnShowEpg) {
            // EPG按钮向上：移动到频道列表
            lvChannelList.requestFocus();
            syncFocusStyle();
            return true;
        }
        return false;
    }

    private boolean moveFocusLeftDown(View current) {
        if (current == lvGroup) {
            // 分组列表向下：移动到频道列表
            lvChannelList.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == lvChannelList) {
            // 频道列表向下：移动到 EPG 按钮
            btnShowEpg.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == btnShowEpg) {
            // EPG 按钮向下：无操作（边界）
            return false;
        }
        return false;
    }

    private boolean moveFocusLeftLeft(View current) {
        if (current == btnShowEpg) {
            // EPG按钮左移：移动到频道列表
            lvChannelList.requestFocus();
            syncFocusStyle();
            return true;
        }
        // 其他控件左移无操作
        return false;
    }

    private boolean moveFocusLeftRight(View current) {
        if (current == lvChannelList) {
            // 频道列表右移：移动到 EPG 按钮
            btnShowEpg.requestFocus();
            syncFocusStyle();
            return true;
        }
        // 其他控件右移无操作（分组列表和 EPG 按钮都不响应右移）
        return false;
    }

    private void performLeftItemClick(View current) {
        if (current == lvGroup) {
            int pos = lvGroup.getSelectedItemPosition();
            onGroupClicked(pos);
        } else if (current == lvChannelList) {
            int pos = lvChannelList.getSelectedItemPosition();
            onChannelClicked(pos);
        } else if (current == btnShowEpg) {
            onEpgButtonClicked();
        }
    }

    // ---------- 右侧面板导航 ----------
    private boolean moveFocusRightUp(View current) {
        if (current == lvChannelListEpg) {
            // 频道列表向上：无操作（边界）
            return false;
        } else if (current == lvDate) {
            // 日期列表向上：移动到频道列表
            lvChannelListEpg.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == lvEpg) {
            // EPG节目单向上：移动到日期列表
            lvDate.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == btnBackGroup) {
            // 返回按钮向上：移动到频道列表（或 EPG 列表）
            lvChannelListEpg.requestFocus();
            syncFocusStyle();
            return true;
        }
        return false;
    }

    private boolean moveFocusRightDown(View current) {
        if (current == lvChannelListEpg) {
            // 频道列表向下：移动到日期列表
            lvDate.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == lvDate) {
            // 日期列表向下：移动到 EPG 节目单
            lvEpg.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == lvEpg) {
            // EPG节目单向下：移动到返回按钮
            btnBackGroup.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == btnBackGroup) {
            // 返回按钮向下：无操作（边界）
            return false;
        }
        return false;
    }

    private boolean moveFocusRightLeft(View current) {
        if (current == lvChannelListEpg) {
            // 频道列表左移：无操作（或可返回左侧，但根据设计，左移应回到左侧面板）
            // 这里我们让左移关闭右侧面板（和返回按钮类似）
            onBackGroupClicked();
            return true;
        } else if (current == lvDate) {
            // 日期左移：移动到频道列表
            lvChannelListEpg.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == lvEpg) {
            // EPG左移：移动到日期列表
            lvDate.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == btnBackGroup) {
            // 返回按钮左移：移动到 EPG 节目单（或直接关闭）
            // 但根据设计，返回按钮左移应该关闭右侧面板（即执行点击效果）
            onBackGroupClicked();
            return true;
        }
        return false;
    }

    private boolean moveFocusRightRight(View current) {
        if (current == lvChannelListEpg) {
            // 频道列表右移：移动到日期列表
            lvDate.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == lvDate) {
            // 日期右移：移动到 EPG 节目单
            lvEpg.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == lvEpg) {
            // EPG右移：移动到返回按钮
            btnBackGroup.requestFocus();
            syncFocusStyle();
            return true;
        } else if (current == btnBackGroup) {
            // 返回按钮右移：无操作（边界）
            return false;
        }
        return false;
    }

    private void performRightItemClick(View current) {
        if (current == lvChannelListEpg) {
            int pos = lvChannelListEpg.getSelectedItemPosition();
            onChannelClicked(pos);
        } else if (current == lvDate) {
            int pos = lvDate.getSelectedItemPosition();
            setCurrentDateIndex(pos);
        } else if (current == lvEpg) {
            View focused = lvEpg.getSelectedView();
            if (focused != null) {
                lvEpg.performItemClick(focused, lvEpg.getSelectedItemPosition(), lvEpg.getSelectedItemId());
            }
        } else if (current == btnBackGroup) {
            onBackGroupClicked();
        }
    }

    // ===================== 监听器设置 =====================
    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    // ===================== 资源释放 =====================
    public void release() {
        cancelAutoHide();
        if (mAutoHideHandler != null) {
            mAutoHideHandler.removeCallbacksAndMessages(null);
            mAutoHideHandler = null;
        }
        mAutoHideRunnable = null;
        // 清空引用帮助 GC
        context = null;
        panelLayout = null;
        lvGroup = null;
        lvChannelList = null;
        lvChannelListEpg = null;
        lvDate = null;
        lvEpg = null;
        btnShowEpg = null;
        btnBackGroup = null;
        llLeftPanel = null;
        llRightPanel = null;
        groupListManager = null;
        channelListManager = null;
        channelListManagerEpg = null;
        dateListManager = null;
        epgManagerWrapper = null;
        panelManager = null;
        channelSourceList = null;
        currentGroupChannelList = null;
        channelChangeListener = null;
        panelStateListener = null;
    }
}
