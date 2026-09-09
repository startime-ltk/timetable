package com.lingion.sleepy.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lingion.sleepy.BuildConfig
import com.lingion.sleepy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/**
 * 智谱清言(BigModel, open.bigmodel.cn) 视觉模型 glm-4v-plus 识图调用。
 *
 * 用途: AI 识图导入课表 — 把课表截图压缩后 base64 化, POST 到 OpenAI 兼容
 * /chat/completions, system/user 提示词复用 ai_prompt_text(#sleepy-v1 输出规则),
 * 返回文本直接交 ScheduleParser 走现有预览/冲突流程。
 *
 * 设计: 零第三方依赖, 只用 Android 内置 HttpURLConnection + org.json,
 * 避免为联网功能引入 gradle 新依赖下载风险。
 */
object BigModelVision {
    private const val TAG = "BigModelVision"
    private const val ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    private const val MODEL = "glm-4.6v"
    private const val MAX_EDGE = 1600
    private const val JPEG_QUALITY = 85

    /** 区分可读错误的轻量异常; 文案映射在 UI 层做, detail 只进日志。 */
    sealed class VisionError(message: String) : Exception(message) {
        class Network(message: String) : VisionError(message)
        class InvalidKey(message: String) : VisionError(message)
        class Server(message: String) : VisionError(message)
        class BadContent(message: String) : VisionError(message)
    }

    /**
     * 读取图片 URI → 压缩 → base64 data URI → 调智谱 glm-4v-plus。
     * @return 模型返回文本(期望首行 #sleepy-v1, 走 ScheduleParser 解析)
     * @throws VisionError 细分错误; IOException 等网络底层会归入 Network/Server
     */
    suspend fun recognizeSchedule(context: Context, uri: Uri, apiKey: String): String =
        withContext(Dispatchers.IO) {
            val dataUri = compressToDataUri(context, uri)
            val prompt = context.getString(R.string.ai_prompt_text)
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
            val userContent = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", dataUri))
                )
            val body = JSONObject()
                .put("model", MODEL)
                // glm-4.6v 默认开思考模式, 会先把 max_tokens 吃光(reasoning_tokens)导致正文为空, 必须显式关闭
                .put("thinking", JSONObject().put("type", "disabled"))
                // glm-4.6v/glm-4v-plus 限制 max_tokens ∈ [1,2048]; 取 2048 上限防长课表输出截断丢课
                .put("max_tokens", 2048)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject().put("role", "user").put("content", userContent)
                    )
                )
            val responseJson = postJson(body.toString(), apiKey)
            parseContent(responseJson)
        }

    private fun postJson(body: String, apiKey: String): String {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            // 识图等待模型推理较久: 连接 60s / 读 90s (需求 60s+)
            conn.connectTimeout = 60_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("User-Agent", "Sleepy/${BuildConfig.VERSION_NAME}")
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } catch (e: IOException) {
                // 证书/代理/断网等写失败统一按网络错误, 不再读 errorStream(连接已不可用)
                Log.w(TAG, "request write failed", e)
                throw VisionError.Network(e.message ?: "write failed")
            }
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.w(TAG, "auth rejected code=$code detail=$detail")
                throw VisionError.InvalidKey("http $code")
            }
            if (code !in 200..299) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.w(TAG, "server error code=$code detail=$detail")
                throw VisionError.Server("http $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: VisionError) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "network error", e)
            throw VisionError.Network(e.message ?: "io error")
        } catch (e: Exception) {
            Log.w(TAG, "unexpected error", e)
            throw VisionError.Server(e.message ?: "unknown error")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseContent(responseJson: String): String {
        try {
            val root = JSONObject(responseJson)
            val choices = root.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                val content = message?.optString("content", null)
                if (!content.isNullOrBlank()) return content
            }
            // 200 但无 choices(如内容安全拦截/配额), 取 error 详情进日志
            val err = root.optJSONObject("error")
            val detail = err?.optString("message") ?: "empty choices"
            Log.w(TAG, "bad content response: $detail")
            throw VisionError.BadContent(detail)
        } catch (e: VisionError) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "parse response failed", e)
            throw VisionError.Server("bad json response")
        }
    }

    /** 缩放(最长边 ≤ MAX_EDGE) + JPEG 压缩 → "data:image/jpeg;base64,..."。 */
    private fun compressToDataUri(context: Context, uri: Uri): String {
        val decoded = decodeScaled(context, uri)
        val w = decoded.width
        val h = decoded.height
        val scale = if (w <= 0 || h <= 0) 1f else min(1f, MAX_EDGE / max(w, h).toFloat())
        val finalBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (w * scale).toInt(), (h * scale).toInt(), true)
        } else {
            decoded
        }
        val bos = ByteArrayOutputStream()
        if (!finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)) {
            Log.w(TAG, "jpeg compress failed")
        }
        if (finalBitmap !== decoded) finalBitmap.recycle()
        decoded.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw VisionError.BadContent("decode bounds failed")
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw VisionError.BadContent("decode failed")
        return bmp
    }
}
