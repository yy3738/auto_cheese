package com.cheese.assistant.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * 透明授权页：请求 MediaProjection，结果直接交给 ScreenCapture.start()。
 */
class CapturePermissionActivity : Activity() {

    companion object {
        const val REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            ScreenCapture.start(this, resultCode, data)
            // 授权即自动启动识别循环(2026-10-21): 旧流程要求"授权后再点
            // 一次按钮", 用户授权完直接切 JJ → 循环从未启动 → "识别不出
            // 来"。现在一键: 授权后服务+悬浮窗+循环全自动
            startForegroundService(
                Intent(this, com.cheese.assistant.service.AssistantService::class.java)
                    .setAction(com.cheese.assistant.service.AssistantService.Action.START_AUTO))
        } else {
            android.widget.Toast.makeText(
                this, "未授予截屏权限, 识别未启动", android.widget.Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
