package com.tvlive

import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), GestureDetector.OnGestureListener, Player.Listener {
    private lateinit var playerView: PlayerView
    private lateinit var tvEpg: TextView
    private var player: ExoPlayer? = null
    private var fullChannelList = mutableListOf<Channel>()
    private var showFavOnly = false
    private var displayChannels = mutableListOf<Channel>()
    private var currentPos = 0
    private var epgData = mutableListOf<EpgProgram>()
    private lateinit var gestureDetector: GestureDetector
    private var httpServer: HttpSettingServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化全局配置、收藏
        AppConfig.init(this)
        FavoriteManager.init(this)
        // 启动本地网页后台 10481
        httpServer = HttpSettingServer(10481)
        httpServer?.start()

        // 全屏隐藏状态栏/导航栏
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.playerView)
        tvEpg = findViewById(R.id.tvEpg)
        gestureDetector = GestureDetector(this, this)
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        playerView.keepScreenOn = true

        loadAllSource()
    }

    // 加载订阅源+EPG
    private fun loadAllSource() {
        CoroutineScope(Dispatchers.IO).launch {
            fullChannelList = M3UHelper.parseM3u(AppConfig.currentM3u)
            epgData = EpgHelper.loadEpg(AppConfig.currentEpg)
            refreshDisplayChannel()
            launch(Dispatchers.Main) {
                if(displayChannels.isNotEmpty()) playChannel(0)
                else tvEpg.text = "订阅源无可用频道，请网页后台更换订阅"
            }
        }
    }

    // 切换收藏/全部频道列表
    private fun refreshDisplayChannel() {
        displayChannels = if(showFavOnly){
            fullChannelList.filter { it.isFavorite }.toMutableList()
        }else fullChannelList.toMutableList()
    }

    // 播放频道，自动线路优先可用域名
    private fun playChannel(pos: Int) = runOnUiThread {
        if(displayChannels.isEmpty()) return@runOnUiThread
        val safePos = pos.coerceIn(0, displayChannels.size - 1)
        currentPos = safePos
        val chan = displayChannels[currentPos]
        player?.release()
        player = ExoPlayer.Builder(this).build().apply { addListener(this@MainActivity) }
        playerView.player = player

        // 优先筛选有效域名线路
        val validDomains = AppConfig.validDomain
        val preferUrl = chan.streamUrls.firstOrNull { url ->
            validDomains.any { domain -> url.contains(domain) }
        } ?: chan.streamUrls[chan.currentLineIndex]

        val media = MediaItem.fromUri(preferUrl)
        player!!.setMediaItem(media)
        player!!.prepare()
        player!!.playWhenReady = true
        tvEpg.text = "加载:${chan.name} 线路${chan.currentLineIndex+1}/${chan.streamUrls.size}"

        // 加载当日节目单
        loadCurrentEpgInfo(chan.name)
    }

    // 播放失败自动切换线路
    override fun onPlayerError(error: PlaybackException) {
        if(!AppConfig.autoSwitchLine) return
        val currChan = displayChannels[currentPos]
        val failUrl = currChan.streamUrls[currChan.currentLineIndex]
        // 移除失效域名
        val domSet = AppConfig.validDomain.toMutableSet()
        domSet.remove(getDomainFromUrl(failUrl))
        AppConfig.validDomain = domSet
        // 切下一条线路
        currChan.currentLineIndex++
        if(currChan.currentLineIndex >= currChan.streamUrls.size) currChan.currentLineIndex = 0
        playChannel(currentPos)
    }

    // 播放成功保存有效域名
    override fun onPlaybackStateChanged(state: Int) {
        if(state == Player.STATE_READY){
            val currChan = displayChannels[currentPos]
            val okUrl = currChan.streamUrls[currChan.currentLineIndex]
            val domSet = AppConfig.validDomain.toMutableSet()
            domSet.add(getDomainFromUrl(okUrl))
            AppConfig.validDomain = domSet
        }
    }

    private fun getDomainFromUrl(url: String): String {
        val start = url.indexOf("://") + 3
        val end = url.indexOf("/", start).takeIf { it != -1 } ?: url.length
        return url.substring(start, end)
    }

    private fun loadCurrentEpgInfo(chanName: String) {
        val prog = EpgHelper.getNowProgram(epgData, chanName)
        tvEpg.text = prog?.title ?: "播放: $chanName"
    }

    // 切换上/下频道
    private fun prevChan() {
        val newPos = if(currentPos > 0) currentPos - 1 else displayChannels.size - 1
        playChannel(newPos)
    }
    private fun nextChan() {
        val newPos = if(currentPos < displayChannels.size -1) currentPos + 1 else 0
        playChannel(newPos)
    }

    // 左右滑动切换线路
    private fun switchLineLeft() {
        val chan = displayChannels[currentPos]
        chan.currentLineIndex--
        if(chan.currentLineIndex <0) chan.currentLineIndex = chan.streamUrls.size -1
        playChannel(currentPos)
    }
    private fun switchLineRight() {
        val chan = displayChannels[currentPos]
        chan.currentLineIndex++
        if(chan.currentLineIndex >= chan.streamUrls.size) chan.currentLineIndex = 0
        playChannel(currentPos)
    }

    // 弹窗：频道列表
    private fun showChanListDialog() {
        val names = displayChannels.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if(showFavOnly) "收藏频道" else "全部频道")
            .setItems(names) { _, pos -> playChannel(pos) }.show()
    }

    // 弹窗：屏幕比例设置
    private fun showRatioDialog() {
        AlertDialog.Builder(this).setTitle("画面比例")
            .setItems(arrayOf("正常", "拉伸", "铺满全屏")) { _, which ->
                playerView.resizeMode = when(which){
                    0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            }.show()
    }

    // 弹窗：当日节目单
    private fun showTodayEpgDialog() {
        val chan = displayChannels[currentPos]
        val todayProg = EpgHelper.getTodayAllProgram(epgData, chan.name)
        val titles = todayProg.map { it.title }.toTypedArray()
        AlertDialog.Builder(this).setTitle("${chan.name}今日节目")
            .setItems(titles, null).show()
    }

    // 收藏/取消当前频道
    private fun toggleFavorite() {
        val chan = displayChannels[currentPos]
        FavoriteManager.toggleFav(chan.name)
        chan.isFavorite = !chan.isFavorite
        refreshDisplayChannel()
    }

    // ========== 手势全套实现 ==========
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
    override fun onDown(e: MotionEvent): Boolean = true
    override fun onShowPress(e: MotionEvent) {}
    // 单击 = 频道列表
    override fun onSingleTapUp(e: MotionEvent): Boolean {
        showChanListDialog()
        return true
    }
    // 上下滑动切台，左右滑动切线路
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
        if(dY < -15) prevChan()
        if(dY > 15) nextChan()
        if(dX < -15) switchLineLeft()
        if(dX > 15) switchLineRight()
        return true
    }
    // 长按 = 设置比例
    override fun onLongPress(e: MotionEvent) {
        showRatioDialog()
    }
    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean = true

    // ========== 遥控器按键逻辑 ==========
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when(keyCode){
            KeyEvent.KEYCODE_DPAD_UP -> prevChan()
            KeyEvent.KEYCODE_DPAD_DOWN -> nextChan()
            KeyEvent.KEYCODE_DPAD_LEFT -> switchLineLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT -> switchLineRight()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> showChanListDialog()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HELP -> showRatioDialog()
            // 长按OK键收藏
            KeyEvent.KEYCODE_FAVORITES -> toggleFavorite()
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        httpServer?.stop()
    }
}
