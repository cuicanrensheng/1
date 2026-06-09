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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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

    private val LIVE_SOURCE = "https://raw.githubusercontent.com/cuicanrensheng/IPTV/refs/heads/main/playlist1.m3u"
    private val EPG_URL = "https://epg.catvod.com/epg.xml"
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        showChannelListDialog()
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
        showSettingDialog()
    }

    override fun onFling(
        e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
    ): Boolean = true

    private fun showSettingDialog() {
        val menuItems = arrayOf(
            "画面比例 - 原始适配",
            "画面比例 - 拉伸铺满",
            "画面比例 - 裁剪填充",
            "重新加载直播源",
            "当前EPG: $EPG_URL"
        )
        AlertDialog.Builder(this)
            .setTitle("应用设置")
            .setItems(menuItems) { _, index ->
                when (index) {
                    0 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    1 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                    3 -> loadChannelList()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showChannelListDialog() {
        if (channelList.isEmpty()) {
            tvEpg.text = "暂无可用频道"
            return
        }
        val nameArray = channelList.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("频道列表")
            .setItems(nameArray) { _, pos ->
                playChannel(pos)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun loadChannelList() {
        tvEpg.text = "正在加载频道列表..."
        CoroutineScope(Dispatchers.IO).launch {
            channelList = M3UHelper.getChannelList(LIVE_SOURCE)
            launch(Dispatchers.Main) {
                if (channelList.isNotEmpty()) {
                    playChannel(0)
                } else {
                    tvEpg.text = "频道加载失败，请检查网络"
                }
            }
        }
    }

    // ======================
    // 🔥 核心修复：多线路播放
    // ======================
    private fun playChannel(pos: Int) = runOnUiThread {
        if (channelList.isEmpty()) return@runOnUiThread
        val safePos = pos.coerceIn(0, channelList.size - 1)
        currentPosition = safePos
        val targetChannel = channelList[safePos]

        player?.release()
        player = ExoPlayer.Builder(this@MainActivity).build()
        playerView.player = player

        // ✅ 修复：从 streamUrls 列表获取当前线路
        val playUrl = targetChannel.streamUrls[targetChannel.currentLineIndex]
        val mediaItem = MediaItem.fromUri(playUrl)

        player!!.setMediaItem(mediaItem)
        player!!.prepare()
        player!!.playWhenReady = true

        tvEpg.text = "播放: ${targetChannel.name}"
        loadEpgInfo(targetChannel.name)
    }

    private fun loadEpgInfo(channelName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val req = Request.Builder().url(EPG_URL).build()
                val respText = client.newCall(req).execute().body?.string() ?: ""
                launch(Dispatchers.Main) {
                    tvEpg.text = "播放: $channelName | EPG已加载"
                }
            } catch (err: Exception) {
                launch(Dispatchers.Main) {
                    tvEpg.text = "播放: $channelName | EPG加载失败"
                }
            }
        }
    }

    private fun previousChannel() = playChannel(if (currentPosition > 0) currentPosition - 1 else channelList.size - 1)
    private fun nextChannel() = playChannel(if (currentPosition < channelList.size - 1) currentPosition + 1 else 0)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> previousChannel()
            KeyEvent.KEYCODE_DPAD_DOWN -> nextChannel()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> showChannelListDialog()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HELP -> showSettingDialog()
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
