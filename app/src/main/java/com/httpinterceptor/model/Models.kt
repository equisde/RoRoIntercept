package com.httpinterceptor.model

data class HttpRequest(
    val id: Long,
    val timestamp: Long,
    val method: String,
    val url: String,
    val host: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    var response: HttpResponse? = null,
    var modified: Boolean = false
) {
    fun getHeadersString(): String {
        return headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }
    
    fun getBodyString(): String {
        return body?.toString(Charsets.UTF_8) ?: ""
    }
}

data class HttpResponse(
    val statusCode: Int,
    val statusMessage: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val timestamp: Long
) {
    fun getHeadersString(): String {
        return headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }
    
    fun getBodyString(): String {
        return body?.toString(Charsets.UTF_8) ?: ""
    }
}

data class ProxyRule(
    val id: Long,
    val enabled: Boolean,
    val name: String,
    val urlPattern: String,
    val matchType: MatchType,
    val action: RuleAction,
    val modifyRequest: ModifyAction? = null,
    val modifyResponse: ModifyAction? = null
)

enum class MatchType {
    CONTAINS, REGEX, EXACT, STARTS_WITH, ENDS_WITH
}

enum class RuleAction {
    ALLOW, BLOCK, MODIFY
}

data class ModifyAction(
    val modifyHeaders: Map<String, String>? = null,
    val modifyBody: String? = null,
    val replaceBody: String? = null
)
