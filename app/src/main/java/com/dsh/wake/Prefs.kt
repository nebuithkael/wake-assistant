package com.dsh.wake

import android.content.Context

/** 简单配置存储：密钥只留在本机。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wake", Context.MODE_PRIVATE)

    var deepseekKey: String
        get() = sp.getString("deepseekKey", "").orEmpty()
        set(v) = sp.edit().putString("deepseekKey", v).apply()

    var wakeWord: String
        get() = sp.getString("wakeWord", "小深").orEmpty()
        set(v) = sp.edit().putString("wakeWord", v).apply()

    fun ready(): Boolean = deepseekKey.isNotEmpty()
}
