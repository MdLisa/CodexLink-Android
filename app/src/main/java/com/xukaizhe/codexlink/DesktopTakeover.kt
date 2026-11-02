package com.xukaizhe.codexlink

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject

data class TakeoverPlan(val threadId: String, val confirmationId: String, val expiresAt: Long, val desktopCount: Int) {
    companion object {
        fun from(result: JSONObject): TakeoverPlan {
            val thread = result.str("threadId")
            val confirmation = result.str("confirmationId")
            require(thread.isNotBlank() && validImageId(confirmation)) { "电脑返回的接管确认无效，请刷新后重试。" }
            return TakeoverPlan(thread, confirmation, result.optLong("expiresAt"), result.optInt("desktopCount"))
        }
    }
}

internal suspend fun BridgeApi.confirmDesktopTakeover(
    plan: TakeoverPlan,
    onProgress: (String) -> Unit,
    pause: suspend () -> Unit = { delay(700) },
): JSONObject {
    val route = "/api/threads/${plan.threadId}/takeover"
    try {
        request(route, "POST", JSONObject().put("confirmationId", plan.confirmationId))
    } catch (e: BridgeException) {
        throw e
    } catch (_: IOException) {
        // A lost response must never cause a second desktop-close request.
        onProgress("正在核对接管结果…")
    }
    try { return withTimeout(120_000) { repeat(150) {
        val result = try { request("$route/${plan.confirmationId}") }
        catch (e: BridgeException) { throw e }
        catch (_: IOException) { onProgress("正在重连并核对接管结果…"); pause(); return@repeat }
        when (result.str("state")) {
            "succeeded" -> return@withTimeout result
            "failed" -> throw IllegalStateException(result.str("error", "接管失败，请刷新原会话。"))
            "checking" -> onProgress("正在检查会话占用…")
            "closingDesktop" -> onProgress("正在请求电脑端正常退出…")
            "acquiring" -> onProgress("正在接管原会话…")
            else -> throw IllegalStateException("无法确认接管状态，请刷新原会话后检查。")
        }
        pause()
    }; throw IllegalStateException("接管尚未确认完成，请刷新原会话；不要重复退出电脑端。") } }
    catch (_: TimeoutCancellationException) { throw IllegalStateException("接管等待超时，请刷新原会话确认结果。") }
}
