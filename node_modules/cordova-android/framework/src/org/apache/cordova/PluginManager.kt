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

import java.util.LinkedHashMap

import org.json.JSONException

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Debug

/**
 * PluginManager is exposed to JavaScript in the Cordova WebView.
 *
 * Calling native plugin code can be done by calling PluginManager.exec(...)
 * from JavaScript.
 */
class PluginManager(private val app: CordovaWebView, private val ctx: CordovaInterface, pluginEntries: Collection<PluginEntry>) {

    // List of service entries
    private val pluginMap = LinkedHashMap<String, CordovaPlugin>()
    private val entryMap = LinkedHashMap<String, PluginEntry>()
    private var isInitialized: Boolean = false

    private val permissionRequester: CordovaPlugin? = null

    var pluginEntries: Collection<PluginEntry>
        get() = entryMap.values()
        set(pluginEntries) {
            if (isInitialized) {
                this.onPause(false)
                this.onDestroy()
                pluginMap.clear()
                entryMap.clear()
            }
            for (entry in pluginEntries) {
                addService(entry)
            }
            if (isInitialized) {
                startupPlugins()
            }
        }

    init {
        pluginEntries = pluginEntries
    }

    /**
     * Init when loading a new HTML page into webview.
     */
    fun init() {
        LOG.d(TAG, "init()")
        isInitialized = true
        this.onPause(false)
        this.onDestroy()
        pluginMap.clear()
        this.startupPlugins()
    }

    /**
     * Create plugins objects that have onload set.
     */
    private fun startupPlugins() {
        for (entry in entryMap.values()) {
            // Add a null entry to for each non-startup plugin to avoid ConcurrentModificationException
            // When iterating plugins.
            if (entry.onload) {
                getPlugin(entry.service)
            } else {
                pluginMap.put(entry.service, null)
            }
        }
    }

    /**
     * Receives a request for execution and fulfills it by finding the appropriate
     * Java class and calling it's execute method.
     *
     * PluginManager.exec can be used either synchronously or async. In either case, a JSON encoded
     * string is returned that will indicate if any errors have occurred when trying to find
     * or execute the class denoted by the clazz argument.
     *
     * @param service       String containing the service to run
     * @param action        String containing the action that the class is supposed to perform. This is
     * passed to the plugin execute method and it is up to the plugin developer
     * how to deal with it.
     * @param callbackId    String containing the id of the callback that is execute in JavaScript if
     * this is an async plugin call.
     * @param rawArgs       An Array literal string containing any arguments needed in the
     * plugin execute method.
     */
    fun exec(service: String, action: String, callbackId: String, rawArgs: String) {
        val plugin = getPlugin(service)
        if (plugin == null) {
            LOG.d(TAG, "exec() call to unknown plugin: $service")
            val cr = PluginResult(PluginResult.Status.CLASS_NOT_FOUND_EXCEPTION)
            app.sendPluginResult(cr, callbackId)
            return
        }
        val callbackContext = CallbackContext(callbackId, app)
        try {
            val pluginStartTime = System.currentTimeMillis()
            val wasValidAction = plugin!!.execute(action, rawArgs, callbackContext)
            val duration = System.currentTimeMillis() - pluginStartTime

            if (duration > SLOW_EXEC_WARNING_THRESHOLD) {
                LOG.w(TAG, "THREAD WARNING: exec() call to " + service + "." + action + " blocked the main thread for " + duration + "ms. Plugin should use CordovaInterface.getThreadPool().")
            }
            if (!wasValidAction) {
                val cr = PluginResult(PluginResult.Status.INVALID_ACTION)
                callbackContext.sendPluginResult(cr)
            }
        } catch (e: JSONException) {
            val cr = PluginResult(PluginResult.Status.JSON_EXCEPTION)
            callbackContext.sendPluginResult(cr)
        } catch (e: Exception) {
            LOG.e(TAG, "Uncaught exception from plugin", e)
            callbackContext.error(e.getMessage())
        }

    }

    /**
     * Get the plugin object that implements the service.
     * If the plugin object does not already exist, then create it.
     * If the service doesn't exist, then return null.
     *
     * @param service       The name of the service.
     * @return              CordovaPlugin or null
     */
    fun getPlugin(service: String): CordovaPlugin? {
        var ret = pluginMap.get(service)
        if (ret == null) {
            val pe = entryMap.get(service) ?: return null
            if (pe.plugin != null) {
                ret = pe.plugin
            } else {
                ret = instantiatePlugin(pe.pluginClass)
            }
            ret!!.privateInitialize(service, ctx, app, app.getPreferences())
            pluginMap.put(service, ret)
        }
        return ret
    }

