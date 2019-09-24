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

package org.apache.cordova.engine

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebSettings.LayoutAlgorithm
import android.webkit.WebView

import org.apache.cordova.CordovaBridge
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPreferences
import org.apache.cordova.CordovaResourceApi
import org.apache.cordova.CordovaWebView
import org.apache.cordova.CordovaWebViewEngine
import org.apache.cordova.ICordovaCookieManager
import org.apache.cordova.LOG
import org.apache.cordova.NativeToJsMessageQueue
import org.apache.cordova.PluginManager

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method


/**
 * Glue class between CordovaWebView (main Cordova logic) and SystemWebView (the actual View).
 * We make the Engine separate from the actual View so that:
 * A) We don't need to worry about WebView methods clashing with CordovaWebViewEngine methods
 * (e.g.: goBack() is void for WebView, and boolean for CordovaWebViewEngine)
 * B) Separating the actual View from the Engine makes API surfaces smaller.
 * Class uses two-phase initialization. However, CordovaWebView is responsible for calling .init().
 */
class SystemWebViewEngine @JvmOverloads constructor(protected val webView: SystemWebView?, protected var preferences: CordovaPreferences? = null) : CordovaWebViewEngine {
    protected val cookieManager: SystemCookieManager
    protected var bridge: CordovaBridge
    protected var client: CordovaWebViewEngine.Client
    @get:Override
    var cordovaWebView: CordovaWebView
        protected set
    protected var cordova: CordovaInterface? = null
    protected var pluginManager: PluginManager
    protected var resourceApi: CordovaResourceApi
    protected var nativeToJsMessageQueue: NativeToJsMessageQueue
    private var receiver: BroadcastReceiver? = null

    val view: View?
        @Override
        get() = webView

    val url: String
        @Override
        get() = webView!!.getUrl()

    /** Used when created via reflection.  */
    constructor(context: Context, preferences: CordovaPreferences?) : this(SystemWebView(context), preferences) {}

    init {
        cookieManager = SystemCookieManager(webView)
    }

    @Override
    fun init(parentWebView: CordovaWebView, cordova: CordovaInterface, client: CordovaWebViewEngine.Client,
             resourceApi: CordovaResourceApi, pluginManager: PluginManager,
             nativeToJsMessageQueue: NativeToJsMessageQueue) {
        if (this.cordova != null) {
            throw IllegalStateException()
        }
        // Needed when prefs are not passed by the constructor
        if (preferences == null) {
            preferences = parentWebView.getPreferences()
        }
        this.cordovaWebView = parentWebView
        this.cordova = cordova
        this.client = client
        this.resourceApi = resourceApi
        this.pluginManager = pluginManager
        this.nativeToJsMessageQueue = nativeToJsMessageQueue
        webView!!.init(this, cordova)

        initWebViewSettings()

        nativeToJsMessageQueue.addBridgeMode(NativeToJsMessageQueue.OnlineEventsBridgeMode(object : NativeToJsMessageQueue.OnlineEventsBridgeMode.OnlineEventsBridgeModeDelegate() {
            @Override
            fun setNetworkAvailable(value: Boolean) {
                //sometimes this can be called after calling webview.destroy() on destroy()
                //thus resulting in a NullPointerException
                if (webView != null) {
                    webView!!.setNetworkAvailable(value)
                }
            }

            @Override
            fun runOnUiThread(r: Runnable) {
                this@SystemWebViewEngine.cordova!!.getActivity().runOnUiThread(r)
            }
        }))
        nativeToJsMessageQueue.addBridgeMode(NativeToJsMessageQueue.EvalBridgeMode(this, cordova))
        bridge = CordovaBridge(pluginManager, nativeToJsMessageQueue)
        exposeJsInterface(webView!!, bridge)
    }

    @Override
    fun getCookieManager(): ICordovaCookieManager {
        return cookieManager
    }

