package com.cheese.assistant.service

import android.app.AlertDialog
import android.content.Context
import android.view.WindowManager

/**
 * 封存确认（2026-10-27 用户要求）：点「结」后先问是否复盘。
 *
 * - 复盘：棋谱入库后立即回到局前可开下一局，复盘在后台跑，完成发通知；
 * - 不复盘：只封存棋谱，不做复盘（对局复盘列表里标「未复盘」）；
 * - 取消：什么都不做，留在当前对局。
 */
class SealDialog(
    private val context: Context,
    private val moveCount: Int,
    private val onChoose: (Boolean) -> Unit,
) {

    fun show() {
        val dialog = AlertDialog.Builder(context)
            .setTitle("封存本局（$moveCount 步）")
            .setMessage("要在后台复盘吗？\n复盘不阻塞开下一局，完成后会通知。")
            .setPositiveButton("复盘") { _, _ -> onChoose(true) }
            .setNegativeButton("不复盘") { _, _ -> onChoose(false) }
            .setNeutralButton("取消", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }
}
