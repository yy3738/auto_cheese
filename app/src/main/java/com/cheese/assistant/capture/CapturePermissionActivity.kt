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
            // 2026-10-26 Android 14+ 修复：必须先有 mediaProjection 类型的
            // 前台服务，再调用 getMediaProjection（否则 SecurityException 闪退）。
            // 所以这里只把授权结果交给服务，由服务在自己的前台生命周期里创建投屏。
            val svc = Intent(this, com.cheese.assistant.service.AssistantService::class.java)
                .setAction(com.cheese.assistant.service.AssistantService.Action.START_AUTO)
                .putExtra(com.cheese.assistant.service.AssistantService.Extra.PROJECTION_RESULT_CODE,
                    resultCode)
                .putExtra(com.cheese.assistant.service.AssistantService.Extra.PROJECTION_DATA, data)
            startForegroundService(svc)
        } else {
            android.widget.Toast.makeText(
                this, "未授予截屏权限, 识别未启动", android.widget.Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
