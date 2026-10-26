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
            // 授权后只启动服务+悬浮窗，识别循环等用户点「开局」再开
            // （2026-10-23 用户要求：没点开局不识别、不提示识别不稳定）
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
