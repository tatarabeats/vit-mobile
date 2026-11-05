package com.shunp.vitmobile

/**
 * 画面の左のいちばん端（幅の4%）から右へのスワイプは、One Hand Operation+ の操作なので
 * Claude のメニューを開かない（2026-11-05 駿平さん「画面の一番端っこから右側にスワイプしたときはメニューを開かないようにして。
 * ワンハンドオペレーションと干渉するから」。実測: ワンハンドの操作は x=17/1080 から始まり、メニューの操作は x=305 や 592 から）。
 */
internal object SwipeEdge {
    const val FRACTION = 0.04f
}