    /**
     * Add a plugin class that implements a service to the service entry table.
     * This does not create the plugin object instance.
     *
     * @param service           The service name
     * @param className         The plugin class name
     */
    fun addService(service: String, className: String) {
        val entry = PluginEntry(service, className, false)
        this.addService(entry)
    }

    /**
     * Add a plugin class that implements a service to the service entry table.
     * This does not create the plugin object instance.
     *
     * @param entry             The plugin entry
     */
    fun addService(entry: PluginEntry) {
        this.entryMap.put(entry.service, entry)
        if (entry.plugin != null) {
            entry.plugin.privateInitialize(entry.service, ctx, app, app.getPreferences())
            pluginMap.put(entry.service, entry.plugin)
        }
    }

    /**
     * Called when the system is about to start resuming a previous activity.
     *
     * @param multitasking      Flag indicating if multitasking is turned on for app
     */
    fun onPause(multitasking: Boolean) {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                plugin!!.onPause(multitasking)
            }
        }
    }

    /**
     * Called when the system received an HTTP authentication request. Plugins can use
     * the supplied HttpAuthHandler to process this auth challenge.
     *
     * @param view              The WebView that is initiating the callback
     * @param handler           The HttpAuthHandler used to set the WebView's response
     * @param host              The host requiring authentication
     * @param realm             The realm for which authentication is required
     *
     * @return                  Returns True if there is a plugin which will resolve this auth challenge, otherwise False
     */
    fun onReceivedHttpAuthRequest(view: CordovaWebView, handler: ICordovaHttpAuthHandler, host: String, realm: String): Boolean {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null && plugin!!.onReceivedHttpAuthRequest(app, handler, host, realm)) {
                return true
            }
        }
        return false
    }

    /**
     * Called when he system received an SSL client certificate request.  Plugin can use
     * the supplied ClientCertRequest to process this certificate challenge.
     *
     * @param view              The WebView that is initiating the callback
     * @param request           The client certificate request
     *
     * @return                  Returns True if plugin will resolve this auth challenge, otherwise False
     */
    fun onReceivedClientCertRequest(view: CordovaWebView, request: ICordovaClientCertRequest): Boolean {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null && plugin!!.onReceivedClientCertRequest(app, request)) {
                return true
            }
        }
        return false
    }

    /**
     * Called when the activity will start interacting with the user.
     *
     * @param multitasking      Flag indicating if multitasking is turned on for app
     */
    fun onResume(multitasking: Boolean) {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                plugin!!.onResume(multitasking)
            }
        }
    }

    /**
     * Called when the activity is becoming visible to the user.
     */
    fun onStart() {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                plugin!!.onStart()
            }
        }
    }

    /**
     * Called when the activity is no longer visible to the user.
     */
    fun onStop() {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                plugin!!.onStop()
            }
        }
    }

    /**
     * The final call you receive before your activity is destroyed.
     */
    fun onDestroy() {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                plugin!!.onDestroy()
            }
        }
    }

    /**
     * Send a message to all plugins.
     *
     * @param id                The message id
     * @param data              The message data
     * @return                  Object to stop propagation or null
     */
    fun postMessage(id: String, data: Object): Object {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                val obj = plugin!!.onMessage(id, data)
                if (obj != null) {
                    return obj
                }
            }
        }
        return ctx.onMessage(id, data)
    }

    /**
     * Called when the activity receives a new intent.
     */
    fun onNewIntent(intent: Intent) {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                plugin!!.onNewIntent(intent)
            }
        }
    }

    /**
     * Called when the webview is going to request an external resource.
     *
     * This delegates to the installed plugins, and returns true/false for the
     * first plugin to provide a non-null result.  If no plugins respond, then
     * the default policy is applied.
     *
     * @param url       The URL that is being requested.
     * @return          Returns true to allow the resource to load,
     * false to block the resource.
     */
    fun shouldAllowRequest(url: String): Boolean {
        for (entry in this.entryMap.values()) {
            val plugin = pluginMap.get(entry.service)
            if (plugin != null) {
                val result = plugin!!.shouldAllowRequest(url)
                if (result != null) {
                    return result!!
                }
            }
        }

        // Default policy:
        if (url.startsWith("blob:") || url.startsWith("data:") || url.startsWith("about:blank")) {
            return true
        }
        // TalkBack requires this, so allow it by default.
        if (url.startsWith("https://ssl.gstatic.com/accessibility/javascript/android/")) {
            return true
        }
        return if (url.startsWith("file://")) {
            //This directory on WebKit/Blink based webviews contains SQLite databases!
            //DON'T CHANGE THIS UNLESS YOU KNOW WHAT YOU'RE DOING!
            !url.contains("/app_webview/")
        } else false
    }

    /**
     * Called when the webview is going to change the URL of the loaded content.
     *
     * This delegates to the installed plugins, and returns true/false for the
     * first plugin to provide a non-null result.  If no plugins respond, then
     * the default policy is applied.
     *
     * @param url       The URL that is being requested.
     * @return          Returns true to allow the navigation,
     * false to block the navigation.
     */
    fun shouldAllowNavigation(url: String): Boolean {
        for (entry in this.entryMap.values()) {
            val plugin = pluginMap.get(entry.service)
            if (plugin != null) {
                val result = plugin!!.shouldAllowNavigation(url)
                if (result != null) {
                    return result!!
                }
            }
        }

        // Default policy:
        return url.startsWith("file://") || url.startsWith("about:blank")
    }


    /**
     * Called when the webview is requesting the exec() bridge be enabled.
     */
    fun shouldAllowBridgeAccess(url: String): Boolean {
        for (entry in this.entryMap.values()) {
            val plugin = pluginMap.get(entry.service)
            if (plugin != null) {
                val result = plugin!!.shouldAllowBridgeAccess(url)
                if (result != null) {
                    return result!!
                }
            }
        }

        // Default policy:
        return url.startsWith("file://")
    }

    /**
     * Called when the webview is going not going to navigate, but may launch
     * an Intent for an URL.
     *
     * This delegates to the installed plugins, and returns true/false for the
     * first plugin to provide a non-null result.  If no plugins respond, then
     * the default policy is applied.
     *
     * @param url       The URL that is being requested.
     * @return          Returns true to allow the URL to launch an intent,
     * false to block the intent.
     */
    fun shouldOpenExternalUrl(url: String): Boolean {
        for (entry in this.entryMap.values()) {
            val plugin = pluginMap.get(entry.service)
            if (plugin != null) {
                val result = plugin!!.shouldOpenExternalUrl(url)
                if (result != null) {
                    return result
                }
            }
        }
        // Default policy:
        // External URLs are not allowed
        return false
    }

    /**
     * Called when the URL of the webview changes.
     *
     * @param url               The URL that is being changed to.
     * @return                  Return false to allow the URL to load, return true to prevent the URL from loading.
     */
    fun onOverrideUrlLoading(url: String): Boolean {
        for (entry in this.entryMap.values()) {
            val plugin = pluginMap.get(entry.service)
            if (plugin != null && plugin!!.onOverrideUrlLoading(url)) {
                return true
            }
        }
        return false
    }

    /**
     * Called when the app navigates or refreshes.
     */
    fun onReset() {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                plugin!!.onReset()
            }
        }
    }

    internal fun remapUri(uri: Uri): Uri? {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                val ret = plugin!!.remapUri(uri)
                if (ret != null) {
                    return ret
                }
            }
        }
        return null
    }

    /**
     * Create a plugin based on class name.
     */
    private fun instantiatePlugin(className: String?): CordovaPlugin? {
        var ret: CordovaPlugin? = null
        try {
            var c: Class<*>? = null
            if (className != null && !"".equals(className)) {
                c = Class.forName(className)
            }
            if ((c != null) and CordovaPlugin::class.java!!.isAssignableFrom(c)) {
                ret = c!!.newInstance() as CordovaPlugin
            }
        } catch (e: Exception) {
            e.printStackTrace()
            System.out.println("Error adding plugin $className.")
        }

        return ret
    }

    /**
     * Called by the system when the device configuration changes while your activity is running.
     *
     * @param newConfig        The new device configuration
     */
    fun onConfigurationChanged(newConfig: Configuration) {
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                plugin!!.onConfigurationChanged(newConfig)
            }
        }
    }

    fun onSaveInstanceState(): Bundle {
        val state = Bundle()
        for (plugin in this.pluginMap.values()) {
            if (plugin != null) {
                val pluginState = plugin!!.onSaveInstanceState()
                if (pluginState != null) {
                    state.putBundle(plugin!!.getServiceName(), pluginState)
                }
            }
        }
        return state
    }

    companion object {
        private val TAG = "PluginManager"
        private val SLOW_EXEC_WARNING_THRESHOLD = if (Debug.isDebuggerConnected()) 60 else 16
    }
}
