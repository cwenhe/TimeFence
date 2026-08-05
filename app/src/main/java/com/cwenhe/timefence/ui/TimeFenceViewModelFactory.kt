package com.cwenhe.timefence.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cwenhe.timefence.core.AppContainer

/** 为根界面提供带应用级依赖的 ViewModel。 */
class TimeFenceViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    /** 只创建时界根 ViewModel，未知类型直接拒绝。 */
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TimeFenceViewModel::class.java)) {
            "不支持的 ViewModel：${modelClass.name}"
        }
        return TimeFenceViewModel(container) as T
    }
}
