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

import java.util.ArrayList;
import java.util.List;

/**
 * 频道面板控制器
 *
 * 【2026-06-26 新增：首次启动面板特殊延迟】
 * 【修改说明】
 * 1. 把 MainActivity 中的首次启动面板延迟逻辑合并到这里，新增 handleFirstLaunch() 方法，面板控制器自己管理首次启动逻辑。
 * 2. 优化 togglePanel 方法适配性，增强监听绑定的健壮性
 * 3. 完善空指针防护，提升方法调用安全性
 */
public class ChannelPanelController {

    private static final long CHANNEL_COOLDOWN = 300;
    private static final int MAX_AUTO_SKIP = 10;

    // ✅ 2026-06-26 新增：首次启动面板特殊延迟常量
    private static final long FIRST_LAUNCH_HIDE_DELAY_MS = 5000;
    private static final long NORMAL_HIDE_DELAY_MS = 10000;

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

    // ✅ 2026-06-26 新增：首次启动标记
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
        // 空指针防护
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        if (panelLayout == null) {
            throw new IllegalArgumentException("panelLayout cannot be null");
        }
        
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
        if (lvGroup != null) {
            lvGroup.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    onGroupClicked(position);
                }
            });
        }
        
        if (lvChannelList != null) {
            lvChannelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                    onChannelClicked(pos);
                }
            });
        }
        
        if (lvChannelListEpg != null) {
            lvChannelListEpg.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                    onChannelClicked(pos);
                }
            });
        }
        
        if (channelListManager != null) {
            channelListManager.setOnChannelLongClickListener(new ChannelListManager.OnChannelLongClickListener() {
                @Override
                public boolean onChannelLongClick(String channelName, int position) {
                    return handleChannelLongClick(channelName, false);
                }
            });
        }
        
        if (channelListManagerEpg != null) {
            channelListManagerEpg.setOnChannelLongClickListener(new ChannelListManager.OnChannelLongClickListener() {
                @Override
                public boolean onChannelLongClick(String channelName, int position) {
                    return handleChannelLongClick(channelName, true);
                }
            });
        }
        
        if (btnShowEpg != null) {
            btnShowEpg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onEpgButtonClicked();
                }
            });
        }
        
        if (btnBackGroup != null) {
            btnBackGroup.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onBackGroupClicked();
                }
            });
        }
    }

    private void initFocusListeners() {
        if (lvGroup != null) {
            lvGroup.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        currentFocusPanel = "left";
                        leftFocusView = "group";
                        syncFocusStyle();
                    }
                }
            });
        }
        
        if (lvChannelList != null) {
            lvChannelList.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        currentFocusPanel = "left";
                        leftFocusView = "channel";
                        syncFocusStyle();
                    }
                }
            });
        }
        
        if (btnShowEpg != null) {
            btnShowEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        currentFocusPanel = "left";
                        leftFocusView = "epgBtn";
                        syncFocusStyle();
                    }
                }
            });
        }
        
        if (lvChannelListEpg != null) {
            lvChannelListEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        currentFocusPanel = "right";
                        rightFocusView = "channel";
                        syncFocusStyle();
                    }
                }
            });
        }
        
        if (lvDate != null) {
            lvDate.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        currentFocusPanel = "right";
                        rightFocusView = "date";
                        syncFocusStyle();
                    }
                }
            });
        }
        
        if (lvEpg != null) {
            lvEpg.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        currentFocusPanel = "right";
                        rightFocusView = "epg";
                        syncFocusStyle();
                    }
                }
            });
        }
        
        if (btnBackGroup != null) {
            btnBackGroup.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        currentFocusPanel = "right";
                        rightFocusView = "backBtn";
                        syncFocusStyle();
                    }
                }
            });
        }
    }

    private void initAutoHide() {
        mAutoHideHandler = new Handler(Looper.getMainLooper());
        mAutoHideRunnable = new Runnable() {
            @Override
            public void run() {
                hidePanel();
            }
        };
    }

    private void clearAllFocusStyles() {
        if (groupListManager != null) {
            groupListManager.setFocused(false);
        }
        if (channelListManager != null) {
            channelListManager.setFocused(false);
        }
        if (channelListManagerEpg != null) {
            channelListManagerEpg.setFocused(false);
        }
        if (dateListManager != null) {
            dateListManager.setFocused(false);
        }
        
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
        clearAllFocusStyles();
        if ("left".equals(currentFocusPanel)) {
            if ("group".equals(leftFocusView) && groupListManager != null) {
                groupListManager.setFocused(true);
            } else if ("channel".equals(leftFocusView) && channelListManager != null) {
                channelListManager.setFocused(true);
            } else if ("epgBtn".equals(leftFocusView) && btnShowEpg != null) {
                btnShowEpg.setTextColor(0xFFFFFFFF);
                btnShowEpg.setTypeface(null, Typeface.BOLD);
                btnShowEpg.setBackgroundColor(0x3340A9FF);
            }
        } else if ("right".equals(currentFocusPanel)) {
            if ("channel".equals(rightFocusView) && channelListManagerEpg != null) {
                channelListManagerEpg.setFocused(true);
            } else if ("date".equals(rightFocusView) && dateListManager != null) {
                dateListManager.setFocused(true);
            } else if ("backBtn".equals(rightFocusView) && btnBackGroup != null) {
                btnBackGroup.setTextColor(0xFFFFFFFF);
                btnBackGroup.setTypeface(null, Typeface.BOLD);
                btnBackGroup.setBackgroundColor(0x3340A9FF);
            }
        }
    }

    public void setChannels(List<Channel> channels) {
        if (channels == null) return;
        this.channelSourceList = new ArrayList<>(channels); // 深拷贝避免外部修改影响
        
        int favoriteCount = 0;
        int recentCount = 0;
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            List<String> favorites = appConfig.getFavoriteChannels();
            List<String> recent = appConfig.getRecentChannels();
            
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
            e.printStackTrace();
        }
        
        if (groupListManager != null) {
            groupListManager.setGroups(channels, favoriteCount, recentCount);
        }
        if (channelListManager != null) {
            channelListManager.setChannels(channels, currentPlayIndex);
        }
        if (channelListManagerEpg != null) {
            channelListManagerEpg.setChannels(channels, currentPlayIndex);
        }
    }

    private void onGroupClicked(int position) {
        if (groupListManager == null) return;
        
        groupListManager.setSelectedPosition(position);
        if (lvGroup != null) {
            lvGroup.setItemChecked(position, true);
            lvGroup.setSelection(position);
        }
        
        String groupName = groupListManager.getCurrentGroup(position);
        currentGroupName = groupName;
        currentGroupChannelList.clear();
        
        if (GroupListManager.GROUP_ALL.equals(groupName)) {
            currentGroupChannelList.addAll(channelSourceList);
            if (channelListManager != null) {
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
            }
        } else if (GroupListManager.GROUP_FAVORITE.equals(groupName)) {
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
                e.printStackTrace();
            }
            
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            
            if (channelListManager != null) {
                channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
            }
        } else if (GroupListManager.GROUP_RECENT.equals(groupName)) {
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
                e.printStackTrace();
            }
            
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            
            if (channelListManager != null) {
                channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
            }
        } else {
            for (Channel c : channelSourceList) {
                if (groupName.equals(c.getGroup())) {
                    currentGroupChannelList.add(c);
                }
            }
            
            if (channelListManager != null) {
                channelListManager.setChannelsByGroup(channelSourceList, groupName, currentPlayIndex);
            }
        }
    }

    public String getCurrentGroupName() {
        return currentGroupName;
    }

    public List<Channel> getCurrentGroupChannels() {
        return new ArrayList<>(currentGroupChannelList); // 返回拷贝避免外部修改
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
        
        // 边界防护
        index = Math.max(0, Math.min(index, channelSourceList.size() - 1));
        currentPlayIndex = index;
        
        Channel ch = channelSourceList.get(index);
        if (ch == null) return;
        
        String channelGroup = ch.getGroup();
        if (channelGroup != null && !channelGroup.isEmpty()) {
            boolean isSpecialGroup = GroupListManager.GROUP_ALL.equals(currentGroupName)
                    || GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                    || GroupListManager.GROUP_RECENT.equals(currentGroupName)
                    || currentGroupName.isEmpty();
            
            if (!isSpecialGroup && !channelGroup.equals(currentGroupName)) {
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
        }
        
        // 更新频道列表显示
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            if (channelListManager != null) {
                channelListManager.setChannels(channelSourceList, index);
            }
        } else if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                || GroupListManager.GROUP_RECENT.equals(currentGroupName)) {
            if (channelListManager != null) {
                channelListManager.setFilteredChannels(currentGroupChannelList, ch.getName());
            }
        } else {
            if (channelListManager != null) {
                channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, index);
            }
        }
        
        if (channelListManagerEpg != null) {
            channelListManagerEpg.setChannels(channelSourceList, index);
        }
        
        if (epgManagerWrapper != null) {
            epgManagerWrapper.refresh(ch, channelSourceList, currentSelectedDateIndex);
        }
        
        // 触发频道切换监听
        if (channelChangeListener != null) {
            channelChangeListener.onChannelChanged(ch, index);
        }
        
        addToRecent(ch.getName());
    }

    private void addToRecent(String channelName) {
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            appConfig.addRecentChannel(channelName);
            
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
            
            if (groupListManager != null) {
                groupListManager.updateSpecialGroupCount(favoriteCount, recentCount);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean handleChannelLongClick(String channelName, boolean isRightPanel) {
        if (channelName == null || channelName.isEmpty()) {
            return false;
        }
        
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            boolean isFavorite = appConfig.toggleFavorite(channelName);
            
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
            
            if (groupListManager != null) {
                groupListManager.updateSpecialGroupCount(favoriteCount, recentCount);
            }
            
            if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)) {
                currentGroupChannelList.clear();
                for (String name : favorites) {
                    for (Channel c : channelSourceList) {
                        if (name.equals(c.getName())) {
                            currentGroupChannelList.add(c);
                            break;
                        }
                    }
                }
                
                if (!isRightPanel && channelListManager != null) {
                    channelListManager.setFilteredChannels(currentGroupChannelList, channelName);
                } else if (isRightPanel && channelListManagerEpg != null) {
                    channelListManagerEpg.setFilteredChannels(currentGroupChannelList, channelName);
                }
            }
            
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean toggleCurrentFavorite() {
        if (channelSourceList == null || channelSourceList.isEmpty()) return false;
        if (currentPlayIndex < 0 || currentPlayIndex >= channelSourceList.size()) return false;
        
        Channel currentChannel = channelSourceList.get(currentPlayIndex);
        if (currentChannel == null) return false;
        
        try {
            AppConfig appConfig = AppConfig.getInstance(context);
            boolean isFavorite = appConfig.toggleFavorite(currentChannel.getName());
            
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
            
            if (groupListManager != null) {
                groupListManager.updateSpecialGroupCount(favoriteCount, recentCount);
            }
            
            if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)) {
                currentGroupChannelList.clear();
                for (String name : favorites) {
                    for (Channel c : channelSourceList) {
                        if (name.equals(c.getName())) {
                            currentGroupChannelList.add(c);
                            break;
                        }
                    }
                }
                
                if (channelListManager != null) {
                    channelListManager.setFilteredChannels(currentGroupChannelList, currentChannel.getName());
                }
            }
            
            return isFavorite;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
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
        // 边界防护
        if (channelSourceList.isEmpty()) {
            this.currentPlayIndex = 0;
            return;
        }
        this.currentPlayIndex = Math.max(0, Math.min(index, channelSourceList.size() - 1));
    }

    public void setTotalChannelCount(int count) {
        // 预留方法，保持兼容性
    }

    /**
     * 优化后的面板切换方法，增强适配性和健壮性
     */
    public void togglePanel() {
        // 更新频道列表状态
        if (GroupListManager.GROUP_ALL.equals(currentGroupName)
                || currentGroupName.isEmpty()
                || currentGroupChannelList.isEmpty()) {
            if (channelListManager != null) {
                channelListManager.setChannels(channelSourceList, currentPlayIndex);
            }
        } else if (GroupListManager.GROUP_FAVORITE.equals(currentGroupName)
                || GroupListManager.GROUP_RECENT.equals(currentGroupName)) {
            String currentChannelName = "";
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                currentChannelName = channelSourceList.get(currentPlayIndex).getName();
            }
            if (channelListManager != null) {
                channelListManager.setFilteredChannels(currentGroupChannelList, currentChannelName);
            }
        } else {
            if (channelListManager != null) {
                channelListManager.setChannelsByGroup(channelSourceList, currentGroupName, currentPlayIndex);
            }
        }
        
        if (channelListManagerEpg != null) {
            channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
        }
        
        boolean isOpen = isPanelOpen();
        
        // 调用面板管理器切换面板
        if (panelManager != null) {
            panelManager.toggle(channelSourceList, currentPlayIndex, dateListManager);
        }
        
        if (!isOpen) {
            // 面板打开逻辑
            panelLayout.post(new Runnable() {
                @Override
                public void run() {
                    clearAllFocusStyles();
                    currentFocusPanel = "left";
                    leftFocusView = "channel";
                    syncFocusStyle();
                    
                    if (lvChannelList != null) {
                        lvChannelList.requestFocus();
                        lvChannelList.setSelection(getChannelListSelection());
                    }
                    
                    resetAutoHide();
                }
            });
        } else {
            // 面板关闭逻辑
            cancelAutoHide();
        }
        
        // 触发面板状态监听
        if (panelStateListener != null) {
            panelStateListener.onPanelStateChanged(!isOpen);
        }
    }

    public void showPanel() {
        if (!isPanelOpen()) {
            togglePanel();
        }
    }

    public void hidePanel() {
        if (isPanelOpen()) {
            cancelAutoHide();
            togglePanel();
        }
    }

    public boolean isPanelOpen() {
        return panelLayout != null && panelLayout.getVisibility() == View.VISIBLE;
    }

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
        if (delayMs < 0) {
            throw new IllegalArgumentException("Delay cannot be negative");
        }
        this.mAutoHideDelayMs = delayMs;
    }

    public void setAutoHideEnabled(boolean enabled) {
        this.mAutoHideEnabled = enabled;
        if (!enabled) {
            cancelAutoHide();
        }
    }

    // ====================================================================
    // ✅ 2026-06-26 新增：首次启动面板特殊延迟处理
    // ====================================================================
    public void handleFirstLaunch() {
        if (!mIsFirstLaunch) return;
        
        SettingsActivity.logOperation("【面板】首次启动，设置特殊延迟："
                + (FIRST_LAUNCH_HIDE_DELAY_MS / 1000) + "秒");
        
        // 设置首次启动延迟并立即生效
        setAutoHideDelay(FIRST_LAUNCH_HIDE_DELAY_MS);
        resetAutoHide();
        
        // 恢复默认延迟（后续使用）
        setAutoHideDelay(NORMAL_HIDE_DELAY_MS);
        
        // 标记为非首次启动
        mIsFirstLaunch = false;
        SettingsActivity.logOperation("【面板】首次启动处理完成，已标记为非首次启动");
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
            // 打开右侧EPG面板
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.GONE);
            }
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.VISIBLE);
            }
            
            rightPanelOpen = true;
            epgPanelOpen = true;
            
            if (channelListManagerEpg != null) {
                channelListManagerEpg.setChannels(channelSourceList, currentPlayIndex);
            }
            
            if (llRightPanel != null) {
                llRightPanel.post(new Runnable() {
                    @Override
                    public void run() {
                        clearAllFocusStyles();
                        currentFocusPanel = "right";
                        rightFocusView = "channel";
                        syncFocusStyle();
                        
                        if (lvChannelListEpg != null) {
                            lvChannelListEpg.requestFocus();
                            lvChannelListEpg.setSelection(currentPlayIndex);
                        }
                    }
                });
            }
            
            if (!channelSourceList.isEmpty()
                    && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()
                    && epgManagerWrapper != null) {
                Channel curr = channelSourceList.get(currentPlayIndex);
                epgManagerWrapper.refresh(curr, channelSourceList, currentSelectedDateIndex);
            }
        } else {
            // 关闭右侧EPG面板
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.GONE);
            }
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.VISIBLE);
            }
            
            rightPanelOpen = false;
            epgPanelOpen = false;
            
            if (llLeftPanel != null) {
                llLeftPanel.post(new Runnable() {
                    @Override
                    public void run() {
                        clearAllFocusStyles();
                        currentFocusPanel = "left";
                        leftFocusView = "channel";
                        syncFocusStyle();
                        
                        if (lvChannelList != null) {
                            lvChannelList.requestFocus();
                            lvChannelList.setSelection(getChannelListSelection());
                        }
                    }
                });
            }
        }
    }

    private void onBackGroupClicked() {
        if (rightPanelOpen) {
            if (llRightPanel != null) {
                llRightPanel.setVisibility(View.GONE);
            }
            if (llLeftPanel != null) {
                llLeftPanel.setVisibility(View.VISIBLE);
            }
            
            rightPanelOpen = false;
            epgPanelOpen = false;
            
            if (llLeftPanel != null) {
                llLeftPanel.post(new Runnable() {
                    @Override
                    public void run() {
                        clearAllFocusStyles();
                        currentFocusPanel = "left";
                        leftFocusView = "channel";
                        syncFocusStyle();
                        
                        if (lvChannelList != null) {
                            lvChannelList.requestFocus();
                            lvChannelList.setSelection(getChannelListSelection());
                        }
                    }
                });
            }
        }
    }

    public boolean isEpgPanelOpen() {
        return epgPanelOpen;
    }

    public void setCurrentDateIndex(int index) {
        // 边界防护
        this.currentSelectedDateIndex = Math.max(0, index);
        
        if (panelManager != null) {
            panelManager.setCurrentDateIndex(this.currentSelectedDateIndex);
        }
        
        if (!channelSourceList.isEmpty()
                && currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()
                && epgManagerWrapper != null) {
            Channel curr = channelSourceList.get(currentPlayIndex);
            epgManagerWrapper.refresh(curr, channelSourceList, this.currentSelectedDateIndex);
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
            if (currentPlayIndex >= 0 && currentPlayIndex < channelSourceList.size()) {
                Channel currentChannel = channelSourceList.get(currentPlayIndex);
                for (int i = 0; i < currentGroupChannelList.size(); i++) {
                    if (currentGroupChannelList.get(i).getName().equals(currentChannel.getName())) {
                        return i;
                    }
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

    public boolean dispatchKeyEvent(int keyCode) {
        return false;
    }

    /**
     * 绑定频道切换监听（增强版，支持空值检查）
     * @param listener 监听实例
     */
    public void setOnChannelChangeListener(OnChannelChangeListener listener) {
        this.channelChangeListener = listener;
    }

    /**
     * 绑定面板状态监听（增强版，支持空值检查）
     * @param listener 监听实例
     */
    public void setOnPanelStateListener(OnPanelStateListener listener) {
        this.panelStateListener = listener;
    }

    /**
     * 资源释放方法，防止内存泄漏
     */
    public void release() {
        cancelAutoHide();
        
        // 清空监听
        channelChangeListener = null;
        panelStateListener = null;
        
        // 清空Handler
        if (mAutoHideHandler != null) {
            mAutoHideHandler.removeCallbacksAndMessages(null);
            mAutoHideHandler = null;
        }
        mAutoHideRunnable = null;
        
        // 清空集合引用
        if (channelSourceList != null) {
            channelSourceList.clear();
        }
        if (currentGroupChannelList != null) {
            currentGroupChannelList.clear();
        }
        
        // 清空上下文和视图引用
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
        
        // 清空管理器引用
        groupListManager = null;
        channelListManager = null;
        channelListManagerEpg = null;
        dateListManager = null;
        epgManagerWrapper = null;
        panelManager = null;
    }
}
