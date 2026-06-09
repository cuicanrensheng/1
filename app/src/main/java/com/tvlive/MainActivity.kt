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

// 假设：
// data class Channel(val name: String, val url: String)
// data class EpgProgram(val title: String, val startTime: Long, val endTime: Long)
// object M3UHelper { fun parseM3U(url: String): List<Channel> }
// object EpgHelper { fun parseXml(url: String): List<EpgProgram> }

class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var tvEpgInfo: TextView
    private var player: ExoPlayer? = null

    // 你的直播源/EPG地址
    private val m3uUrl = "https://example.com/iptv.m3u"
    private val epgUrl = "https://example.com/epg.xml"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById<PlayerView>(R.id.playerView)
        tvEpgInfo = findViewById<TextView>(R.id.tvEpg)

        // 启动即加载播放第一个频道
        loadAndPlayFirstChannel()
        // 启动即加载EPG
        loadEpgForCurrentChannel()
    }

    // 加载M3U并播放第一个频道（兼容你helper方法名）
    private fun loadAndPlayFirstChannel() {
        CoroutineScope(Dispatchers.IO).launch {
            val channels: List<Channel> = try {
                // 你原来如果是 M3UHelper.parse(...) 报错，
                // 请改成你真实方法名，例如 parseM3U / fromUrl
                M3UHelper.parseM3U(m3uUrl)
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

    // 加载EPG
    private fun loadEpgForCurrentChannel() {
        CoroutineScope(Dispatchers.IO).launch {
            val epgList: List<EpgProgram> = try {
                EpgHelper.parseXml(epgUrl)
            } catch (e: Exception) {
                emptyList()
            }

            val currentTime = System.currentTimeMillis()
            val currentProgram = epgList.firstOrNull {
                currentTime in it.startTime..it.endTime
            }

            val epgText = currentProgram?.let {
                "正在播放：${it.title}"
            } ?: "暂无节目信息"

            withContext(Dispatchers.Main) {
                tvEpgInfo.text = epgText
            }
        }
    }

    // 初始化播放器
    private fun initPlayer(streamUrl: String) {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        val mediaItem = MediaItem.fromUri(streamUrl)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.playWhenReady = true
    }

    // 遥控器按键（修复KEYCODE_CENTER）
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> { // 修复：正确常量
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
