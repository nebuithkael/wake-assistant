package com.dsh.wake

import android.content.Context

/** 简单配置存储：密钥只留在本机。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("wake", Context.MODE_PRIVATE)

    var accessKey: String
        get() = sp.getString("accessKey", "").orEmpty()
        set(v) = sp.edit().putString("accessKey", v).apply()

    var deepseekKey: String
        get() = sp.getString("deepseekKey", "").orEmpty()
        set(v) = sp.edit().putString("deepseekKey", v).apply()

    var chinese: Boolean
        get() = sp.getBoolean("chinese", true)
        set(v) = sp.edit().putBoolean("chinese", v).apply()

    var keywordPath: String
        get() = sp.getString("keywordPath", "").orEmpty()
        set(v) = sp.edit().putString("keywordPath", v).apply()

    fun ready(): Boolean =
        accessKey.isNotEmpty() && deepseekKey.isNotEmpty() && keywordPath.isNotEmpty()
}
