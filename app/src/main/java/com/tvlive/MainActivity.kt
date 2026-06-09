package com.tvlive

import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.util.AspectRatioFrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity(), GestureDetector.OnGestureListener {

    private lateinit var playerView: PlayerView
    private lateinit var tvEpg: TextView
    private var player: ExoPlayer? = null
    private var channelList = mutableListOf<Channel>()
    private var currentPosition = 0

    private val EPG_URL = "https://epg.112114.xyz/epg.xml"
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局强制全屏，屏蔽状态栏/通知栏、导航栏
        setFullScreen()
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        tvEpg = findViewById(R.id.tvEpg)
        gestureDetector = GestureDetector(this, this)

        loadChannelList()
    }

    // 核心：沉浸式全屏，彻底屏蔽状态栏、通知栏、导航栏（全版本兼容）
    private fun setFullScreen() {
        val window: Window = window
        // 禁止屏幕变暗、锁屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 基础全屏Flag
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        val decorView = window.decorView
        // 粘性沉浸模式：滑动边缘弹出系统栏后自动重新隐藏
        val uiOptions = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION       // 隐藏导航栏
                        or View.SYSTEM_UI_FLAG_FULLSCREEN             // 隐藏状态栏/通知栏
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY       // 粘性沉浸（自动复原）
                )
        decorView.systemUiVisibility = uiOptions

        // Android 11+ 新版系统栏控制（兼容高版本）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    // 页面恢复前台时，重新强制全屏（防止切应用后状态栏重现）
    override fun onResume() {
        super.onResume()
        setFullScreen()
    }

    // 加载频道列表
    private fun loadChannelList() {
        CoroutineScope(Dispatchers.IO).launch {
            channelList = M3UHelper.getChannelList()
            if (channelList.isNotEmpty()) {
                runOnUiThread {
                    playChannel(0)
                }
            }
        }
    }

    // 播放指定频道
    private fun playChannel(pos: Int) {
        if (pos < 0 || pos >= channelList.size) return
        currentPosition = pos
        val channel = channelList[pos]

        player?.release()
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        val mediaItem = MediaItem.fromUri(channel.streamUrl)
        player!!.setMediaItem(mediaItem)
        player!!.prepare()
        player!!.playWhenReady = true

        tvEpg.text = "正在播放：${channel.name}"
        loadEpg()
    }

    // 上一个频道
    private fun previousChannel() = playChannel(if (currentPosition > 0) currentPosition - 1 else channelList.size - 1)
    // 下一个频道
    private fun nextChannel() = playChannel(if (currentPosition < channelList.size - 1) currentPosition + 1 else 0)

    // 加载EPG节目信息
    private fun loadEpg() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(EPG_URL).build()
                val xml = client.newCall(request).execute().body?.string() ?: ""
                val list = EpgHelper.parseXml(xml)
                val now = System.currentTimeMillis()
                val program = list.firstOrNull { now in it.startTime..it.endTime }
                runOnUiThread {
                    tvEpg.text = program?.title ?: "正在播放：${channelList[currentPosition].name}"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvEpg.text = "正在播放：${channelList[currentPosition].name}"
                }
            }
        }
    }

    // 屏幕比例设置弹窗
    private fun showScreenRatioDialog() {
        val items = arrayOf("正常", "拉伸", "填充")
        AlertDialog.Builder(this)
            .setTitle("屏幕比例")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT       // 正常：保留比例，有黑边
                    1 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM     // 拉伸：强制拉伸，可能变形
                    2 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL     // 填充：等比例铺满全面屏，无黑边
                }
            }
            .show()
    }

    // 电视遥控器按键监听
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> previousChannel()
            KeyEvent.KEYCODE_DPAD_DOWN -> nextChannel()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> showChannelListDialog()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HELP -> showScreenRatioDialog()
        }
        return super.onKeyDown(keyCode, event)
    }

    // 频道列表选择弹窗
    private fun showChannelListDialog() {
        val names = channelList.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("频道列表")
            .setItems(names) { _, pos -> playChannel(pos) }
            .show()
    }

    // 触摸事件分发
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDown(e: MotionEvent): Boolean = true
    override fun onShowPress(e: MotionEvent) {}

    // 单击屏幕 = 打开频道列表
    override fun onSingleTapUp(e: MotionEvent): Boolean {
        showChannelListDialog()
        return true
    }

    // 滑动切换频道
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
        if (dY > 10) nextChannel()
        if (dY < -10) previousChannel()
        return true
    }

    // 长按屏幕 = 打开屏幕比例设置
    override fun onLongPress(e: MotionEvent) {
        showScreenRatioDialog()
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
        if (e2.y < e1.y) previousChannel() else nextChannel()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
