package io.legado.app.help.source

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.TimeUnit

object CaptchaAiRecognizer {

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

    fun isConfigured() = AppConfig.captchaAiEnabled && AppConfig.captchaAiApiKey.isNotBlank()

    suspend fun recognize(bitmap: Bitmap): String = recognize(appCtx, bitmap)

    suspend fun recognize(context: Context, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val model = AppConfig.captchaAiModel
        val endpoint = AppConfig.captchaAiBaseUrl.trimEnd('/') + "/chat/completions"
        val startedAt = System.currentTimeMillis()
        val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY)
        check(cacheDirectory.exists() || cacheDirectory.mkdirs()) { "无法创建验证码私有缓存目录" }
        cleanStaleFiles(cacheDirectory)
        val imageFile = File.createTempFile(FILE_PREFIX, ".png", cacheDirectory)

        try {
            imageFile.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "无法写入验证码临时图片"
                }
            }
            AppLog.put("验证码 AI 私有临时图片已创建: imageBytes=${imageFile.length()}")
            AppLog.put("验证码 AI 请求已发送: model=$model")
            recognize(imageFile.readBytes(), model, endpoint, startedAt)
        } catch (error: CancellationException) {
            AppLog.put("验证码 AI 请求已取消: model=$model")
            throw error
        } catch (error: Throwable) {
            AppLog.put(
                "验证码 AI 请求失败: model=$model, ${error.localizedMessage}",
                error
            )
            throw error
        } finally {
            val deleted = imageFile.delete() || !imageFile.exists()
            if (deleted) {
                AppLog.put("验证码 AI 私有临时图片已清理")
            } else {
                AppLog.put("验证码 AI 私有临时图片清理失败")
            }
        }
    }

    private suspend fun recognize(
        imageBytes: ByteArray,
        model: String,
        endpoint: String,
        startedAt: Long
    ): String {
        AppLog.put("验证码 AI 图片已编码: imageBytes=${imageBytes.size}")
        val imageUrl = "data:image/png;base64," +
            Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val content = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", "识别图片中的验证码。只返回验证码字符，不要解释、不要添加空格或标点。")
            })
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply { addProperty("url", imageUrl) })
            })
        }
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", content)
                })
            })
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${AppConfig.captchaAiApiKey}")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .header("User-Agent", "curl/8.7.1")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).await().use { response ->
            val body = response.body.string()
            check(response.isSuccessful) { "HTTP ${response.code}: ${body.take(200)}" }
            val responseJson = JsonParser.parseString(body).asJsonObject
            val result = responseJson
                .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")?.get("content")?.asString
                ?.trim()?.trim('"', '\'', '`')
                ?.replace(Regex("\\s+"), "")
                .orEmpty()
            check(result.isNotBlank()) { "模型未返回验证码" }
            val requestId = response.header("x-request-id")
                ?: responseJson.get("id")?.asString
                ?: "unknown"
            val totalTokens = responseJson.getAsJsonObject("usage")
                ?.get("total_tokens")?.asInt
            val usage = totalTokens?.let { ", totalTokens=$it" }.orEmpty()
            val elapsed = System.currentTimeMillis() - startedAt
            AppLog.put(
                "验证码 AI 识别成功: model=$model, requestId=$requestId$usage, elapsedMs=$elapsed"
            )
            return result
        }
    }

    private fun cleanStaleFiles(cacheDirectory: File) {
        val staleBefore = System.currentTimeMillis() - STALE_FILE_AGE_MS
        val deleted = cacheDirectory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.lastModified() < staleBefore }
            ?.count { it.delete() }
            ?: 0
        if (deleted > 0) {
            AppLog.put("验证码 AI 已清理遗留私有临时图片: count=$deleted")
        }
    }

    private const val CACHE_DIRECTORY = "captcha_ai"
    private const val FILE_PREFIX = "captcha-"
    private const val STALE_FILE_AGE_MS = 10 * 60 * 1000L
}
