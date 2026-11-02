package com.xukaizhe.codexlink

import org.json.JSONObject

fun approvalLabel(reviewer: String): String = when (reviewer) {
    "user" -> "手动审批"
    "auto_review" -> "帮我批准（官方自动审查）"
    "chat_only" -> "仅聊天（允许联网）"
    else -> "审批状态未知，请检查电脑设置"
}

data class ApprovalSettings(val reviewer: String = "", val perDevice: Boolean = false, val modeRevision: String = "") {
    val chatOnly get() = reviewer == "chat_only"
    val executionMode get() = if(chatOnly)"chat_only" else "operate"
    fun acceptsPending(pending:Pending) = pending.executionMode==executionMode&&(pending.modeRevision.isEmpty()||pending.modeRevision==modeRevision)
    companion object {
        fun fromStatus(status: JSONObject): ApprovalSettings {
            val perDevice = status.optJSONObject("capabilities")?.optBoolean("perDeviceApprovals") == true
            val reviewer = if (perDevice) status.optJSONObject("executionSettings")?.str("approvalsReviewer")
                ?: status.str("approvalsReviewer") else status.str("approvalsReviewer")
            return ApprovalSettings(reviewer, perDevice, status.optJSONObject("executionSettings")?.str("modeRevision").orEmpty())
        }
    }
}
