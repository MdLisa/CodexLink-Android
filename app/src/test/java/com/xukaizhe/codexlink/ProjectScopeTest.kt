package com.xukaizhe.codexlink

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProjectScopeTest {
    @Test fun oldBackendRemainsRestrictedToItsWorkspaceList() {
        val scope = ProjectScope.fromStatus(JSONObject("""{"workspaces":["D:/Projects/A"]}"""))
        assertFalse(scope.all)
        assertEquals(listOf("D:/Projects/A"), scope.roots)
        assertEquals("", scope.revision)
    }

    @Test fun multipleDirectoriesAndRevisionComeFromDevicePolicy() {
        val scope = ProjectScope.fromStatus(JSONObject("""{"workspaces":["old"],"projectAccess":{"mode":"directories","roots":["D:/A","C:/B"],"revision":"changed"},"capabilities":{"perDeviceProjects":true}}"""))
        assertFalse(scope.all)
        assertEquals(listOf("D:/A", "C:/B"), scope.roots)
        assertEquals("changed", scope.revision)
    }

    @Test fun allDirectoriesRequiresExplicitBackendCapabilityAndPolicy() {
        val status = JSONObject("""{"projectAccess":{"mode":"all","roots":[],"revision":"all"}}""")
        assertFalse(ProjectScope.fromStatus(status).all)
        status.put("capabilities", JSONObject().put("perDeviceProjects", true))
        assertTrue(ProjectScope.fromStatus(status).all)
        status.getJSONObject("projectAccess").put("mode", "unknown")
        assertFalse(ProjectScope.fromStatus(status).all)
    }
}
