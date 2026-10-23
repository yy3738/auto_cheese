package com.cheese.assistant.recognize

import android.content.Context
import com.cheese.assistant.util.AppLog

/**
 * 识别引擎工厂（简单工厂 + 单例缓存）：按 SharedPreferences 选择主路引擎。
 *
 * key "engine_cnn"（默认 true）：
 *   true  = CnnEngine 主路（NCC 影子对照）
 *   false = NccEngine 主路（CNN 影子对照，回退开关）
 *
 * 调试切换：
 *   adb shell "run-as com.cheese.assistant sh -c
 *     'echo ... > files/pref_override'" 之类不适用——直接改默认值重编译，
 *   或用 am start extras（见 resolve）。
 */
object RecognizerEngineFactory {

    private const val TAG = "engine"
    const val KEY_CNN = "engine_cnn"

    @Volatile
    private var cnn: CnnEngine? = null

    /** 应用启动时调用一次（幂等）：加载 ONNX CNN 权重 */
    fun init(context: Context) {
        if (cnn != null) return
        runCatching {
            val net = OnnxCnn.load(context.applicationContext)
                ?: error("OnnxCnn.load returned null")
            cnn = CnnEngine(net)
            AppLog.i(TAG, "CNN(ONNX MobileNetV3) 加载成功: ${net.classes.size} 类")
        }.onFailure {
            AppLog.e(TAG, "CNN 加载失败: ${it.javaClass.simpleName} ${it.message}")
        }
    }

    val cnnReady: Boolean get() = cnn != null

    /** 仲裁层发现可疑格时强制重推（懒推理缓存失效） */
    fun invalidate(cells: Collection<Int>) {
        cnn?.invalidate(cells)
    }

    /** 连续失败/锚点重同步时强制下一帧全量推理 */
    fun invalidateAll() {
        cnn?.invalidateAll()
    }

    /**
     * 解析主路引擎。templates 仅 NCC 需要（懒创建，切换回退时零成本）。
     * CNN 请求但未加载成功（自检失败/权重缺失）时回退 NCC，保证可用性。
     */
    fun resolve(templates: TemplateStore): RecognizerEngine {
        // CNN 单核(2026-10-20): NCC 按替换决策不再参与主路/回退/影子
        val primary = cnn ?: error("CNN 未就绪(加载失败或尚未 init)")
        return primary
    }
}

/** SharedPreferences 简单访问（独立小类，便于测试替换） */
object PreferenceAccess {
    @Volatile
    var appContext: Context? = null

    fun readBoolean(key: String, default: Boolean): Boolean {
        val ctx = appContext ?: return default
        return runCatching {
            ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
                .getBoolean(key, default)
        }.getOrDefault(default)
    }
}
