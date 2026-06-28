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
import com.tv.live.SettingsActivity;
import com.tv.live.config.AppConfig;
import com.tv.live.widget.ChannelListManager;
import com.tv.live.widget.DateListManager;
import com.tv.live.widget.EpgManagerWrapper;
import com.tv.live.widget.GroupListManager;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 频道面板控制器【内存泄漏修复完整版】
 */
public class ChannelPanelController {
    private static final long CHANNEL_COOLDOWN = 300;
    private static final int MAX_AUTO_SKIP = 10;
    private static final long FIRST_LAUNCH_HIDE_DELAY_MS = 5000;
    private static final long NORMAL_HIDE_DELAY_MS = 10000;

    // 修复：弱引用上下文，移除强Context
    private WeakReference<Context> contextRef;

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

    // ===================== 全部静态弱引用监听器/任务（消除匿名泄漏） =====================
    // 分组点击
    private static class GroupItemClick implements AdapterView.OnItemClickListener {
        private final WeakReference<ChannelPanelController> ctrlRef;
        public GroupItemClick(ChannelPanelController ctrl) { ctrlRef = new WeakReference<>(ctrl); }
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
            ChannelPanelController c = ctrl.get();
            if (c != null) c.onGroupClicked(pos);
        }
    }
    // 普通频道列表点击
    private static class ChannelItemClick implements AdapterView.OnItemClickListener {
        private final WeakReference<ChannelPanelController> ctrlRef;
        public ChannelItemClick(ChannelPanelController ctrl) { ctrlRef = new WeakReference<>(ctrl); }
        @Override
        public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
            ChannelPanelController c = ctrl.get();
            if (c != null) c.onChannelClicked(pos);
        }
    }
    // EPG频道列表点击
    private static class EpgChannelItemClick implements AdapterView.OnItemClickListener {
        private final WeakReference<ChannelPanelController> ctrlRef;
        public EpgChannelItemClick(ChannelPanelController ctrl) { ctrlRef = new WeakReference<>(ctrl); }
        @Override
        public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
            ChannelPanelController c = ctrl.get();
            if (c != null) c.onChannelClicked(pos);
        }
    }
    // 频道长按回调包装
    private static class LongClickWrapper implements ChannelListManager.OnChannelLongClickListener {
        private final WeakReference<ChannelPanelController> ctrlRef;
        private final boolean isEpgPanel;
        public LongClickWrapper(ChannelPanelController ctrl, boolean epg) {
            ctrlRef = new WeakReference<>(ctrl);
            isEpgPanel = epg;
        }
        @Override
        public boolean onChannelLongClick(String channelName, int position) {
            ChannelPanelController c = ctrl.get();
            return c != null ? c.handleChannelLongClick(channelName, isEpgPanel) : false;
        }
    }
    // EPG按钮点击
    private static class EpgBtnClick implements View.OnClickListener {
        private final WeakReference<ChannelPanelController> ctrlRef;
        public EpgBtnClick(ChannelPanelController ctrl) { ctrlRef = new WeakReference<>(ctrl); }
        @Override
        public void onClick(View v) {
            ChannelPanelController c = ctrl.get();
            if (c != null) c.onEpgButtonClicked();
        }
    }
    // 返回分组按钮点击
    private static class BackGroupClick implements View.OnClickListener {
        private final WeakReference<ChannelPanelController> ctrlRef;
        public BackGroupClick(ChannelPanelController ctrl) { ctrlRef = new WeakReference<>(ctrl); }
        @Override
        public void onClick(View v) {
            ChannelPanelController c = ctrl.get();
            if (c != null) c.onBackGroupClicked();
        }
    }
    // 各类焦点监听统一静态类
    private static class FocusListener implements View.OnFocusChangeListener {
        private final WeakReference<ChannelPanelController> ctrlRef;
        private final String panelTag;
        private final String viewTag;
        public FocusListener(ChannelPanelController ctrl, String pTag, String vTag) {
            ctrlRef = new WeakReference<>(ctrl);
            panelTag = pTag;
            viewTag = vTag;
        }
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            ChannelPanelController c = ctrl.get();
            if (c != null && hasFocus) {
                c.currentFocusPanel = panelTag;
                if ("left".equals(panelTag)) c.leftFocusView = viewTag;
                else c.rightFocusView = viewTag;
                c.syncFocusStyle();
            }
        }
    }
    // 面板自动隐藏定时任务
    private static class AutoHideRunnable implements Runnable {
        private final WeakReference<ChannelPanelController> ctrlRef;
        public AutoHideRunnable(ChannelPanelController ctrl) { ctrlRef = new WeakReference<>(ctrl); }
        @Override
        public void run() {
            ChannelPanelController c = ctrl.get();
            if (c != null) c.hidePanel();
        }
    }
    // 打开面板后延迟焦点恢复任务
    private static class RestoreFocusRunnable implements Runnable {
        private final WeakReference<ChannelPanelController> ctrlRef;
        public RestoreFocusRunnable(ChannelPanelController ctrl) { ctrlRef = new WeakReference<>(ctrl); }
        @Override
        public void run() {
            ChannelPanelController c = ctrl.get();
            if (c == null) return;
            c.clearAllFocusStyles();
            c.currentFocusPanel = "left";
            c.leftFocusView = "channel";
            c.syncFocusStyle();
            c.lvChannelList.requestFocus();
            c.lvChannelList.setSelection(c.getChannelListSelection());
            c.resetAutoHide();
        }
    }
    // 右侧面板切换延迟任务
    private static class RightPanelRestoreRunnable implements Runnable {
        private final WeakReference<ChannelPanelController> ctrlRef;
        public RightPanelRestoreRunnable(ChannelPanelController ctrl) { ctrlRef = new WeakReference<>(ctrl); }
        @Override
        public void run() {
            ChannelPanelController c = ctrl.get();
            if (c == null) return;
            c.clearAllFocusStyles();
            c.currentFocusPanel = "right";
            c.rightFocusView = "channel";
            c.syncFocusStyle();
            c.lvChannelListEpg.requestFocus();
            c.lvChannelListEpg.setSelection(c.currentPlayIndex);
        }
    }

    // 构造：弱引用包装ApplicationContext
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
        this.contextRef = new WeakReference<>(context.getApplicationContext());
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

        mAutoHideHandler = new Handler(Looper.getMainLooper());
        mAutoHideRunnable = new AutoHideRunnable(this);

        initClickListeners();
        initFocusListeners();
    }

    // 安全获取上下文
    private Context getContext() {
        return contextRef != null ? contextRef.get() : null;
    }

    // 初始化点击监听器（全部使用静态弱引用类）
    private void initClickListeners() {
        lvGroup.setOnItemClickListener(new GroupItemClick(this));
        lvChannelList.setOnItemClickListener(new ChannelItemClick(this));
        lvChannelListEpg.setOnItemClickListener(new EpgChannelItemClick(this));

        channelListManager.setOnChannelLongClickListener(new LongClickWrapper(this, false));
        channelListManagerEpg.setOnChannelLongClickListener(new LongClickWrapper(this, true));

        btnShowEpg.setOnClickListener(new EpgBtnClick(this));
        btnBackGroup.setOnClickListener(new BackGroupClick(this));
    }

    // 初始化焦点监听（统一静态弱引用类）
    private void initFocusListeners() {
        lvGroup.setOnFocusChangeListener(new FocusListener(this, "left", "group"));
        lvChannelList.setOnFocusChangeListener(new FocusListener(this, "left", "channel"));
        btnShowEpg.setOnFocusChangeListener(new FocusListener(this, "left", "epgBtn"));
        lvChannelListEpg.setOnFocusChangeListener(this, "right", "channel");
        lvDate.setOnFocusChangeListener(new FocusListener(this, "right", "date"));
        lvEpg.setOnFocusChangeListener(new FocusListener(this, "right", "epg"));
        btnBackGroup.setOnFocusChangeListener(new FocusListener(this, "right", "backBtn"));
    }

    private void initAutoHide() {
        mAutoHideHandler = new Handler(Looper.getMainLooper());
        mAutoHideRunnable = new AutoHideRunnable(this);
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
            if ("group".equals(leftFocusView)) groupListManager.setFocused(true);
            else if ("channel".equals(leftFocusView)) channelListManager.setFocused(true);
            else if ("epgBtn".equals(leftFocusView)) {
                btnShowEpg.setTextColor(0xFFFFFFFF);
                btnShowEpg.setTypeface(null, Typeface.BOLD);
                btnShowEpg.setBackgroundColor(0x3340A9FF);
            }
        } else if ("right".equals(currentFocusPanel)) {
            if ("channel".equals(rightFocusView)) channelListManagerEpg.setFocused(true);
            else if ("date".equals(rightFocusView)) dateListManager.setFocused(true);
            else if ("backBtn".equals(rightFocusView)) {
                btnBackGroup.setTextColor(0xFFFFFFFF);
                btnBackGroup.setTypeface(null, Typeface.BOLD);
                btnBackGroup.setBackgroundColor(0x3340A9FF);
            }
        }
    }

    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = channels;
        int favoriteCount = 0;
        int recentCount = 0;
        Context ctx = getContext();
        try {
            AppConfig appConfig = AppConfig.getInstance(ctx);
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();
            for (String name : favorites) {
                for (Channel c : channels) {
                    if (name.equals(c.getName())) { favoriteCount++; break; }
                }
            }
            for (String name : recent) {
                for (Channel c : channels) {
                    if (name.equals(c.getName())) { recentCount++; break; }
                }
            }
        } catch (Exception e) {}
        groupListManager.setGroups(channels, favoriteCount, recentCount);
        channelListManager.setChannels(channels, currentPlayIndex);
        channelListManagerEpg.setChannels(channels, currentPlayIndex);
    }

    private void onGroupClicked(int position) {
        groupListManager.setSelectedPosition(position);
        lvGroup.setItemChecked(position, true);
        lvGroup.setSelection(position);
        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;
        currentGroupChannelList.clear();
        if (GroupListManager.GROUP_ALL.equals(groupName)) {
            currentGroupChannelList.addAll(channelSourceList);
            channelListManager.setChannels(channelSourceList, currentPlayIndex);
        } else if (GroupListManager.GROUP_FAVORITE.equals(groupName)) {
            loadFavoriteChannels();
        } else if (GroupListManager.GROUP_RECENT.equals(groupName)) {
            loadRecentChannels();
        } else {
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) currentGroupChannelList.add(c);
            }
            channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
        }
    }

    private void loadFavoriteChannels() {
        Context ctx = getContext();
        try {
            AppConfig appConfig = AppConfig.getInstance(ctx);
            List<String> favorites = appConfig.getFavoriteChannels();
            for (String name : favorites) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) currentGroupChannelList.add(c);
                }
            }
        } catch (Exception ignored) {}
        String currentChannelName = currentPlayIndex >= 0 && currentPlayIndex < channelSourceList ? channelSourceList.get(currentPlayIndex).getName() : "";
        channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
    }

    private void loadRecentChannels() {
        Context ctx = getContext();
        try {
            AppConfig appConfig = AppConfig.getInstance(ctx);
            List<String> recent = appConfig.getRecentChannels();
            for (String name : recent) {
                for (Channel c : channelSourceList) {
                    if (name.equals(c.getName())) currentGroupChannelList.add(c);
                }
            }
        } catch (Exception ignored) {}
        String currentChannelName = currentPlayIndex >= 0 && currentPlayIndex < channelSourceList ? channelSourceList.get(currentPlayIndex).getName() : "";
        channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
    }

    public String getCurrentGroupName() { return currentGroupName; }
    public List<Channel> getCurrentGroupChannels() { return currentGroupChannelList; }
    public void setEpgEnable(boolean enable) { epgEnable = enable; }

    public void playPrev() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentChannel.getGroup().equals(c.getGroup())) groupChannels.add(c);
        }
        if (groupChannels.size() <= 1) return;
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) { groupIndex = i; break; }
        }
        if (groupIndex == -1) return;
        int prevIdx = (groupIndex - 1 + groupChannels.size()) % groupChannels.size();
        int globalIdx = channelSourceList.indexOf(groupChannels.get(prevIdx));
        if (globalIdx != -1) playChannel(globalIdx);
    }

    public void playNext() {
        long now = System.currentTimeMillis();
        if (now - lastChannelChangeTime < CHANNEL_COOLDOWN) return;
        lastChannelChangeTime = now;
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        List<Channel> groupChannels = new ArrayList<>();
        for (Channel c : channelSourceList) {
            if (currentChannel.getGroup().equals(c.getGroup())) groupChannels.add(c);
        }
        if (groupChannels.size() <= 1) return;
        int groupIndex = -1;
        for (int i = 0; i < groupChannels.size(); i++) {
            if (groupChannels.get(i).getName().equals(currentChannel.getName())) { groupIndex = i; break; }
        }
        if (groupIndex == -1) return;
        int nextIdx = (groupIndex + 1) % groupChannels.size();
        int globalIdx = channelSourceList.indexOf(groupChannels.get(nextIdx));
        if (globalIdx != -1) playChannel(globalIdx);
    }

    public void switchUp() {
        lastSwitchDirection = "up";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) playNext(); else playPrev();
    }

    public void switchDown() {
        lastSwitchDirection = "down";
        isSwitchingChannel = true;
        autoSkipCount = 0;
        if (isReverse) playPrev(); else playNext();
    }

    public void playChannel(int index) {
        if (channelSourceList == null || channelSourceList.isEmpty()) return;
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        Channel ch = channelSourceList.get(index);
        if (ch == null) return;
        String channelGroup = ch.getGroup();
        boolean isSpecial = GroupListManager.GROUP_ALL.equals(currentGroupName)
                || GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                || GroupListManager.GROUP_RECENT.equals(currentGroupName)
                || currentGroupName.isEmpty();
        if (!isSpecial && !channelGroup.equals(currentGroupName)) {
            currentGroupName = channelGroup;
            currentGroupChannelList.clear();
            for (Channel c : channelSourceList) {
                if (channelGroup.equals(c.getGroup())) currentGroupChannelList.add(c);
            }
            int groupPos = groupListManager.getGroupPosition(channelGroup);
            groupListManager.setSelectedPosition(groupPos);
        }
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) || currentGroupName.isEmpty() || currentGroupChannelList.isEmpty()) {
            channelListManager.setChannels(channelSourceList, index);
        } else if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName) || GroupListManager.GROUP_RECENT.equals(currentGroupName)) {
            channelListManager.setFilteredChannels(currentGroupChannel, ch.getName());
        } else {
            channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
        }
        channelListManagerEpg.setChannels(channelSourceList, index);
        epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
        if (channelChangeListener != null) channelChangeListener.onChannelChanged(ch, index);
        addToRecent(ch.getName());
    }

    private void addToRecent(String channelName) {
        Context ctx = getContext();
        try {
            AppConfig appConfig = AppConfig.getInstance(ctx);
            appConfig.addRecentChannel(channelName);
            int favCount = 0, recCount = 0;
            List<String> favs = appConfig.getFavoriteChannels();
            List<String> recents = appConfig.getRecentChannels();
            for (String n : favs) {
                for (Channel c : channelSourceList) {
                    if (n.equals(c.getName())) { favCount++; break; }
                }
            }
            for (String n : recents) {
                for (Channel c : channelSourceList) {
                    if (n.equals(c.getName())) { recCount++; break; }
                }
            }
            groupListManager.updateSpecialGroupCount(favCount, recCount);
        } catch (Exception ignored) {}
    }

    private boolean handleChannelLongClick(String channelName, boolean isRightPanel) {
        Context ctx = getContext();
        try {
            AppConfig appConfig = AppConfig.getInstance(ctx);
            boolean isFav = appConfig.toggleFavorite(channelName);
            refreshFavGroup();
            if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)) {
                currentGroupChannelList.clear();
                List<String> favs = appConfig.getFavoriteChannels();
                for (String n : favs) {
                    for (Channel c : channelSourceList) {
                        if (n.equals(c.getName())) currentGroupChannelList.add(c);
                    }
                }
                if (!isRightPanel) channelListManager.setFilteredChannels(currentGroupChannelList, channelName);
                else channelListManagerEpg.setFilteredChannels(currentGroupChannelList, channelName);
            }
            return isFav;
        } catch (Exception e) { return false; }
    }

    private void refreshFavGroup() {
        Context ctx = getContext();
        try {
            AppConfig appConfig = AppConfig.getInstance(ctx);
            int favCount = 0, recCount = 0;
            List<String> favs = appConfig.getFavoriteChannels();
            List<String> recents = appConfig.getRecentChannels();
            for (String n : favs) {
                for (Channel c : channelSourceList) {
                    if (n.equals(c.getName())) { favCount++; break; }
                }
            }
            for (String n : recents) {
                for (Channel c : channelSourceList) {
                    if (n.equals(c.getName())) { recCount++; break; }
                }
            }
            groupListManager.updateSpecialGroupCount(favCount, recCount);
        } catch (Exception ignored) {}
    }

    public boolean toggleCurrentFavorite() {
        if (channelSourceList == null || currentPlayIndex >= channelSourceList.size()) return false;
        Channel curr = channelSourceList.get(currentPlayIndex);
        Context ctx = getContext();
        try {
            AppConfig appConfig = AppConfig.getInstance(ctx);
            boolean fav = appConfig.toggleFavorite(curr.getName());
            refreshFavGroup();
            if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)) {
                currentGroupChannelList.clear();
                List<String> favs = appConfig.getFavoriteChannels();
                for (String n : favs) {
                    for (Channel c : channelSourceList) {
                        if (n.equals(c.getName())) currentGroupChannelList.add(c);
                    }
                }
                channelListManager.setFilteredChannels(currentGroupChannelList, curr.getName());
            }
            return fav;
        } catch (Exception e) { return false; }
    }

    private void onChannelClicked(int position) {
        if (!currentGroupChannelList.isEmpty() && !rightPanelOpen) {
            Channel sel = currentGroupChannelList.get(position);
            int idx = channelSourceList.indexOf(sel);
            if (idx != -1) {
                lastSwitchDirection = "";
                isSwitchingChannel = false;
                autoSkipCount = 0;
                playChannel(idx);
                togglePanel();
            }
        } else if (position < channelSourceList.size()) {
            lastSwitchDirection = "";
            isSwitchingChannel = false;
            autoSkipCount = 0;
            playChannel(position);
        }
    }

    public int getCurrentPlayIndex() { return currentPlayIndex; }
    public void setCurrentPlayIndex(int index) { currentPlayIndex = index; }
    public void setTotalChannelCount(int count) {}

    public void togglePanel() {
        boolean isOpen = isPanelOpen();
        panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
        if (!isOpen) {
            panelLayout.post(new RestoreFocusRunnable(this));
        } else {
            cancelAutoHide();
        }
        if (panelStateListener != null) panelStateListener.onPanelStateChanged(!isOpen);
    }

    public void showPanel() { if (!isPanelOpen()) togglePanel(); }
    public void hidePanel() { if (isPanelOpen()) togglePanel(); }
    public boolean isPanelOpen() { return panelLayout.getVisibility() == View.VISIBLE; }

    public void resetAutoHide() {
        if (!mAutoHideEnabled) return;
        if (mAutoHideHandler != null) {
            mAutoHideHandler.removeCallbacks(mAutoHideRunnable);
            mAutoHideHandler.postDelayed(mAutoHideRunnable, mAutoHideDelayMs);
        }
    }

    public void cancelAutoHide() {
        if (mAutoHideHandler != null) mAutoHideHandler.removeCallbacks(mAutoHideRunnable);
    }

    public void setAutoHideDelay(long delayMs) { mAutoHideDelayMs = delayMs; }
    public void setAutoHideEnabled(boolean enabled) {
        mAutoHideEnabled = enabled;
        if (!enabled) cancelAutoHide();
    }

    public void handleFirstLaunch() {
        if (!mIsFirstLaunch) return;
        SettingsActivity.logOperation("【面板】首次启动延迟");
        setAutoHideDelay(FIRST_LAUNCH_HIDE_DELAY_MS);
        resetAutoHide();
        setAutoHideDelay(NORMAL_HIDE_DELAY_MS);
        mIsFirstLaunch = false;
    }
    public boolean isFirstLaunch() { return mIsFirstLaunch; }
    public boolean isRightPanelOpen() { return rightPanelOpen; }

    private void onEpgButtonClicked() {
        if (!epgEnable) return;
        if (!rightPanelOpen) {
            llLeftPanel.setVisibility(View.GONE);
            llRightPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = true;
            epgPanelOpen = true;
            llRightPanel.post(new RightPanelRestoreRunnable(this));
            if (!channelSourceList.isEmpty() && currentPlayIndex >= 0) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        } else {
            llRightPanel.setVisibility(View.GONE);
            llLeftPanel.setVisibility(View.VISIBLE);
            rightPanelOpen = false;
            epgPanelOpen = false;
            llLeftPanel.post(new RestoreFocusRunnable(this));
        }
    }

    private void onBackGroupClicked() {
        if (!rightPanelOpen) return;
        llRightPanel.setVisibility(View.GONE);
        llLeftPanel.setVisibility(View.VISIBLE);
        rightPanelOpen = false;
        epgPanelOpen = false;
        llLeftPanel.post(new RestoreFocusRunnable(this));
    }

    public boolean isEpgPanelOpen() { return epgPanelOpen; }
    public void setCurrentDateIndex(int index) {
        currentSelectedDateIndex = index;
        panelManager.setCurrentDateIndex(index);
        if (!channelSourceList.isEmpty() && currentPlayIndex >= 0) {
            Channel curr = channelSourceList.get(currentPlayIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
        }
    }
    public int getCurrentSelectedDateIndex() { return currentSelectedDateIndex; }

    private int getChannelListSelection() {
        if (GroupListManager.GROUP_ALL.equals(currentGroupName) || currentGroupChannel.isEmpty()) return currentPlayIndex;
        if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName) || GroupListManager.GROUP_RECENT.equals(currentGroupName)) {
            String target = channelSourceList.get(currentPlayIndex).getName();
            for (int i = 0; i < currentGroupChannelList.size(); i++) {
                if (currentGroupChannelList.get(i).getName().equals(target)) return i;
            }
            return 0;
        }
        Channel curr = channelSourceList.get(currentPlayIndex);
        for (int i = 0; i < currentGroupChannelList.size(); i++) {
            if (currentGroupChannelList.get(i).getName().equals(curr.getName())) return i;
        }
        return 0;
    }

    public boolean handleBackPressed() {
        if (isPanelOpen()) {
            if (rightPanelOpen) { onBackGroupClicked(); return true; }
            hidePanel(); return true;
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
            if (isReverse) playNext(); else playPrev();
        } else {
            if (isReverse) playPrev(); else playNext();
        }
        return true;
    }

    public void setReverse(boolean reverse) { isReverse = reverse; }
    public boolean isReverse() { return isReverse; }
    public boolean dispatchKeyEvent(int keyCode) { return false; }

    public void setOnChannelChangeListener(OnChannelChangeListener listener) { channelChangeListener = listener; }
    public void setOnPanelStateListener(OnPanelStateListener listener) { panelStateListener = listener; }

    // ========== 规范完整release() 全部资源清理 ==========
