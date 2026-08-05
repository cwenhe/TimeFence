package com.cwenhe.timefence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.cwenhe.timefence.ui.TimeFenceApp
import com.cwenhe.timefence.ui.TimeFenceTheme
import com.cwenhe.timefence.ui.TimeFenceViewModel
import com.cwenhe.timefence.ui.TimeFenceViewModelFactory

/** 承载时界 Compose 界面的单 Activity。 */
class MainActivity : ComponentActivity() {
    private val viewModel: TimeFenceViewModel by viewModels {
        TimeFenceViewModelFactory((application as TimeFenceApplication).container)
    }

    /** 创建时界主题、根导航和共享状态。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimeFenceTheme {
                TimeFenceApp(viewModel)
            }
        }
    }

    /** 从系统设置返回时刷新权限与服务连接状态。 */
    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }
}
