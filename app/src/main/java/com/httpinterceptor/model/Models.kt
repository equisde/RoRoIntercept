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
    // Headers modification
    val modifyHeaders: Map<String, String>? = null, // Add/Replace headers
    val removeHeaders: List<String>? = null,         // Remove specific headers
    val searchReplaceHeaders: List<SearchReplace>? = null, // Find & replace in headers
    
    // Body modification
    val modifyBody: String? = null,                  // Regex to remove from body
    val replaceBody: String? = null,                 // Complete body replacement
    val searchReplaceBody: List<SearchReplace>? = null  // Find & replace in body
)

data class SearchReplace(
    val search: String,           // Text or regex pattern to find
    val replace: String,          // Replacement text
    val useRegex: Boolean = false, // Use regex for search
    val caseSensitive: Boolean = true, // Case sensitive matching
    val replaceAll: Boolean = true     // Replace all occurrences or just first
)
