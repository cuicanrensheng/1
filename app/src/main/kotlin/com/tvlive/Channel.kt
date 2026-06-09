package com.tvlive

data class Channel(
    val name: String,
    val streamUrls: MutableList<String>, // 多线路数组
    var currentLineIndex: Int = 0,
    var isFavorite: Boolean = false
)
