package com.cheese.assistant.util

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别样本飞轮（S3，2026-10-26）。
 *
 * 把"用户确认/纠错后的帧 + 正确盘面"落盘，作为下一轮 CNN 重训的难例：
 * `files/labels/<时间>-<类型>/frame.png + meta.json`，
 * 并在 `files/labels/index.jsonl` 追加一行索引（拉取/入训练集都以它为准）。
 *
 * 为什么值得存：歧义确认与四下拉纠错提供了**带真值的标签**——这一帧模型
 * 认错了、用户告诉我们正确答案，正是价值最高的硬负样本。
 *
 * 目录数上限 [MAX_DIRS]，超出淘汰最旧，防长期占用空间。
 */
object SampleStore {

    private const val MAX_DIRS = 200

    /**
     * @param bmp 出错的原始帧（可空；空则只记 meta，便于对照棋谱）
     * @param fen 确认后的正确盘面（含行棋方）
     * @param kind ambiguity / correction / reject
     * @param cells 涉及的格号（0 基，row0=黑方底线）
     * @param note 额外说明（如候选序列文本、拒帧原因）
     */
    fun save(
        context: Context,
        bmp: Bitmap?,
        fen: String,
        kind: String,
        cells: List<Int>,
        note: String = "",
    ) {
        runCatching {
            val root = File(context.filesDir, "labels")
            if (!root.exists()) root.mkdirs()
            val stamp = SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date())
            var dir = File(root, "$stamp-$kind")
            var n = 1
            while (dir.exists()) dir = File(root, "$stamp-$kind-${n++}")
            dir.mkdirs()
            if (bmp != null && !bmp.isRecycled) {
                File(dir, "frame.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val meta = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("kind", kind)
                .put("fen", fen)
                .put("cells", JSONArray(cells))
                .put("note", note)
                .put("dir", dir.name)
            File(dir, "meta.json").writeText(meta.toString())
            File(root, "index.jsonl").appendText(meta.toString() + "\n")
            prune(root)
            AppLog.i("SampleStore", "saved $kind at ${dir.name} cells=${cells.size}")
        }.onFailure { AppLog.e("SampleStore", "save failed", it) }
    }

    private fun prune(root: File) {
        val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return
        if (dirs.size <= MAX_DIRS) return
        dirs.take(dirs.size - MAX_DIRS).forEach { runCatching { it.deleteRecursively() } }
    }
}
