package com.httpinterceptor.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.httpinterceptor.model.ProxyRule
import java.io.File

class RulesManager(private val context: Context) {
    
    private val rulesFile: File = File(context.filesDir, "proxy_rules.json")
    private val gson = Gson()
    private val rules = mutableListOf<ProxyRule>()
    
    init {
        loadRules()
    }
    
    @Synchronized
    private fun loadRules() {
        if (rulesFile.exists()) {
            try {
                val json = rulesFile.readText()
                val type = object : TypeToken<List<ProxyRule>>() {}.type
                val loadedRules: List<ProxyRule> = gson.fromJson(json, type)
                rules.clear()
                rules.addAll(loadedRules)
                Log.d(TAG, "Loaded ${rules.size} rules")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading rules", e)
            }
        }
    }
    
    @Synchronized
    private fun saveRules() {
        try {
            val json = gson.toJson(rules)
            rulesFile.writeText(json)
            Log.d(TAG, "Saved ${rules.size} rules")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving rules", e)
        }
    }
    
    @Synchronized
    fun addRule(rule: ProxyRule) {
        rules.add(rule)
        saveRules()
    }
    
    @Synchronized
    fun updateRule(ruleId: Long, updatedRule: ProxyRule) {
        val index = rules.indexOfFirst { it.id == ruleId }
        if (index != -1) {
            rules[index] = updatedRule
            saveRules()
        }
    }
    
    @Synchronized
    fun deleteRule(ruleId: Long) {
        rules.removeIf { it.id == ruleId }
        saveRules()
    }
    
    @Synchronized
    fun getRules(): List<ProxyRule> = rules.toList()
    
    @Synchronized
    fun getEnabledRules(): List<ProxyRule> = rules.filter { it.enabled }
    
    fun findMatchingRules(url: String): List<ProxyRule> {
        return getEnabledRules().filter { rule ->
            when (rule.matchType) {
                com.httpinterceptor.model.MatchType.CONTAINS -> url.contains(rule.urlPattern)
                com.httpinterceptor.model.MatchType.EXACT -> url == rule.urlPattern
                com.httpinterceptor.model.MatchType.STARTS_WITH -> url.startsWith(rule.urlPattern)
                com.httpinterceptor.model.MatchType.ENDS_WITH -> url.endsWith(rule.urlPattern)
                com.httpinterceptor.model.MatchType.REGEX -> {
                    try {
                        Regex(rule.urlPattern).matches(url)
                    } catch (e: Exception) {
                        false
                    }
                }
            }
        }
    }
    
    companion object {
        private const val TAG = "RulesManager"
    }
}
