package com.tvlive

import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var tvEpgInfo: TextView
    private var player: ExoPlayer? = null

    // 你可以在这里替换成自己的地址
    private val m3uUrl = "https://example.com/iptv.m3u"
    private val epgUrl = "https://example.com/epg.xml"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        tvEpgInfo = findViewById(R.id.tvEpg)

        // 启动自动加载并播放直播
        loadAndPlayFirstChannel()
        // 启动自动加载并显示EPG
        loadEpgForCurrentChannel()
    }

    // ------------------ 调用 M3UHelper 加载并播放第一个频道 ------------------
    private fun loadAndPlayFirstChannel() {
        CoroutineScope(Dispatchers.IO).launch {
            val channels = try {
                M3UHelper.parse(m3uUrl)
            } catch (e: Exception) {
                emptyList()
            }

            if (channels.isNotEmpty()) {
                val firstChannel = channels.first()
                withContext(Dispatchers.Main) {
                    initPlayer(firstChannel.url)
                }
            }
        }
    }

    // ------------------ 调用 EpgHelper 加载当前频道的EPG ------------------
    private fun loadEpgForCurrentChannel() {
        CoroutineScope(Dispatchers.IO).launch {
            val epgList = try {
                EpgHelper.parseXml(epgUrl)
            } catch (e: Exception) {
                emptyList()
            }

            val currentTime = System.currentTimeMillis()
            val currentProgram = epgList.find {
                currentTime in it.startTime..it.endTime
            }

            val epgText = if (currentProgram != null) {
                "正在播放：${currentProgram.title}"
            } else {
                "暂无节目信息"
            }

            withContext(Dispatchers.Main) {
                tvEpgInfo.text = epgText
            }
        }
    }

    // ------------------ 初始化播放器 ------------------
    private fun initPlayer(streamUrl: String) {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        val mediaItem = MediaItem.fromUri(streamUrl)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
    }

    // ------------------ 遥控器按键支持 ------------------
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_CENTER -> {
                // 遥控器按键交给播放器处理
                if (playerView.onKeyDown(keyCode, event)) true
                else super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
