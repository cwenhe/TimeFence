package com.cwenhe.timefence.apps

import android.graphics.drawable.Drawable

/** 应用选择页展示的一项可启动应用。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)
