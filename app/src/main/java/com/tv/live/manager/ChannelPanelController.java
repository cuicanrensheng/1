package com.tv.live.manager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import com.tv.live.Channel;
import com.tv.live.MainActivity;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
/**
 * 频道面板控制器【修复闪退完整版】
 * 修复：空指针、索引越界、并发修改、焦点死循环、页面销毁后回调崩溃
 */
public class ChannelPanelController {
    private static final String TAG = "ChannelPanelController";
    private static final long CHANNEL_COOLDOWN = 300;
    private static final int MAX_AUTO_SKIP = 10;

    private MainActivity activity;
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

    // 频道数据源 + 频道名->全局索引映射（优化indexOf，避免循环遍历）
    private final List<Channel> channelSourceList = new ArrayList<>();
    private final Map<String, Integer> channelNameIndexMap = new HashMap<>();
    private final List<Channel> currentGroupChannelList = new ArrayList<>();

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

    // 主线程Handler，统一管理延迟任务，页面销毁时全部清空
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // 标记是否已释放资源，防止release后继续执行逻辑
    private final AtomicBoolean isReleased = new AtomicBoolean(false);

    public interface OnChannelChangeListener {
        void onChannelChanged(Channel channel, int index);
    }
    public interface OnPanelStateListener {
        void onPanelStateChanged(boolean isOpen);
    }

