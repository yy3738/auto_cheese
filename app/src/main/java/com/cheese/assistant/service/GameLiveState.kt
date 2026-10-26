package com.cheese.assistant.service

/**
 * 「对局实况」页的数据源:自动循环每识别一步/每次分析完成即更新,
 * 主界面 Activity 轮询读取。识别(后台服务)与查看(主界面)解耦——
 * 服务在后台跑,用户随时进主界面核对识别是否有误。
 */
object GameLiveState {

    /** 各字段的最后更新时间戳 */
    @Volatile var updatedAt: Long = 0
        private set

    /** 当前识别局面 FEN(含行棋方) */
    @Volatile var fen: String = ""
        private set

    /** 按棋谱回放推演出的局面 FEN(识别若中途出错,以它为准展示) */
    @Volatile var replayFen: String = ""
        private set

    /** 识别局面与棋谱推演是否一致;null=无法比较(棋谱空/识别空) */
    @Volatile var match: Boolean? = null
        private set

    /** 棋谱(格式化好的着法行,如 "3. 黑 卒7进1") */
    @Volatile var moveLines: List<String> = emptyList()
        private set

    /** 当前推荐(格式化好,如 "打吃  -0.2  炮2平3") */
    @Volatile var candidateLines: List<String> = emptyList()
        private set

    /** 实况页在前景: 盖住棋盘期间识别循环应暂停(它读到的是实况页
     *  自己渲染的盘, 2026-10-21 实测引发"黑车识别不出来") */
    @Volatile var livePageOpen: Boolean = false

    /** 用户执红？实况页据此决定是否上下翻转（永远"我执子在下"） */
    @Volatile var userIsRed: Boolean = true

    fun setUserSide(isRed: Boolean) {
        userIsRed = isRed
    }

    fun update(fen: String, moveLines: List<String>, replayFen: String, match: Boolean?) {
        this.fen = fen
        this.moveLines = moveLines
        this.replayFen = replayFen
        this.match = match
        updatedAt = System.currentTimeMillis()
    }

    fun setCandidates(lines: List<String>) {
        candidateLines = lines
        updatedAt = System.currentTimeMillis()
    }

    fun clear() {
        fen = ""
        replayFen = ""
        match = null
        moveLines = emptyList()
        candidateLines = emptyList()
        updatedAt = System.currentTimeMillis()
    }
}
