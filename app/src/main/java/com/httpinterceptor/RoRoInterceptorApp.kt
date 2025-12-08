package com.httpinterceptor

import android.app.Application
import android.util.Log
import com.httpinterceptor.utils.CrashHandler

class RoRoInterceptorApp : Application() {
    
    companion object {
        private const val TAG = "RoRoInterceptorApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize crash handler
        CrashHandler.init(this)
        Log.i(TAG, "RoRo Interceptor initialized - Crash handler enabled")
    }
}
