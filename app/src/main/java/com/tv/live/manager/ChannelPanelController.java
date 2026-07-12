package com.tv.live.manager;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
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
 * 已移除所有自动隐藏逻辑，面板生命周期完全由用户交互控制
 */
public class ChannelPanelController {

    private static final long CHANNEL_COOLDOWN = 300;
    private static final int MAX_AUTO_SKIP = 10;

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

    private boolean mIsFirstLaunch = true;

    private boolean isReverse = false;
    private long lastChannelChangeTime = 0;

    private String currentFocusPanel = "left";
    private String leftFocusView = "channel";
    private String rightFocusView = "channel";

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
        lvGroup.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "left";
                leftFocusView = "group";
                syncFocusStyle();
            }
        });
        lvChannelList.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "left";
                leftFocusView = "channel";
                syncFocusStyle();
            }
        });
        btnShowEpg.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "left";
                leftFocusView = "epgBtn";
                syncFocusStyle();
            }
        });
        lvChannelListEpg.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "right";
                rightFocusView = "channel";
                syncFocusStyle();
            }
        });
        lvDate.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "right";
                rightFocusView = "date";
                syncFocusStyle();
            }
        });
        lvEpg.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "right";
                rightFocusView = "epg";
                syncFocusStyle();
            }
        });
        btnBackGroup.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                currentFocusPanel = "right";
                rightFocusView = "backBtn";
                syncFocusStyle();
            }
        });
    }

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
        clearAllFocusStyles();
        if ("left".equals(currentFocusPanel)) {
            if ("group".equals(leftFocusView)) {
                groupListManager.setFocused(true);
            } else if ("channel".equals(leftFocusView)) {
                channelListManager.setFocused(true);
            } else if ("epgBtn".equals(leftFocusView)) {
                btnShowEpg.setTextColor(0xFFFFFFFF);
                btnShowEpg.setTypeface(null, Typeface.BOLD);
                btnShowEpg.setBackgroundColor(0x3340A9FF);
            }
        } else if ("right".equals(currentFocusPanel)) {
            if ("channel".equals(rightFocusView)) {
                channelListManagerEpg.setFocused(true);
            } else if ("date".equals(rightFocusView)) {
                dateListManager.setFocused(true);
            } else if ("backBtn".equals(rightFocusView)) {
                btnBackGroup.setTextColor(0xFFFFFFFF);
                btnBackGroup.setTypeface(null, Typeface.BOLD);
                btnBackGroup.setBackgroundColor(0x3340A9FF);
            }
        }
    }

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

    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            return;
        }
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            return;
        }
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) {
            return;
        }
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;
        int prevGroupIndex = (groupIndex - 1 + groupChannels.size()) % groupChannels.size();
        Channel prevChannel = groupChannels.get(prevGroupIndex);
        int globalIndex = channelSourceList.indexOf(prevChannel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
    }

    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) {
            return;
        }
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) {
            return;
        }
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        String currentGroup = currentChannel.getGroup();
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentGroup.equals(c.getGroup())) {
                groupChannels.add(c);
            }
        }
        if (groupChannels.size() <= 1) {
            return;
        }
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) {
                groupIndex = i;
                break;
            }
        }
        if (groupIndex == -1) return;
        int nextGroupIndex = (groupIndex + 1) % groupChannels.size();
        Channel nextChannel = groupChannels.get(nextGroupIndex);
        int globalIndex = channelSourceList.indexOf(nextChannel);
        if (globalIndex != -1) {
            playChannel(globalIndex);
        }
    }

    public void switchUp() {
        lastSwitchDirection = "up";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) {
            playNext();
        } else {
            playPrev();
        }
    }

    public void switchDown() {
        lastSwitchDirection = "down";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) {
            playPrev();
        } else {
            playNext();
        }
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
                    if (channelGroup.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                int groupPos = groupListManager.getGroupPosition(channelGroup);
                groupListManager.setSelectedPosition(groupPos);
            }
        }
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
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
        return false;
    }

    public boolean toggleCurrentFavorite() {
        return false;
    }

    private void onChannelClicked(int position) {
        if (!currentGroupChannelList.isEmpty() && position < currentGroupChannelList.size()
                && !rightPanelOpen) {
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

    /**
     * 核心切换逻辑：完全依赖 panelLayout 的当前可见性，没有自动隐藏干扰
     */
    public void togglePanel() {
        boolean willOpen = !isPanelOpen();

        // 准备数据
        if (willOpen) {
            if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                    || currentGroupName.isEmpty()
                    || currentGroupChannelList.isEmpty()) {
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
            } else {
                channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
            }
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
        }

        // 调用 PanelManager 切换可见性
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);

        // 如果面板打开，延迟 100ms 设置焦点（确保 UI 已渲染）
        if (isPanelOpen()) {
            panelLayout.postDelayed(() -> {
                clearAllFocusStyles();
                currentFocusPanel = "left";
                leftFocusView = "channel";
                syncFocusStyle();
                lvChannelList.setFocusable(true);
                lvChannelList.setFocusableInTouchMode(true);
                lvChannelList.requestFocus();
                lvChannelList.setSelection(getChannelListSelection());
            }, 100);
        }

        if (panelStateListener != null) {
            panelStateListener.onPanelStateChanged(willOpen);
        }
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

    public void handleFirstLaunch() {
        // 已移除自动隐藏逻辑，无需额外操作
        mIsFirstLaunch = false;
    }

    public boolean isFirstLaunch() {
        return mIsFirstLaunch;
    }

    public boolean isRightPanelOpen() {
        return rightPanelOpen;
    }

    private void onEpgButtonClicked() {
        if (!epgEnable) {
            return;
        }
        if (!rightPanelOpen) {
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.GONE);
            }
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = true;
            epgPanelOpen = true;
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            if (llRightPanel != null) {
                llRightPanel.postDelayed(() -> {
                    clearAllFocusStyles();
                    currentFocusPanel = "right";
                    rightFocusView = "channel";
                    syncFocusStyle();
                    lvChannelListEpg.setFocusable(true);
                    lvChannelListEpg.setFocusableInTouchMode(true);
                    lvChannelListEpg.requestFocus();
                    lvChannelListEpg.setSelection(currentPlayIndex);
                }, 100);
            }
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        } else {
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.GONE);
            }
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.VISIBLE);
            }
            rightPanelOpen = false;
            epgPanelOpen = false;
            if (llLeftPanel != null) {
                llLeftPanel.postDelayed(() -> {
                    clearAllFocusStyles();
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
                    lvChannelList.setFocusable(true);
                    lvChannelList.setFocusableInTouchMode(true);
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                }, 100);
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
                llLeftPanel.postDelayed(() -> {
                    clearAllFocusStyles();
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
                    lvChannelList.setFocusable(true);
                    lvChannelList.setFocusableInTouchMode(true);
                    lvChannelList.requestFocus();
                    lvChannelList.setSelection(getChannelListSelection());
                }, 100);
            }
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
    }

    public int getCurrentSelectedDateIndex() {
        return currentSelectedDateIndex;
    }

    private int getChannelListSelection() {
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            return currentPlayIndex;
        } else {
            if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) {
                return 0;
            }
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
        return isSwitchingChannel
                && !"".equals(lastSwitchDirection)
                && autoSkipCount < MAX_AUTO_SKIP;
    }

    public boolean autoSkipFailedChannel() {
        if (!canAutoSkip()) {
            return false;
        }
        autoSkipCount++;
        if ("up".equals(lastSwitchDirection)) {
            if (isReverse) {
                playNext();
            } else {
                playPrev();
            }
        } else if ("down".equals(lastSwitchDirection)) {
            if (isReverse) {
                playPrev();
            } else {
                playNext();
            }
        }
        return true;
    }

    public void setReverse(boolean reverse) {
        this.isReverse = reverse;
    }

    public boolean isReverse() {
        return isReverse;
    }

    // ============================================================
    // ✅【核心修复】方案三：上下键主动控制 ListView 滚动
    // ✅【完整 OK 键支持】所有可交互控件都能响应确认键
    // ============================================================
    public boolean dispatchKeyEvent(int keyCode) {
        if (panelLayout.getVisibility() != View.VISIBLE) {
            return false;
        }

        View currentFocus = panelLayout.findFocus();
        if (currentFocus == null) return false;

        // ==================== 上下键：让当前焦点所在的 ListView 滚动 ====================
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (currentFocus instanceof ListView) {
                ListView lv = (ListView) currentFocus;
                // 获取当前选中位置，如果没有选中项则取第一个可见位置
                int pos = lv.getSelectedItemPosition();
                if (pos == -1) {
                    pos = lv.getFirstVisiblePosition();
                    if (pos == -1) pos = 0; // 列表为空时的兜底
                }
                int count = lv.getCount();
                if (count == 0) return true; // 列表为空，直接消费按键

                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (pos > 0) {
                        lv.setSelection(pos - 1);
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (pos < count - 1) {
                        lv.setSelection(pos + 1);
                        return true;
                    }
                }
                // 到达顶部/底部时仍然返回 true，防止焦点跳离列表
                return true;
            } else {
                // 当前焦点不是 ListView，让系统处理焦点切换
                return false;
            }
        }

        // ==================== OK/确认键：统一处理所有控件上的确认操作 ====================
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (currentFocus == lvChannelList) {
                int pos = lvChannelList.getSelectedItemPosition();
                if (pos >= 0 && pos < lvChannelList.getCount()) {
                    onChannelClicked(pos);
                    return true;
                }
            } else if (currentFocus == lvGroup) {
                int pos = lvGroup.getSelectedItemPosition();
                if (pos >= 0 && pos < lvGroup.getCount()) {
                    onGroupClicked(pos);
                    return true;
                }
            } else if (currentFocus == lvChannelListEpg) {
                int pos = lvChannelListEpg.getSelectedItemPosition();
                if (pos >= 0 && pos < lvChannelListEpg.getCount()) {
                    onChannelClicked(pos);
                    return true;
                }
            } else if (currentFocus == btnShowEpg) {
                onEpgButtonClicked();
                return true;
            } else if (currentFocus == btnBackGroup) {
                onBackGroupClicked();
                return true;
            }
            return false;
        }

        // ==================== 原有左右键业务逻辑保持不变 ====================
        if (!rightPanelOpen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (currentFocus == lvGroup) {
                        lvChannelList.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvChannelList) {
                        btnShowEpg.requestFocus();
                        return true;
                    }
                    if (currentFocus == btnShowEpg) {
                        onEpgButtonClicked();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (currentFocus == btnShowEpg) {
                        lvChannelList.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvChannelList) {
                        lvGroup.requestFocus();
                        return true;
                    }
                    break;
                default:
                    break;
            }
        } else {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (currentFocus == lvEpg) {
                        lvDate.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvDate) {
                        lvChannelListEpg.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvChannelListEpg) {
                        btnBackGroup.requestFocus();
                        return true;
                    }
                    if (currentFocus == btnBackGroup) {
                        onBackGroupClicked();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (currentFocus == btnBackGroup) {
                        lvChannelListEpg.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvChannelListEpg) {
                        lvDate.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvDate) {
                        lvEpg.requestFocus();
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return false;
    }

    // ✅ 由外部统一清除面板焦点
    public void clearPanelFocus() {
        if (panelLayout != null) {
            panelLayout.clearFocus();
        }
    }

    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    public void release() {
        // 无 Handler 需要清理
    }
}