    public ChannelPanelController(
            MainActivity activity,
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
        this.activity = activity;
        this.context = activity != null ? activity.getApplicationContext() : null;
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
        if (isReleased.get()) return;
        if (lvGroup != null) {
            lvGroup.setOnItemClickListener((parent, view, position, id) -> onGroupClicked(position));
        }
        if (lvChannelList != null) {
            lvChannelList.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));
        }
        if (lvChannelListEpg != null) {
            lvChannelListEpg.setOnItemClickListener((p, v, pos, id) -> onChannelClicked(pos));
        }
        if (channelListManager != null) {
            channelListManager.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, false));
        }
        if (channelListManagerEpg != null) {
            channelListManagerEpg.setOnChannelLongClickListener((channelName, position) -> handleChannelLongClick(channelName, true));
        }
        if (btnShowEpg != null) {
            btnShowEpg.setOnClickListener(v -> onEpgButtonClicked());
        }
        if (btnBackGroup != null) {
            btnBackGroup.setOnClickListener(v -> onBackGroupClicked());
        }
    }

    private void initFocusListeners() {
        if (isReleased.get()) return;
        if (lvGroup != null) {
            lvGroup.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && !isReleased.get()) {
                    currentFocusPanel = "left";
                    leftFocusView = "group";
                    syncFocusStyle();
                }
            });
        }
        if (lvChannelList != null) {
            lvChannelList.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && !isReleased.get()) {
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
                }
            });
        }
        if (btnShowEpg != null) {
            btnShowEpg.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && !isReleased.get()) {
                    currentFocusPanel = "left";
                    leftFocusView = "epgBtn";
                    syncFocusStyle();
                }
            });
        }
        if (lvChannelListEpg != null) {
            lvChannelListEpg.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && !isReleased.get()) {
                    currentFocusPanel = "right";
                    rightFocusView = "channel";
                    syncFocusStyle();
                }
            });
        }
        if (lvDate != null) {
            lvDate.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && !isReleased.get()) {
                    currentFocusPanel = "right";
                    rightFocusView = "date";
                    syncFocusStyle();
                }
            });
        }
        if (lvEpg != null) {
            lvEpg.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && !isReleased.get()) {
                    currentFocusPanel = "right";
                    rightFocusView = "epg";
                    syncFocusStyle();
                }
            });
        }
        if (btnBackGroup != null) {
            btnBackGroup.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && !isReleased.get()) {
                    currentFocusPanel = "right";
                    rightFocusView = "backBtn";
                    syncFocusStyle();
                }
            });
        }
    }

    private void clearAllFocusStyles() {
        if (isReleased.get()) return;
        if (groupListManager != null) groupListManager.setFocused(false);
        if (channelListManager != null) channelListManager.setFocused(false);
        if (channelListManagerEpg != null) channelListManagerEpg.setFocused(false);
        if (dateListManager != null) dateListManager.setFocused(false);

        if (btnShowEpg != null) {
            btnShowEpg.setTextColor(0xFFFFFFFF);
            btnShowEpg.setTypeface(null, Typeface.NORMAL);
            btnShowEpg.setBackgroundColor(0x00000000);
        }
        if (btnBackGroup != null) {
            btnBackGroup.setTextColor(0xFFFFFFFF);
            btnBackGroup.setTypeface(null, Typeface.NORMAL);
            btnBackGroup.setBackgroundColor(0x00000000);
        }
    }

    private void syncFocusStyle() {
        if (isReleased.get()) return;
        clearAllFocusStyles();
        if ("left".equals(currentFocusPanel)) {
            if ("group".equals(leftFocusView)) {
                if (groupListManager != null) groupListManager.setFocused(true);
            } else if ("channel".equals(leftFocusView)) {
                if (channelListManager != null) channelListManager.setFocused(true);
            } else if ("epgBtn".equals(leftFocusView) && btnShowEpg != null) {
                btnShowEpg.setTextColor(0xFFFFFFFF);
                btnShowEpg.setTypeface(null, Typeface.BOLD);
                btnShowEpg.setBackgroundColor(0x3340A9FF);
            }
        } else if ("right".equals(currentFocusPanel)) {
            if ("channel".equals(rightFocusView)) {
                if (channelListManagerEpg != null) channelListManagerEpg.setFocused(true);
            } else if ("date".equals(rightFocusView)) {
                if (dateListManager != null) dateListManager.setFocused(true);
            } else if ("backBtn".equals(rightFocusView) && btnBackGroup != null) {
                btnBackGroup.setTextColor(0xFFFFFFFF);
                btnBackGroup.setTypeface(null, Typeface.BOLD);
                btnBackGroup.setBackgroundColor(0x3340A9FF);
            }
        }
    }

    // 安全获取全局频道索引（替代低效indexOf）
    private int getChannelGlobalIndex(String channelName) {
        synchronized (channelSourceList) {
            if (channelNameIndexMap.containsKey(channelName)) {
                return channelNameIndexMap.get(channelName);
            }
            return -1;
        }
    }

    // 安全获取频道，防止越界
    private Channel safeGetChannel(List<Channel> list, int index) {
        if (list == null || index < 0 || index >= list.size()) return null;
        return list.get(index);
    }

    public void setChannels(List<Channel> channels) {
        if (isReleased.get() || channels == null) return;
        synchronized (channelSourceList) {
            channelSourceList.clear();
            channelNameIndexMap.clear();
            currentGroupChannelList.clear();
            if (!channels.isEmpty()) {
                channelSourceList.addAll(channels);
                for (int i = 0; i < channels.size(); i++) {
                    Channel ch = channels.get(i);
                    if (ch != null && ch.getName() != null) {
                        channelNameIndexMap.put(ch.getName(), i);
                    }
                }
            }
        }
        if (groupListManager != null) groupListManager.setGroups(channelSourceList);
        if (channelListManager != null) channelListManager.setChannels(channelSourceList, currentPlayIndex);
        if (channelListManagerEpg != null) channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
    }

    private void onGroupClicked(int position) {
        if (isReleased.get() || groupListManager == null || lvGroup == null) return;
        int count = lvGroup.getCount();
        if (position < 0 || position >= count) {
            Log.w(TAG, "onGroupClicked: 无效位置 " + position);
            return;
        }
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;

        synchronized (channelSourceList) {
            currentGroupChannelList.clear();
            if (GroupListManager.GROUP_ALL.equals(groupName)) {
                currentGroupChannelList.addAll(channelSourceList);
                if (channelListManager != null) channelListManager.setChannels(channelSourceList, currentPlayIndex);
            } else {
                for (Channel c : channelSourceList) {
                    if (groupName.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                if (channelListManager != null) channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            }
        }

        if (channelListManager != null && !channelSourceList.isEmpty()) {
            // 边界保护
            currentPlayIndex = Math.max(0, Math.min(currentPlayIndex, channelSourceList.size() - 1));
            Channel currentChannel = safeGetChannel(channelSourceList, currentPlayIndex);
            int targetPos = 0;
            if (currentChannel != null) {
                for (int i = 0; i < currentGroupChannelList.size(); i++) {
                    Channel item = currentGroupChannelList.get(i);
                    if (item != null && item.getName().equals(currentChannel.getName())) {
                        targetPos = i;
                        break;
                    }
                }
            }
            if (lvChannelList != null) {
                lvChannelList.setSelection(targetPos);
                lvChannelList.setFocusable(true);
                lvChannelList.setFocusableInTouchMode(true);
            }
        }
    }

    public String getCurrentGroupName() {
        return currentGroupName;
    }

    public List<Channel> getCurrentGroupChannels() {
        synchronized (channelSourceList) {
            return new ArrayList<>(currentGroupChannelList);
        }
    }

    public void setEpgEnable(boolean enable) {
        this.epgEnable = enable;
    }

    public void playPrev() {
        if (isReleased.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        synchronized (channelSourceList) {
            if (channelSourceList.isEmpty()) return;
            // 索引边界修复
            currentPlayIndex = Math.max(0, Math.min(currentPlayIndex, channelSourceList.size() - 1));
            Channel currentChannel = safeGetChannel(channelSourceList, currentPlayIndex);
            if (currentChannel == null) return;

            String currentGroup = currentChannel.getGroup();
            List<Channel> groupChannels = new ArrayList<>();
            for (Channel c : channelSourceList) {
                if (currentGroup.equals(c.getGroup())) {
                    groupChannels.add(c);
                }
            }
            if (groupChannels.size() <= 1) return;

            int groupIndex = -1;
            for (int i = 0; i < groupChannels.size(); i++) {
                Channel item = groupChannels.get(i);
                if (item != null && item.getName().equals(currentChannel.getName())) {
                    groupIndex = i;
                    break;
                }
            }
            if (groupIndex == -1) return;
            int prevGroupIndex = (groupIndex - 1 + groupChannels.size()) % groupChannels.size();
            Channel prevChannel = safeGetChannel(groupChannels, prevGroupIndex);
            if (prevChannel == null) return;

            int globalIndex = getChannelGlobalIndex(prevChannel.getName());
            if (globalIndex != -1) playChannel(globalIndex);
        }
    }

    public void playNext() {
        if (isReleased.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;

        synchronized (channelSourceList) {
            if (channelSourceList.isEmpty()) return;
            currentPlayIndex = Math.max(0, Math.min(currentPlayIndex, channelSourceList.size() - 1));
            Channel currentChannel = safeGetChannel(channelSourceList, currentPlayIndex);
            if (currentChannel == null) return;

            String currentGroup = currentChannel.getGroup();
            List<Channel> groupChannels = new ArrayList<>();
            for (Channel c : channelSourceList) {
                if (currentGroup.equals(c.getGroup())) {
                    groupChannels.add(c);
                }
            }
            if (groupChannels.size() <= 1) return;

            int groupIndex = -1;
            for (int i = 0; i < groupChannels.size(); i++) {
                Channel item = groupChannels.get(i);
                if (item != null && item.getName().equals(currentChannel.getName())) {
                    groupIndex = i;
                    break;
                }
            }
            if (groupIndex == -1) return;
            int nextGroupIndex = (groupIndex + 1) % groupChannels.size();
            Channel nextChannel = safeGetChannel(groupChannels, nextGroupIndex);
            if (nextChannel == null) return;

            int globalIndex = getChannelGlobalIndex(nextChannel.getName());
            if (globalIndex != -1) playChannel(globalIndex);
        }
    }

    public void switchUp() {
        if (isReleased.get()) return;
        lastSwitchDirection = "up";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) playNext();
        else playPrev();
    }

    public void switchDown() {
        if (isReleased.get()) return;
        lastSwitchDirection = "down";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) playPrev();
        else playNext();
    }

    public void playChannel(int index) {
        if (isReleased.get()) return;
        synchronized (channelSourceList) {
            if (channelSourceList.isEmpty()) return;
            // 强制边界裁剪，杜绝越界
            index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
            currentPlayIndex = index;
            Channel ch = safeGetChannel(channelSourceList, index);
            if (ch == null) return;

            String channelGroup = ch.getGroup();
            if (channelGroup != null && !channelGroup.isEmpty() && !channelGroup.equals(currentGroupName)) {
                currentGroupName = channelGroup;
                currentGroupChannelList.clear();
                for (Channel c : channelSourceList) {
                    if (channelGroup.equals(c.getGroup())) {
                        currentGroupChannelList.add(c);
                    }
                }
                if (groupListManager != null) {
                    int groupPos = groupListManager.getGroupPosition(channelGroup);
                    groupListManager.setSelectedPosition(groupPos);
                }
            }

            if (channelListManager != null) {
                if (GroupListManager.GROUP_ALL.equals(currentGroupName) || currentGroupChannelList.isEmpty()) {
                    channelListManager.setChannels(channelSourceList, index);
                } else {
                    channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
                }
            }
            if (channelListManagerEpg != null) channelListManagerEpg.setChannels(channelSourceList, index);
            if (epgManagerWrapper != null) epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);

            if (lvGroup != null) {
                lvGroup.setFocusable(true);
                lvGroup.setFocusableInTouchMode(true);
            }
            if (lvChannelList != null) {
                lvChannelList.setFocusable(true);
                lvChannelList.setFocusableInTouchMode(true);
            }
            if (channelChangeListener != null) channelChangeListener.onChannelChanged(ch, index);
        }
    }

    private boolean handleChannelLongClick(String channelName, boolean isRightPanel) {
        return false;
    }

    public boolean toggleCurrentFavorite() {
        return false;
    }

    private void onChannelClicked(int position) {
        if (isReleased.get()) return;
        synchronized (channelSourceList) {
            if (currentGroupChannelList.isEmpty()) {
                Log.w(TAG, "onChannelClicked: 当前分组频道列表为空");
                return;
            }
            if (position < 0 || position >= currentGroupChannelList.size()) {
                Log.w(TAG, "onChannelClicked: 无效位置 " + position);
                return;
            }
            if (!rightPanelOpen) {
                Channel selectedChannel = safeGetChannel(currentGroupChannelList, position);
                if (selectedChannel == null) return;
                int globalIndex = getChannelGlobalIndex(selectedChannel.getName());
                if (globalIndex != -1) {
                    lastSwitchDirection = "";
                    isSwitchingChannel = false;
                    autoSkipCount = 0;
                    playChannel(globalIndex);
                    togglePanel();
                }
            } else {
                if (position >= 0 && position < channelSourceList.size()) {
                    lastSwitchDirection = "";
                    isSwitchingChannel = false;
                    autoSkipCount = 0;
                    playChannel(position);
                }
            }
        }
    }

    public int getCurrentPlayIndex() {
        synchronized (channelSourceList) {
            return currentPlayIndex;
        }
    }

    public void setCurrentPlayIndex(int index) {
        synchronized (channelSourceList) {
            if (!channelSourceList.isEmpty()) {
                this.currentPlayIndex = Math.max(0, Math.min(index, channelSourceList.size() - 1));
            } else {
                this.currentPlayIndex = 0;
            }
        }
    }

    public void togglePanel() {
        if (isReleased.get()) return;
        boolean willOpen = !isPanelOpen();
        synchronized (channelSourceList) {
            if (willOpen) {
                if (channelListManager != null) {
                    if (GroupListManager.GROUP_ALL.equals(currentGroupName) || currentGroupChannelList.isEmpty()) {
                        channelListManager.setChannels(channelSourceList, currentPlayIndex);
                    } else {
                        channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
                    }
                }
                if (channelListManagerEpg != null) channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            }
        }
        if (panelManager != null) panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
        // 清空旧延迟任务，防止多次回调
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(() -> {
            if (isReleased.get() || activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Log.d(TAG, "togglePanel postDelayed: Activity已销毁，取消焦点操作");
                return;
            }
            if (isPanelOpen()) {
                clearAllFocusStyles();
                currentFocusPanel = "left";
                leftFocusView = "channel";
                syncFocusStyle();
                if (lvChannelList != null) {
                    lvChannelList.setFocusable(true);
                    lvChannelList.setFocusableInTouchMode(true);
                    lvChannelList.setSelection(getChannelListSelection());
                }
                if (lvChannelListEpg != null) {
                    lvChannelListEpg.setFocusable(true);
                    lvChannelListEpg.setFocusableInTouchMode(true);
                }
            } else {
                if (panelLayout != null) panelLayout.clearFocus();
                if (activity != null) {
                    androidx.media3.ui.PlayerView playerView = activity.getPlayerView();
                    if (playerView != null) {
                        playerView.setFocusable(true);
                        playerView.setFocusableInTouchMode(true);
                        playerView.requestFocus();
                        Log.d(TAG, "焦点已归还给 PlayerView");
                    } else {
                        Log.w(TAG, "togglePanel: getPlayerView() 返回 null，无法归还焦点");
                    }
                }
            }
        }, 100);
        if (panelStateListener != null) panelStateListener.onPanelStateChanged(willOpen);
    }

    public void showPanel() {
        if (isReleased.get()) return;
        if (!isPanelOpen()) togglePanel();
    }

    public void hidePanel() {
        if (isReleased.get()) return;
        if (isPanelOpen()) togglePanel();
    }

    public boolean isPanelOpen() {
        if (isReleased.get() || panelLayout == null) return false;
        return panelLayout.getVisibility() == View.VISIBLE;
    }

    public void handleFirstLaunch() {
        mIsFirstLaunch = false;
    }

    public boolean isFirstLaunch() {
        return mIsFirstLaunch;
    }

    public boolean isRightPanelOpen() {
        return rightPanelOpen;
    }

    private void onEpgButtonClicked() {
        if (isReleased.get() || !epgEnable) return;
        mainHandler.removeCallbacksAndMessages(null);
        if (!rightPanelOpen) {
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.GONE);
            if (llRightPanel != null) llRightPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = true;
            epgPanelOpen = true;
            if (channelListManagerEpg != null) channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            if (llRightPanel != null) {
                mainHandler.postDelayed(() -> {
                    if (isReleased.get() || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                    clearAllFocusStyles();
                    currentFocusPanel = "right";
                    rightFocusView = "channel";
                    syncFocusStyle();
                    if (lvChannelListEpg != null) {
                        lvChannelListEpg.setFocusable(true);
                        lvChannelListEpg.setFocusableInTouchMode(true);
                        lvChannelListEpg.setSelection(currentPlayIndex);
                    }
                }, 100);
            }
            synchronized (channelSourceList) {
                if (!channelSourceList.isEmpty()) {
                    currentPlayIndex = Math.max(0, Math.min(currentPlayIndex, channelSourceList.size() - 1));
                    Channel curr = safeGetChannel(channelSourceList, currentPlayIndex);
                    if (curr != null && epgManagerWrapper != null) {
                        epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                    }
                }
            }
        } else {
            if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
            if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            if (llLeftPanel != null) {
                mainHandler.postDelayed(() -> {
                    if (isReleased.get() || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                    clearAllFocusStyles();
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
                    if (lvChannelList != null) {
                        lvChannelList.setFocusable(true);
                        lvChannelList.setFocusableInTouchMode(true);
                        lvChannelList.setSelection(getChannelListSelection());
                    }
                }, 100);
            }
        }
    }

    private void onBackGroupClicked() {
        if (isReleased.get() || !rightPanelOpen) return;
        mainHandler.removeCallbacksAndMessages(null);
        if (llRightPanel != null) llRightPanel.setVisibility(View.GONE);
        if (llLeftPanel != null) llLeftPanel.setVisibility(View.VISIBLE);
        rightPanelOpen = false;
        epgPanelOpen = false;
        if (llLeftPanel != null) {
            mainHandler.postDelayed(() -> {
                if (isReleased.get() || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                clearAllFocusStyles();
                currentFocusPanel = "left";
                leftFocusView = "channel";
                syncFocusStyle();
                if (lvChannelList != null) {
                    lvChannelList.setFocusable(true);
                    lvChannelList.setFocusableInTouchMode(true);
                    lvChannelList.setSelection(getChannelListSelection());
                }
            }, 100);
        }
    }

    public boolean isEpgPanelOpen() {
        return epgPanelOpen;
    }

    public void setCurrentDateIndex(int index) {
        if (isReleased.get()) return;
        this.currentSelectedDateIndex = index;
        if (panelManager != null) panelManager.setCurrentDateIndex(index);
        synchronized (channelSourceList) {
            if (!channelSourceList.isEmpty()) {
                currentPlayIndex = Math.max(0, Math.min(currentPlayIndex, channelSourceList.size() - 1));
                Channel curr = safeGetChannel(channelSourceList, currentPlayIndex);
                if (curr != null && epgManagerWrapper != null) {
                    epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
                }
            }
        }
    }

    public int getCurrentSelectedDateIndex() {
        return currentSelectedDateIndex;
    }

    private int getChannelListSelection() {
        synchronized (channelSourceList) {
            if (GroupListManager.GROUP_ALL.equals(currentGroupName) || currentGroupChannelList.isEmpty()) {
                return Math.max(0, Math.min(currentPlayIndex, channelSourceList.size() - 1));
            } else {
                if (channelSourceList.isEmpty()) return 0;
                currentPlayIndex = Math.max(0, Math.min(currentPlayIndex, channelSourceList.size() - 1));
                Channel currentChannel = safeGetChannel(channelSourceList, currentPlayIndex);
                if (currentChannel == null) return 0;
                for (int i = 0; i < currentGroupChannelList.size(); i++) {
                    Channel item = currentGroupChannelList.get(i);
                    if (item != null && item.getName().equals(currentChannel.getName())) {
                        return i;
                    }
                }
                return 0;
            }
        }
    }

    public boolean handleBackPressed() {
        if (isReleased.get()) return false;
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
        if (isReleased.get() || !canAutoSkip()) return false;
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

    public boolean dispatchKeyEvent(int keyCode) {
        if (isReleased.get() || panelLayout == null || panelLayout.getVisibility() != View.VISIBLE) return false;
        View currentFocus = panelLayout.findFocus();
        if (currentFocus == null) return false;

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (currentFocus instanceof ListView) {
                ListView lv = (ListView) currentFocus;
                int pos = lv.getSelectedItemPosition();
                if (pos == -1) {
                    pos = lv.getFirstVisiblePosition();
                    if (pos == -1) pos = 0;
                }
                int count = lv.getCount();
                if (count == 0) return true;
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
                return true;
            } else {
                return false;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (currentFocus == lvChannelList && lvChannelList != null) {
                int pos = lvChannelList.getSelectedItemPosition();
                if (pos >= 0 && pos < lvChannelList.getCount()) {
                    onChannelClicked(pos);
                    return true;
                }
            } else if (currentFocus == lvGroup && lvGroup != null) {
                int pos = lvGroup.getSelectedItemPosition();
                if (pos >= 0 && pos < lvGroup.getCount()) {
                    onGroupClicked(pos);
                    return true;
                }
            } else if (currentFocus == lvChannelListEpg && lvChannelListEpg != null) {
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

        if (!rightPanelOpen) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (currentFocus == lvGroup && lvChannelList != null) {
                        lvChannelList.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvChannelList && btnShowEpg != null) {
                        btnShowEpg.requestFocus();
                        return true;
                    }
                    if (currentFocus == btnShowEpg) {
                        onEpgButtonClicked();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (currentFocus == btnShowEpg && lvChannelList != null) {
                        lvChannelList.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvChannelList && lvGroup != null) {
                        lvGroup.requestFocus();
                        return true;
                    }
                    break;
            }
        } else {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (currentFocus == lvEpg && lvDate != null) {
                        lvDate.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvDate && lvChannelListEpg != null) {
                        lvChannelListEpg.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvChannelListEpg && btnBackGroup != null) {
                        btnBackGroup.requestFocus();
                        return true;
                    }
                    if (currentFocus == btnBackGroup) {
                        onBackGroupClicked();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (currentFocus == btnBackGroup && lvChannelListEpg != null) {
                        lvChannelListEpg.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvChannelListEpg && lvDate != null) {
                        lvDate.requestFocus();
                        return true;
                    }
                    if (currentFocus == lvDate && lvEpg != null) {
                        lvEpg.requestFocus();
                        return true;
                    }
                    break;
            }
        }
        return false;
    }

    public void clearPanelFocus() {
        if (isReleased.get() || panelLayout == null) return;
        panelLayout.clearFocus();
    }

    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    // 完整释放资源，杜绝内存泄漏、页面销毁后回调崩溃
    public void release() {
        if (isReleased.compareAndSet(false, true)) {
            Log.d(TAG, "release: 级联清理所有组件引用");
            // 清空主线程所有延迟任务
            mainHandler.removeCallbacksAndMessages(null);

            // 释放管理器
            if (groupListManager != null) {
                groupListManager.release();
                groupListManager = null;
            }
            if (channelListManager != null) {
                channelListManager.release();
                channelListManager = null;
            }
            if (channelListManagerEpg != null) {
                channelListManagerEpg.release();
                channelListManagerEpg = null;
            }
            if (dateListManager != null) {
                dateListManager.release();
                dateListManager = null;
            }
            if (epgManagerWrapper != null) {
                epgManagerWrapper.release();
                epgManagerWrapper = null;
            }
            panelManager = null;

            // 清空回调监听
            channelChangeListener = null;
            panelStateListener = null;

            // 清空数据源
            synchronized (channelSourceList) {
                channelSourceList.clear();
                channelNameIndexMap.clear();
                currentGroupChannelList.clear();
            }

            // 移除所有View监听，切断引用
            if (lvGroup != null) {
                lvGroup.setAdapter(null);
                lvGroup.setOnItemClickListener(null);
                lvGroup.setOnItemSelectedListener(null);
                lvGroup.setOnFocusChangeListener(null);
            }
            if (lvChannelList != null) {
                lvChannelList.setAdapter(null);
                lvChannelList.setOnItemClickListener(null);
                lvChannelList.setOnItemSelectedListener(null);
                lvChannelList.setOnFocusChangeListener(null);
            }
            if (lvChannelListEpg != null) {
                lvChannelListEpg.setAdapter(null);
                lvChannelListEpg.setOnItemClickListener(null);
                lvChannelListEpg.setOnItemSelectedListener(null);
                lvChannelListEpg.setOnFocusChangeListener(null);
            }
            if (lvDate != null) {
                lvDate.setAdapter(null);
                lvDate.setOnItemClickListener(null);
                lvDate.setOnItemSelectedListener(null);
            }
            if (lvEpg != null) {
                lvEpg.setAdapter(null);
                lvEpg.setOnItemClickListener(null);
                lvEpg.setOnItemSelectedListener(null);
            }
            if (btnShowEpg != null) {
                btnShowEpg.setOnClickListener(null);
                btnShowEpg.setOnFocusChangeListener(null);
            }
            if (btnBackGroup != null) {
                btnBackGroup.setOnClickListener(null);
                btnBackGroup.setOnFocusChangeListener(null);
            }

            // 清空页面引用
            activity = null;
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
        }
    }
}
