package com.cwenhe.timefence

import android.app.Application
import com.cwenhe.timefence.core.AppContainer

/** 时界应用进程入口，持有唯一的应用级依赖容器。 */
class TimeFenceApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** 创建依赖容器并启动规则边界同步。 */
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
