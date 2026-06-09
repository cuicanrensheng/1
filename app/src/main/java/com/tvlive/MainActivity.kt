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
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
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

        // 强制全屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.playerView)
        tvEpg = findViewById(R.id.tvEpg)
        gestureDetector = GestureDetector(this, this)

        // ✅【修复黑屏】启动就把播放器铺满全屏
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
        playerView.keepScreenOn = true

        loadChannelList()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDown(e: MotionEvent): Boolean = true
    override fun onShowPress(e: MotionEvent) {}
    override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (channelList.isNotEmpty()) showChannelListDialog()
        return true
    }

    override fun onScroll(
        e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
    ): Boolean {
        if (channelList.isEmpty()) return true
        if (distanceY < -15) previousChannel()
        if (distanceY > 15) nextChannel()
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        showScreenRatioDialog()
    }

    override fun onFling(
        e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
    ): Boolean = true

    private fun loadChannelList() {
        CoroutineScope(Dispatchers.IO).launch {
            channelList = M3UHelper.getChannelList()
            if (channelList.isNotEmpty()) {
                launch(Dispatchers.Main) {
                    playChannel(0) // 自动播放第一个频道
                }
            } else {
                launch(Dispatchers.Main) {
                    tvEpg.text = "未获取到频道"
                }
            }
        }
    }

    // ✅【修复黑屏】正确初始化 ExoPlayer，绝不黑屏
    private fun playChannel(pos: Int) {
        if (channelList.isEmpty()) return

        val safePos = pos.coerceIn(0, channelList.size - 1)
        currentPosition = safePos
        val channel = channelList[safePos]

        // 释放旧播放器
        player?.release()

        // ✅ 新建播放器并绑定
        player = ExoPlayer.Builder(this).apply {
            setSeekBackIncrementMs(5000)
        }.build()

        playerView.player = player

        // 播放
        val mediaItem = MediaItem.fromUri(channel.streamUrl)
        player!!.setMediaItem(mediaItem)
        player!!.prepare()
        player!!.playWhenReady = true

        tvEpg.text = "正在播放：${channel.name}"
        loadEpg()
    }

    private fun loadEpg() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(EPG_URL).build()
                val xml = client.newCall(request).execute().body?.string() ?: ""
                val list = EpgHelper.parseXml(xml)
                val now = System.currentTimeMillis()
                val program = list.firstOrNull { now in it.startTime..it.endTime }
                launch(Dispatchers.Main) {
                    tvEpg.text = program?.title ?: "正在播放：${channelList[currentPosition].name}"
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    tvEpg.text = "正在播放：${channelList[currentPosition].name}"
                }
            }
        }
    }

    private fun previousChannel() = playChannel(if (currentPosition > 0) currentPosition - 1 else channelList.size - 1)
    private fun nextChannel() = playChannel(if (currentPosition < channelList.size - 1) currentPosition + 1 else 0)

    private fun showScreenRatioDialog() {
        val items = arrayOf("正常", "拉伸", "填充")
        AlertDialog.Builder(this)
            .setTitle("屏幕比例")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    1 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> previousChannel()
            KeyEvent.KEYCODE_DPAD_DOWN -> nextChannel()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> showChannelListDialog()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HELP -> showScreenRatioDialog()
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showChannelListDialog() {
        val names = channelList.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("频道列表")
            .setItems(names) { _, pos -> playChannel(pos) }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