    @SuppressLint("NewApi", "SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private fun initWebViewSettings() {
        webView!!.setInitialScale(0)
        webView!!.setVerticalScrollBarEnabled(false)
        // Enable JavaScript
        val settings = webView!!.getSettings()
        settings.setJavaScriptEnabled(true)
        settings.setJavaScriptCanOpenWindowsAutomatically(true)
        settings.setLayoutAlgorithm(LayoutAlgorithm.NORMAL)

        val manufacturer = android.os.Build.MANUFACTURER
        LOG.d(TAG, "CordovaWebView is running on device made by: $manufacturer")

        //We don't save any form data in the application
        settings.setSaveFormData(false)
        settings.setSavePassword(false)

        // Jellybean rightfully tried to lock this down. Too bad they didn't give us a whitelist
        // while we do this
        settings.setAllowUniversalAccessFromFileURLs(true)
        settings.setMediaPlaybackRequiresUserGesture(false)

        // Enable database
        // We keep this disabled because we use or shim to get around DOM_EXCEPTION_ERROR_16
        val databasePath = webView!!.getContext().getApplicationContext().getDir("database", Context.MODE_PRIVATE).getPath()
        settings.setDatabaseEnabled(true)
        settings.setDatabasePath(databasePath)


        //Determine whether we're in debug or release mode, and turn on Debugging!
        val appInfo = webView!!.getContext().getApplicationContext().getApplicationInfo()
        if (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE !== 0) {
            enableRemoteDebugging()
        }

        settings.setGeolocationDatabasePath(databasePath)

        // Enable DOM storage
        settings.setDomStorageEnabled(true)

        // Enable built-in geolocation
        settings.setGeolocationEnabled(true)

        // Enable AppCache
        // Fix for CB-2282
        settings.setAppCacheMaxSize(5 * 1048576)
        settings.setAppCachePath(databasePath)
        settings.setAppCacheEnabled(true)

        // Fix for CB-1405
        // Google issue 4641
        val defaultUserAgent = settings.getUserAgentString()

        // Fix for CB-3360
        val overrideUserAgent = preferences!!.getString("OverrideUserAgent", null)
        if (overrideUserAgent != null) {
            settings.setUserAgentString(overrideUserAgent)
        } else {
            val appendUserAgent = preferences!!.getString("AppendUserAgent", null)
            if (appendUserAgent != null) {
                settings.setUserAgentString(defaultUserAgent + " " + appendUserAgent)
            }
        }
        // End CB-3360

        val intentFilter = IntentFilter()
        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        if (this.receiver == null) {
            this.receiver = object : BroadcastReceiver() {
                @Override
                fun onReceive(context: Context, intent: Intent) {
                    settings.getUserAgentString()
                }
            }
            webView!!.getContext().registerReceiver(this.receiver, intentFilter)
        }
        // end CB-1405
    }

    private fun enableRemoteDebugging() {
        try {
            WebView.setWebContentsDebuggingEnabled(true)
        } catch (e: IllegalArgumentException) {
            LOG.d(TAG, "You have one job! To turn on Remote Web Debugging! YOU HAVE FAILED! ")
            e.printStackTrace()
        }

    }


    /**
     * Load the url into the webview.
     */
    @Override
    fun loadUrl(url: String, clearNavigationStack: Boolean) {
        webView!!.loadUrl(url)
    }

    @Override
    fun stopLoading() {
        webView!!.stopLoading()
    }

    @Override
    fun clearCache() {
        webView!!.clearCache(true)
    }

    @Override
    fun clearHistory() {
        webView!!.clearHistory()
    }

    @Override
    fun canGoBack(): Boolean {
        return webView!!.canGoBack()
    }

    /**
     * Go to previous page in history.  (We manage our own history)
     *
     * @return true if we went back, false if we are already at top
     */
    @Override
    fun goBack(): Boolean {
        // Check webview first to see if there is a history
        // This is needed to support curPage#diffLink, since they are added to parentEngine's history, but not our history url array (JQMobile behavior)
        if (webView!!.canGoBack()) {
            webView!!.goBack()
            return true
        }
        return false
    }

    @Override
    fun setPaused(value: Boolean) {
        if (value) {
            webView!!.onPause()
            webView!!.pauseTimers()
        } else {
            webView!!.onResume()
            webView!!.resumeTimers()
        }
    }

    @Override
    fun destroy() {
        webView!!.chromeClient.destroyLastDialog()
        webView!!.destroy()
        // unregister the receiver
        if (receiver != null) {
            try {
                webView!!.getContext().unregisterReceiver(receiver)
            } catch (e: Exception) {
                LOG.e(TAG, "Error unregistering configuration receiver: " + e.getMessage(), e)
            }

        }
    }

    @Override
    fun evaluateJavascript(js: String, callback: ValueCallback<String>) {
        webView!!.evaluateJavascript(js, callback)
    }

    companion object {
        val TAG = "SystemWebViewEngine"

        // Yeah, we know. It'd be great if lint was just a little smarter.
        @SuppressLint("AddJavascriptInterface")
        private fun exposeJsInterface(webView: WebView, bridge: CordovaBridge) {
            val exposedJsApi = SystemExposedJsApi(bridge)
            webView.addJavascriptInterface(exposedJsApi, "_cordovaNative")
        }
    }
}
