package com.tuapp.inventario.utils

import android.util.Log
import com.tuapp.inventario.BuildConfig

object Logger {
    
    fun d(tag: String, message: String) {
        // Solo mostrar logs en debug mode
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
    
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
