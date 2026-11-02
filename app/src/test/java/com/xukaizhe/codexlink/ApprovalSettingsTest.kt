package com.xukaizhe.codexlink

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApprovalSettingsTest {
    @Test fun chatOnlyIsADeviceModeWithNoProjectRootsOrTakeover() {
        val status=JSONObject("""{"capabilities":{"perDeviceApprovals":true},"executionSettings":{"approvalsReviewer":"chat_only"},"projectAccess":{"mode":"chat_only","roots":[],"revision":"mode-revision"},"workspaces":[]}""")
        assertTrue(ApprovalSettings.fromStatus(status).chatOnly)
        assertEquals("仅聊天（允许联网）",approvalLabel("chat_only"))
        val scope=ProjectScope.fromStatus(status);assertFalse(scope.all);assertTrue(scope.roots.isEmpty())
        assertEquals("mode-revision",scope.revision)
    }
    @Test fun pendingMessageKeepsItsModeAcrossRestartAndOlderMessagesRemainOperate() {
        val message=Pending("message-id","http://localhost","chat-id","math",emptyList(),executionMode="chat_only")
        assertEquals("chat_only",Pending.from(message.json()).executionMode)
        val old=message.json().apply{remove("executionMode")}
        assertEquals("operate",Pending.from(old).executionMode)
    }
    @Test fun retriesCannotCrossAnOfflineModeChangeAndReturn() {
        val pending=Pending("message-id","http://localhost","chat-id","math",emptyList(),modeRevision="first-mode")
        assertTrue(ApprovalSettings("user",true,"first-mode").acceptsPending(pending))
        assertTrue(ApprovalSettings("auto_review",true,"first-mode").acceptsPending(pending))
        assertFalse(ApprovalSettings("chat_only",true,"second-mode").acceptsPending(pending))
        assertFalse(ApprovalSettings("user",true,"third-mode").acceptsPending(pending))
        assertEquals("first-mode",Pending.from(pending.json()).modeRevision)
    }
    @Test fun olderBackendDisplaysItsGlobalReviewer() {
        val settings = ApprovalSettings.fromStatus(JSONObject("""{"approvalsReviewer":"auto_review"}"""))
        assertFalse(settings.perDevice)
        assertEquals("auto_review", settings.reviewer)
    }

    @Test fun devicePolicyOverridesTheLegacyFieldWhenSupported() {
        val settings = ApprovalSettings.fromStatus(JSONObject("""{"approvalsReviewer":"auto_review","capabilities":{"perDeviceApprovals":true},"executionSettings":{"approvalsReviewer":"user"}}"""))
        assertTrue(settings.perDevice)
        assertEquals("user", settings.reviewer)
    }

    @Test fun unknownOrMissingModesAreNotPresentedAsAutomaticApproval() {
        for (value in listOf("", "never", "accept-all")) assertTrue(approvalLabel(value).contains("未知"))
        assertEquals("", ApprovalSettings.fromStatus(JSONObject()).reviewer)
    }

    @Test fun taskRetainsItsOwnReviewerInsteadOfUsingCurrentDeviceSettings() {
        val task = BridgeCodec.task(JSONObject("""{"id":"job","state":"queued","approvalsReviewer":"user"}"""))
        assertEquals("user", task.approvalsReviewer)
        assertEquals("", BridgeCodec.task(JSONObject("""{"id":"old"}""")).approvalsReviewer)
    }
}
