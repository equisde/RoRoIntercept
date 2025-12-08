package com.httpinterceptor.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView
import com.httpinterceptor.R
import com.httpinterceptor.model.HttpRequest
import com.httpinterceptor.model.HttpResponse
import java.text.SimpleDateFormat
import java.util.*

class RequestAdapter(
    private val onItemClick: (HttpRequest) -> Unit
) : RecyclerView.Adapter<RequestAdapter.ViewHolder>() {
    
    private val requests = mutableListOf<HttpRequest>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMethod: MaterialTextView = view.findViewById(R.id.tvMethod)
        val tvUrl: MaterialTextView = view.findViewById(R.id.tvUrl)
        val tvStatus: MaterialTextView = view.findViewById(R.id.tvStatus)
        val tvTime: MaterialTextView = view.findViewById(R.id.tvTime)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_request, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val request = requests[position]
        
        holder.tvMethod.text = request.method
        holder.tvMethod.setBackgroundColor(getMethodColor(request.method))
        holder.tvUrl.text = "${request.host}${request.path}"
        holder.tvTime.text = dateFormat.format(Date(request.timestamp))
        
        request.response?.let { response ->
            holder.tvStatus.text = response.statusCode.toString()
            holder.tvStatus.setTextColor(getStatusColor(response.statusCode))
        } ?: run {
            holder.tvStatus.text = "..."
            holder.tvStatus.setTextColor(Color.GRAY)
        }
        
        holder.itemView.setOnClickListener {
            onItemClick(request)
        }
    }
    
    override fun getItemCount() = requests.size
    
    fun addRequest(request: HttpRequest) {
        requests.add(0, request)
        notifyItemInserted(0)
    }
    
    fun updateResponse(requestId: Long, response: HttpResponse) {
        val index = requests.indexOfFirst { it.id == requestId }
        if (index != -1) {
            requests[index].response = response
            notifyItemChanged(index)
        }
    }
    
    fun setRequests(newRequests: List<HttpRequest>) {
        requests.clear()
        requests.addAll(newRequests)
        notifyDataSetChanged()
    }
    
    fun clearRequests() {
        requests.clear()
        notifyDataSetChanged()
    }
    
    private fun getMethodColor(method: String): Int {
        return when (method) {
            "GET" -> Color.parseColor("#4CAF50")
            "POST" -> Color.parseColor("#2196F3")
            "PUT" -> Color.parseColor("#FF9800")
            "DELETE" -> Color.parseColor("#F44336")
            "PATCH" -> Color.parseColor("#9C27B0")
            else -> Color.parseColor("#607D8B")
        }
    }
    
    private fun getStatusColor(status: Int): Int {
        return when {
            status < 200 -> Color.parseColor("#2196F3")
            status < 300 -> Color.parseColor("#4CAF50")
            status < 400 -> Color.parseColor("#FF9800")
            status < 500 -> Color.parseColor("#F44336")
            else -> Color.parseColor("#9C27B0")
        }
    }
}
