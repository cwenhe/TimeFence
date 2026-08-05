package com.cwenhe.timefence.enforcement

import android.annotation.SuppressLint
import android.content.Context

/**
 * 在无障碍服务尚未连接时，持久化一位“需要立即补检”的进程外状态。
 *
 * @param context 用于打开仅时界可访问的 SharedPreferences。
 */
class BoundaryCheckStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    /** 同步持久化补检标记，避免广播结束后进程被回收导致标记丢失。 */
    @SuppressLint("ApplySharedPref")
    fun markPending() {
        synchronized(storeLock) {
            preferences.edit().putBoolean(KEY_PENDING, true).commit()
        }
    }

    /** 原子读取并清除一次补检标记，返回本次是否确实需要补检。 */
    @SuppressLint("ApplySharedPref")
    fun consumePending(): Boolean = synchronized(storeLock) {
        val pending = preferences.getBoolean(KEY_PENDING, false)
        if (pending) {
            preferences.edit().remove(KEY_PENDING).commit()
        }
        pending
    }

    companion object {
        private const val PREFERENCES_NAME = "boundary_check"
        private const val KEY_PENDING = "pending"
        private val storeLock = Any()
    }
}
