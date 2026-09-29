package com.trseimc.appvolume

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trseimc.appvolume.audio.AudioControlService
import com.trseimc.appvolume.ui.AppListScreen
import com.trseimc.appvolume.ui.AppVolumeViewModel
import com.trseimc.appvolume.ui.theme.AppVolumeTheme
import com.trseimc.appvolume.util.FileLog

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 被拒绝也能用，只是没有常驻通知 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 日志/闪退记录最先初始化（幂等），后面的异常都能落盘
        FileLog.init(this)
        FileLog.log("lifecycle", "MainActivity onCreate")
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 音效挂在进程的生命周期上，进程被回收音量就失效，所以要有个常驻服务维持
        runCatching { startForegroundService(Intent(this, AudioControlService::class.java)) }

        setContent {
            AppVolumeTheme {
                val vm: AppVolumeViewModel = viewModel()
                AppListScreen(vm)
            }
        }
    }
}
