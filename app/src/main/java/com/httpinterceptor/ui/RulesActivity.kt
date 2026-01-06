package com.httpinterceptor.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.httpinterceptor.R
import com.httpinterceptor.model.MatchType
import com.httpinterceptor.model.ModifyAction
import com.httpinterceptor.model.ProxyRule
import com.httpinterceptor.model.RuleAction
import com.httpinterceptor.proxy.ProxyService

class RulesActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RulesAdapter
    private var proxyService: ProxyService? = null
    private var bound = false
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ProxyService.LocalBinder
            proxyService = binder.getService()
            bound = true
            loadRules()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            bound = false
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Reglas"
        
        recyclerView = findViewById(R.id.recyclerViewRules)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = RulesAdapter(
            onToggle = { rule, enabled -> updateRule(rule.copy(enabled = enabled)) },
            onDelete = { rule -> deleteRule(rule) }
        )
        recyclerView.adapter = adapter
        
        findViewById<FloatingActionButton>(R.id.fabAddRule).setOnClickListener {
            showAddRuleDialog()
        }
        
        val intent = Intent(this, ProxyService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    
    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
    
    private fun loadRules() {
        val rules = proxyService?.getRules() ?: emptyList()
        adapter.submitRules(rules)
    }
    
    private fun addRule(rule: ProxyRule) {
        proxyService?.addRule(rule)
        loadRules()
    }
    
    private fun updateRule(rule: ProxyRule) {
        proxyService?.updateRule(rule.id, rule)
        loadRules()
    }
    
    private fun deleteRule(rule: ProxyRule) {
        proxyService?.deleteRule(rule.id)
        loadRules()
    }
    
    private fun showAddRuleDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_rule, null)
        val inputName = view.findViewById<TextInputEditText>(R.id.inputRuleName)
        val inputPattern = view.findViewById<TextInputEditText>(R.id.inputRulePattern)
        val inputHeaderKey = view.findViewById<TextInputEditText>(R.id.inputHeaderKey)
        val inputHeaderValue = view.findViewById<TextInputEditText>(R.id.inputHeaderValue)
        val inputBodyReplace = view.findViewById<TextInputEditText>(R.id.inputBodyReplace)
        val spinnerMatch = view.findViewById<Spinner>(R.id.spinnerMatchType)
        val spinnerAction = view.findViewById<Spinner>(R.id.spinnerAction)
        
        spinnerMatch.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            MatchType.values().map { it.name }
        )
        
        spinnerAction.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            RuleAction.values().map { it.name }
        )
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Nueva regla")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val name = inputName.text?.toString()?.trim().orEmpty()
                val pattern = inputPattern.text?.toString()?.trim().orEmpty()
                if (name.isBlank() || pattern.isBlank()) {
                    return@setPositiveButton
                }
                
                val action = RuleAction.values()[spinnerAction.selectedItemPosition]
                val matchType = MatchType.values()[spinnerMatch.selectedItemPosition]
                val headerKey = inputHeaderKey.text?.toString()?.trim().orEmpty()
                val headerValue = inputHeaderValue.text?.toString()?.trim().orEmpty()
                val replaceBody = inputBodyReplace.text?.toString()?.trim().orEmpty()
                
                val modifyAction = if (action == RuleAction.MODIFY || action == RuleAction.REPLACE) {
                    ModifyAction(
                        modifyHeaders = if (headerKey.isNotBlank()) mapOf(headerKey to headerValue) else null,
                        replaceBody = replaceBody.ifBlank { null }
                    )
                } else null
                
                val rule = ProxyRule(
                    id = System.currentTimeMillis(),
                    enabled = true,
                    name = name,
                    urlPattern = pattern,
                    matchType = matchType,
                    action = action,
                    modifyRequest = modifyAction,
                    modifyResponse = modifyAction
                )
                addRule(rule)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

private class RulesAdapter(
    private val onToggle: (ProxyRule, Boolean) -> Unit,
    private val onDelete: (ProxyRule) -> Unit
) : RecyclerView.Adapter<RulesAdapter.ViewHolder>() {
    
    private val rules = mutableListOf<ProxyRule>()
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvRuleName)
        val tvPattern: TextView = view.findViewById(R.id.tvRulePattern)
        val tvAction: TextView = view.findViewById(R.id.tvRuleAction)
        val switchEnabled: SwitchMaterial = view.findViewById(R.id.switchEnabled)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteRule)
    }
    
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rule, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rule = rules[position]
        holder.tvName.text = rule.name
        holder.tvPattern.text = "${rule.matchType.name}: ${rule.urlPattern}"
        holder.tvAction.text = "Acción: ${rule.action.name}"
        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = rule.enabled
        holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            onToggle(rule, isChecked)
        }
        holder.btnDelete.setOnClickListener { onDelete(rule) }
    }
    
    override fun getItemCount(): Int = rules.size
    
    fun submitRules(newRules: List<ProxyRule>) {
        rules.clear()
        rules.addAll(newRules.sortedBy { it.name.lowercase() })
        notifyDataSetChanged()
    }
}
