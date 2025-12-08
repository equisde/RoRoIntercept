package com.httpinterceptor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.httpinterceptor.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CrashLogsActivity : AppCompatActivity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CrashLogsAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_logs)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Crash Logs"
        
        recyclerView = findViewById(R.id.recyclerViewCrashLogs)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        loadCrashLogs()
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
    
    private fun loadCrashLogs() {
        val crashFiles = filesDir.listFiles { _, name ->
            name.startsWith("crash_") && name.endsWith(".txt")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        
        adapter = CrashLogsAdapter(crashFiles.toMutableList()) { file ->
            showCrashLogDialog(file)
        }
        recyclerView.adapter = adapter
        
        if (crashFiles.isEmpty()) {
            Toast.makeText(this, "No crash logs found", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showCrashLogDialog(file: File) {
        val content = file.readText()
        
        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage(content)
            .setPositiveButton("Copy") { _, _ ->
                copyToClipboard(content)
            }
            .setNegativeButton("Delete") { _, _ ->
                file.delete()
                loadCrashLogs()
                Toast.makeText(this, "Crash log deleted", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Close", null)
            .show()
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Crash Log", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

class CrashLogsAdapter(
    private val crashFiles: MutableList<File>,
    private val onItemClick: (File) -> Unit
) : RecyclerView.Adapter<CrashLogsAdapter.ViewHolder>() {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tvCrashFileName)
        val tvFileDate: TextView = view.findViewById(R.id.tvCrashFileDate)
        val tvFileSize: TextView = view.findViewById(R.id.tvCrashFileSize)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_crash_log, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = crashFiles[position]
        holder.tvFileName.text = file.name
        holder.tvFileDate.text = dateFormat.format(Date(file.lastModified()))
        holder.tvFileSize.text = "${file.length() / 1024} KB"
        
        holder.itemView.setOnClickListener {
            onItemClick(file)
        }
    }
    
    override fun getItemCount() = crashFiles.size
}
