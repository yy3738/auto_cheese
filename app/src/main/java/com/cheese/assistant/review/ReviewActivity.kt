package com.cheese.assistant.review

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cheese.assistant.db.AppDb
import com.cheese.assistant.db.GameEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 对局记录列表:蓝白主题(白底/藏蓝标题/红标坏棋),点行展开复盘明细。
 */
class ReviewActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var container: LinearLayout
    private var games: List<GameEntity> = emptyList()
    private val expandedIds = HashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF8FAFD.toInt())
            addView(TextView(this@ReviewActivity).apply {
                text = "复 盘"
                textSize = 26f
                typeface = Typeface.SERIF
                setTextColor(0xFF234B94.toInt())
                letterSpacing = 0.3f
                setPadding(dp(2), dp(18), dp(2), dp(2))
                setOnClickListener { finish() }
            })
            addView(TextView(this@ReviewActivity).apply {
                text = "← 返回   ·   点行展开明细"
                textSize = 12f
                setTextColor(0xFF64748B.toInt())
                setPadding(dp(2), 0, dp(2), dp(10))
            })
        }
        root.addView(ScrollView(this).apply { addView(container) })
        setContentView(root)

        scope.launch {
            AppDb.get(this@ReviewActivity).gameDao().allGames().collectLatest { list ->
                games = list
                render()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun render() {
        container.removeAllViews()
        if (games.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有对局记录\n\n对局中点悬浮窗「结」按钮封存并复盘"
                setTextColor(0xFF94A3B8.toInt())
                setPadding(dp(8), dp(32), dp(8), dp(8))
            })
            return
        }
        for (g in games) {
            container.addView(gameRow(g))
            if (expandedIds.contains(g.id) && g.reviewJson != null) {
                container.addView(detailView(g))
            }
        }
    }

    private fun gameRow(g: GameEntity): LinearLayout {
        val moves = runCatching { org.json.JSONArray(g.moveListJson).length() }.getOrDefault(0)
        val blunders = g.blunderCount + g.blunderCountMajor
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = card()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
            setOnClickListener {
                if (!expandedIds.remove(g.id)) expandedIds.add(g.id)
                render()
            }
            addView(LinearLayout(this@ReviewActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@ReviewActivity).apply {
                    text = "第 ${g.id} 局"
                    textSize = 16f
                    typeface = Typeface.SERIF
                    setTextColor(0xFF234B94.toInt())
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@ReviewActivity).apply {
                    text = when {
                        g.reviewStatus != GameEntity.STATUS_DONE -> "复盘中"
                        blunders > 0 -> "⚠ $blunders"
                        else -> "✓"
                    }
                    textSize = 13f
                    setTextColor(
                        when {
                            g.reviewStatus != GameEntity.STATUS_DONE -> 0xFF64748B.toInt()
                            blunders >= 3 -> 0xFFB3352B.toInt()
                            blunders > 0 -> 0xFFB7791F.toInt()
                            else -> 0xFF2E7D5B.toInt()
                        }
                    )
                })
            })
            addView(TextView(this@ReviewActivity).apply {
                text = "${formatTime(g.endedAt)}   ${moves}步   ${g.finalResult}"
                textSize = 12f
                setTextColor(0xFF64748B.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }
        return row
    }

    private fun detailView(g: GameEntity): TextView = TextView(this).apply {
        text = buildDetailText(g)
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTextColor(0xFF1A1A1A.toInt())
        setPadding(dp(18), dp(10), dp(14), dp(10))
    }

    private fun buildDetailText(g: GameEntity): String {
        val arr = org.json.JSONArray(g.reviewJson ?: return "")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val delta = o.optInt("deltaCp", 0)
            val mark = when {
                delta >= GameReviewer.BLUNDER_CP -> " ◆大漏着"
                delta >= GameReviewer.MISTAKE_CP -> " ◇失误"
                else -> ""
            }
            sb.append("你走 ").append(o.getString("playedCn"))
                .append("   最佳 ").append(o.getString("bestCn"))
            if (delta > 0) sb.append("   -").append(String.format("%.1f", delta / 100.0)).append("分")
            sb.append(mark).append('\n')
        }
        return sb.toString()
    }

    private fun card(): GradientDrawable = GradientDrawable().apply {
        setColor(0xFFFFFFFF.toInt())
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), 0xFFCBD5E1.toInt())
    }

    private fun formatTime(ts: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ts))

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            resources.displayMetrics).toInt()
}
