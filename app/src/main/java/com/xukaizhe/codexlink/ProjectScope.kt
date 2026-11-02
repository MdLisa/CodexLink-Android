package com.xukaizhe.codexlink

import org.json.JSONObject

data class ProjectScope(val all: Boolean = false, val roots: List<String> = emptyList(), val revision: String = "") {
    companion object {
        fun fromStatus(status: JSONObject): ProjectScope {
            val policy = status.optJSONObject("projectAccess")
            val roots = (policy?.optJSONArray("roots") ?: status.optJSONArray("workspaces"))
                ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            return ProjectScope(
                all = status.optJSONObject("capabilities")?.optBoolean("perDeviceProjects") == true && policy?.str("mode") == "all",
                roots = roots,
                revision = policy?.str("revision").orEmpty(),
            )
        }
    }
}
