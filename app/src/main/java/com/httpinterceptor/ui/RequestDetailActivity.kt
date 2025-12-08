package com.httpinterceptor.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.httpinterceptor.R

class RequestDetailActivity : AppCompatActivity() {
    
    private lateinit var tabLayout: TabLayout
    private lateinit var tvContent: TextView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_detail)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        tabLayout = findViewById(R.id.tabLayout)
        tvContent = findViewById(R.id.tvContent)
        
        val requestId = intent.getLongExtra("REQUEST_ID", -1)
        
        tabLayout.addTab(tabLayout.newTab().setText("Request"))
        tabLayout.addTab(tabLayout.newTab().setText("Response"))
        tabLayout.addTab(tabLayout.newTab().setText("Headers"))
        tabLayout.addTab(tabLayout.newTab().setText("Body"))
        
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                updateContent(tab.position)
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        
        updateContent(0)
    }
    
    private fun updateContent(position: Int) {
        tvContent.text = when (position) {
            0 -> "Request details will be shown here"
            1 -> "Response details will be shown here"
            2 -> "Headers will be shown here"
            3 -> "Body content will be shown here"
            else -> ""
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
