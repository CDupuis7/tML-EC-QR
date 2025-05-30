package org.opencv

import android.content.Context
import android.util.Log

/**
 * Simple wrapper for OpenCV initialization
 */
class OpenCV {
    companion object {
        private const val TAG = "OpenCV"
        private var initialized = false
        
        /**
         * Initialize OpenCV statically
         */
        init {
            try {
                System.loadLibrary("opencv_java4")
                initialized = true
                Log.d(TAG, "OpenCV loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load OpenCV library: ${e.message}")
            }
        }
        
        /**
         * Check if OpenCV is initialized
         */
        fun isInitialized(): Boolean {
            return initialized
        }
        
        /**
         * Try to initialize OpenCV
         */
        fun init(context: Context): Boolean {
            if (initialized) {
                return true
            }
            
            try {
                System.loadLibrary("opencv_java4")
                initialized = true
                Log.d(TAG, "OpenCV initialized successfully")
                return true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load OpenCV: ${e.message}")
                return false
            }
        }
    }
} 