package com.cheese.assistant.recognize

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.cheese.assistant.util.AppLog
import java.nio.FloatBuffer

/**
 * MobileNetV3-Small ONNX 推理封装（方案A 2026-10-20,替代手搓 CharCnn）。
 *
 * 输入 (N,3,96,96) float32, RGB 0-255 → /255 → ImageNet 归一化
 * （与 tools/train_cnn.py 的 norm_t 同一公式）。输出 15 类 logits。
 * 置信度 = sigmoid(top1-top2), 与历史分层语义一致。
 */
class OnnxCnn private constructor(
    val classes: List<String>,
    val img: Int,
    private val mean: FloatArray,
    private val std: FloatArray,
    private val session: OrtSession,
) {
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** 批量推理。输入 [N][3*128*128] 通道平面 RGB(0-255)，返回 (top1 类下标, margin)。
     *  只做单次前向，不做水平翻转 TTA：翻转视图把棋子汉字镜像成训练分布外的
     *  形态，与原图 logits 取平均等于给次优类注入伪证据（2026-10-21 实测
     *  每帧多出第三个红车幻影且 margin=1.0，顶掉真子）。 */
    fun classifyBatch(cells: List<FloatArray>): List<Pair<Int, Float>> {
        if (cells.isEmpty()) return emptyList()
        val plane = img * img
        val n = cells.size
        val buf = FloatBuffer.allocate(n * 3 * plane)
        for (cell in cells) {
            require(cell.size == 3 * plane)
            for (c in 0 until 3) {
                val base = c * plane
                val m = mean[c]; val s = std[c]
                for (i in base until base + plane) {
                    buf.put(((cell[i] / 255f) - m) / s)
                }
            }
        }
        buf.rewind()
        val shape = longArrayOf(n.toLong(), 3, img.toLong(), img.toLong())
        val t1 = OnnxTensor.createTensor(env, buf, shape)
        val results = session.run(mapOf("cells" to t1))
        try {
            @Suppress("UNCHECKED_CAST")
            val logits = results[0].value as Array<FloatArray>
            val out = ArrayList<Pair<Int, Float>>(n)
            for (k in 0 until n) {
                var bi = 0
                for (i in logits[k].indices) if (logits[k][i] > logits[k][bi]) bi = i
                var sj = if (bi == 0) 1 else 0
                for (i in logits[k].indices) {
                    if (i != bi && logits[k][i] > logits[k][sj]) sj = i
                }
                val diff = (logits[k][bi] - logits[k][sj]).toDouble()
                val margin = 1f / (1f + Math.exp(-diff)).toFloat()
                out.add(bi to margin)
            }
            return out
        } finally {
            results.close()
            t1.close()
        }
    }

    companion object {
        private const val TAG = "engine"

        fun load(context: Context): OnnxCnn? = runCatching {
            val meta = context.assets.open("cnn_meta.json").bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(meta)
            val classes = root.getJSONArray("classes").let { a ->
                List(a.length()) { a.getString(it) }
            }
            val mean = root.getJSONArray("mean").let { a ->
                FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }
            val std = root.getJSONArray("std").let { a ->
                FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
            val env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open("cnn.onnx").use { it.readBytes() }
            val session = env.createSession(bytes, opts)
            val cnn = OnnxCnn(classes, root.getInt("img"), mean, std, session)
            // 出厂自检: 全零输入 forward 一次 (结构/权重加载正确性)
            cnn.classifyBatch(List(2) { FloatArray(3 * cnn.img * cnn.img) })
            AppLog.i(TAG, "ONNX CNN 加载成功: ${classes.size} 类")
            cnn
        }.onFailure {
            AppLog.e(TAG, "ONNX CNN 加载失败", it)
        }.getOrNull()
    }
}
