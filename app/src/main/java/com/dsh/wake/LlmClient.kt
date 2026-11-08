package com.dsh.wake

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一句话问答：把识别结果发去 DeepSeek，拿回要朗读的短回答。 */
object LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_PROMPT =
        "你是一个手机语音助手。回答要像说话：一到三句，口语化，不要列表、不要 Markdown、不要表情符号。" +
            "信息不够就简短反问一句。"

    fun ask(apiKey: String, question: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", question))

        val body = JSONObject()
            .put("model", "deepseek-flash")
            .put("messages", messages)
            .put("max_tokens", 300)
            .put("temperature", 0.7)
            .toString()

        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(shortError(text) ?: "接口返回 ${resp.code}")
            }
            val json = JSONObject(text)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
            return content.trim().ifEmpty { "我没想好怎么回答。" }
        }
    }

    private fun shortError(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
