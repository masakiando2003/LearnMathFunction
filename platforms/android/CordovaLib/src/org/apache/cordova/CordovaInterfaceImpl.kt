/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/

package org.apache.cordova

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Pair

import org.json.JSONException
import org.json.JSONObject

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Default implementation of CordovaInterface.
 */
class CordovaInterfaceImpl @JvmOverloads constructor(activity: Activity, threadPool: ExecutorService = Executors.newCachedThreadPool()) : CordovaInterface {
    @get:Override
    var activity: Activity
        protected set
    @get:Override
    var threadPool: ExecutorService
        protected set
    protected var pluginManager: PluginManager? = null

    protected var savedResult: ActivityResultHolder? = null
    protected var permissionResultCallbacks: CallbackMap
    protected var activityResultCallback: CordovaPlugin? = null
    protected var initCallbackService: String? = null
    protected var activityResultRequestCode: Int = 0
    protected var activityWasDestroyed = false
    protected var savedPluginState: Bundle

    val context: Context
        @Override
        get() = activity

    init {
        this.activity = activity
        this.threadPool = threadPool
        this.permissionResultCallbacks = CallbackMap()
    }

    @Override
    fun startActivityForResult(command: CordovaPlugin, intent: Intent, requestCode: Int) {
        setActivityResultCallback(command)
        try {
            activity.startActivityForResult(intent, requestCode)
        } catch (e: RuntimeException) { // E.g.: ActivityNotFoundException
            activityResultCallback = null
            throw e
        }

    }

    @Override
    fun setActivityResultCallback(plugin: CordovaPlugin) {
        // Cancel any previously pending activity.
        if (activityResultCallback != null) {
            activityResultCallback!!.onActivityResult(activityResultRequestCode, Activity.RESULT_CANCELED, null)
        }
        activityResultCallback = plugin
    }

    @Override
    fun onMessage(id: String, data: Object): Object? {
        if ("exit".equals(id)) {
            activity.finish()
        }
        return null
    }

    /**
     * Dispatches any pending onActivityResult callbacks and sends the resume event if the
     * Activity was destroyed by the OS.
     */
    fun onCordovaInit(pluginManager: PluginManager?) {
        this.pluginManager = pluginManager
        if (savedResult != null) {
            onActivityResult(savedResult!!.requestCode, savedResult!!.resultCode, savedResult!!.intent)
        } else if (activityWasDestroyed) {
            // If there was no Activity result, we still need to send out the resume event if the
            // Activity was destroyed by the OS
            activityWasDestroyed = false
            if (pluginManager != null) {
                val appPlugin = pluginManager!!.getPlugin(CoreAndroid.PLUGIN_NAME) as CoreAndroid
                if (appPlugin != null) {
                    val obj = JSONObject()
                    try {
                        obj.put("action", "resume")
                    } catch (e: JSONException) {
                        LOG.e(TAG, "Failed to create event message", e)
                    }

                    appPlugin!!.sendResumeEvent(PluginResult(PluginResult.Status.OK, obj))
                }
            }

        }
    }

    /**
     * Routes the result to the awaiting plugin. Returns false if no plugin was waiting.
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent): Boolean {
        var callback = activityResultCallback
        if (callback == null && initCallbackService != null) {
            // The application was restarted, but had defined an initial callback
            // before being shut down.
            savedResult = ActivityResultHolder(requestCode, resultCode, intent)
            if (pluginManager != null) {
                callback = pluginManager!!.getPlugin(initCallbackService)
                if (callback != null) {
                    callback!!.onRestoreStateForActivityResult(savedPluginState.getBundle(callback!!.getServiceName()),
                            ResumeCallback(callback!!.getServiceName(), pluginManager))
                }
            }
        }
        activityResultCallback = null

        if (callback != null) {
            LOG.d(TAG, "Sending activity result to plugin")
            initCallbackService = null
            savedResult = null
            callback!!.onActivityResult(requestCode, resultCode, intent)
            return true
        }
        LOG.w(TAG, "Got an activity result, but no plugin was registered to receive it" + if (savedResult != null) " yet!" else ".")
        return false
    }

    /**
     * Call this from your startActivityForResult() overload. This is required to catch the case
     * where plugins use Activity.startActivityForResult() + CordovaInterface.setActivityResultCallback()
     * rather than CordovaInterface.startActivityForResult().
     */
    fun setActivityResultRequestCode(requestCode: Int) {
        activityResultRequestCode = requestCode
    }

    /**
     * Saves parameters for startActivityForResult().
     */
    fun onSaveInstanceState(outState: Bundle) {
        if (activityResultCallback != null) {
            val serviceName = activityResultCallback!!.getServiceName()
            outState.putString("callbackService", serviceName)
        }
        if (pluginManager != null) {
            outState.putBundle("plugin", pluginManager!!.onSaveInstanceState())
        }

    }

    /**
     * Call this from onCreate() so that any saved startActivityForResult parameters will be restored.
     */
    fun restoreInstanceState(savedInstanceState: Bundle) {
        initCallbackService = savedInstanceState.getString("callbackService")
        savedPluginState = savedInstanceState.getBundle("plugin")
        activityWasDestroyed = true
    }

    private class ActivityResultHolder(private val requestCode: Int, private val resultCode: Int, private val intent: Intent)

    /**
     * Called by the system when the user grants permissions
     *
     * @param requestCode
     * @param permissions
     * @param grantResults
     */
    @Throws(JSONException::class)
    fun onRequestPermissionResult(requestCode: Int, permissions: Array<String>,
                                  grantResults: IntArray) {
        val callback = permissionResultCallbacks.getAndRemoveCallback(requestCode)
        if (callback != null) {
            callback!!.first.onRequestPermissionResult(callback!!.second, permissions, grantResults)
        }
    }

    fun requestPermission(plugin: CordovaPlugin, requestCode: Int, permission: String) {
        val permissions = arrayOfNulls<String>(1)
        permissions[0] = permission
        requestPermissions(plugin, requestCode, permissions)
    }

    @SuppressLint("NewApi")
    fun requestPermissions(plugin: CordovaPlugin, requestCode: Int, permissions: Array<String>) {
        val mappedRequestCode = permissionResultCallbacks.registerCallback(plugin, requestCode)
        activity.requestPermissions(permissions, mappedRequestCode)
    }

    fun hasPermission(permission: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val result = activity.checkSelfPermission(permission)
            return PackageManager.PERMISSION_GRANTED === result
        } else {
            return true
        }
    }

    companion object {
        private val TAG = "CordovaInterfaceImpl"
    }
}