public void release() {
    // 1 清空Handler全部定时任务并置空
    if (mAutoHideHandler != null) {
        mAutoHideHandler.removeCallbacksAndMessages(null);
        mAutoHideHandler = null;
    }
    mAutoHideRunnable = null;

    // 2 清空所有监听器回调
    channelChangeListener = null;
    panelStateListener = null; // 修复：补充置空操作，替代原无效的 panelStateListener;

    // 3 解绑全部View点击/焦点监听，切断View持有引用
    if (lvGroup != null) lvGroup.setOnItemClickListener(null);
    if (lvChannelList != null) lvChannelList.setOnItemClickListener(null);
    if (lvChannelListEpg != null) lvChannelListEpg.setOnItemClickListener(null);
    if (btnShowEpg != null) btnShowEpg.setOnClickListener(null);
    if (btnBackGroup != null) btnBackGroup.setOnClickListener(null);
    // 焦点监听全部解绑
    if (lvGroup != null) lvGroup.setOnFocusChangeListener(null);
    if (lvChannelList != null) lvChannelList.setOnFocusChangeListener(null);
    if (btnShowEpg != null) btnShowEpg.setOnFocusChangeListener(null);
    if (lvChannelListEpg != null) lvChannelListEpg.setOnFocusChangeListener(null);
    if (lvDate != null) lvDate.setOnFocusChangeListener(null);
    if (lvEpg != null) lvEpg.setOnFocusChangeListener(null);
    if (btnBackGroup != null) btnBackGroup.setOnFocusChangeListener(null);

    // 4 清空子管理器引用，断开链式持有
    groupListManager = null;
    channelListManager = null;
    channelListManagerEpg = null;
    dateListManager = null;
    epgManagerWrapper = null;
    panelManager = null;

    // 5 清空频道数据集合
    if (channelSourceList != null) {
        channelSourceList.clear();
        channelSourceList = null;
    }
    if (currentGroupChannelList != null) {
        currentGroupChannelList.clear();
        currentGroupChannelList = null;
    }

    // 6 清空弱引用上下文
    if (contextRef != null) {
        contextRef.clear();
        contextRef = null;
    }

    // 7 全部UI视图置空，切断页面View引用链
    panelLayout = null;
    llLeftPanel = null;
    llRightPanel = null;
    lvGroup = null;
    lvChannelList = null;
    lvChannelListEpg = null;
    lvDate = null;
    lvEpg = null;
    btnShowEpg = null;
    btnBackGroup = null; // 修复：补充置空操作（原代码漏写 = null）

    // 8 重置所有状态标记
    currentGroupName = "";
    lastSwitchDirection = "";
    currentFocusPanel = "left";
    leftFocusView = "channel";
    rightFocusView = "channel";
    mIsFirstLaunch = true;
    isReverse = false;
    rightPanelOpen = false;
    epgPanelOpen = false;
    epgEnable = true;
    autoSkipCount = 0;
    isSwitchingChannel = false;
    lastChannelChangeTime = 0;
    mAutoHideEnabled = true;
    mAutoHideDelayMs = NORMAL_HIDE_DELAY_MS;
    currentPlayIndex = 0;
    currentSelectedDateIndex = 0;
   }
}
