package com.cheese.assistant.recognize

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

/**
 * 棋子模板库：每个棋子类型若干张 24x24 取样图（用户在真实截图上点选采集），
 * 按目标 App 持久化。识别时逐类取最小距离。
 */
class TemplateStore(val entries: MutableList<Entry> = mutableListOf()) {

    data class Entry(val piece: String, val bitmap: Bitmap) // piece 为 Piece.name 或 "EMPTY"

    fun add(piece: String, bmp: Bitmap) {
        entries.add(Entry(piece, bmp))
    }

    fun countOf(piece: String) = entries.count { it.piece == piece }

    fun save(context: Context, targetApp: String) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("piece", e.piece).put("png", Patch.toBase64(e.bitmap)))
        }
        file(context, targetApp).writeText(JSONObject().put("templates", arr).toString())
    }

    companion object {
        fun file(context: Context, targetApp: String) =
            java.io.File(context.filesDir, "templates_$targetApp.json")

        fun load(context: Context, targetApp: String): TemplateStore {
            val f = file(context, targetApp)
            if (!f.exists()) return TemplateStore()
            return runCatching {
                val o = JSONObject(f.readText())
                val arr = o.getJSONArray("templates")
                val store = TemplateStore()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    store.add(e.getString("piece"), Patch.bitmapFromBase64(e.getString("png")))
                }
                store
            }.getOrDefault(TemplateStore())
        }
    }
}
