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
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout

import org.apache.cordova.engine.SystemWebViewEngine
import org.json.JSONException
import org.json.JSONObject

import java.lang.reflect.Constructor
import java.util.ArrayList
import java.util.HashSet

/**
 * Main class for interacting with a Cordova webview. Manages plugins, events, and a CordovaWebViewEngine.
 * Class uses two-phase initialization. You must call init() before calling any other methods.
 */
class CordovaWebViewImpl(@get:Override
                         val engine: CordovaWebViewEngine) : CordovaWebView {

    @get:Override
    var pluginManager: PluginManager? = null
        private set
    private var cordova: CordovaInterface? = null

    // Flag to track that a loadUrl timeout occurred
    private var loadUrlTimeout = 0

    @get:Override
    var resourceApi: CordovaResourceApi? = null
        private set
    @get:Override
    var preferences: CordovaPreferences? = null
        private set
    private var appPlugin: CoreAndroid? = null
    private var nativeToJsMessageQueue: NativeToJsMessageQueue? = null
    private val engineClient = EngineClient()
    private var hasPausedEver: Boolean = false

    // The URL passed to loadUrl(), not necessarily the URL of the current page.
    internal var loadedUrl: String? = null

    /** custom view created by the browser (a video player for example)  */
    private var mCustomView: View? = null
    private var mCustomViewCallback: WebChromeClient.CustomViewCallback? = null

    private val boundKeyCodes = HashSet<Integer>()

    val isInitialized: Boolean
        @Override
        get() = cordova != null

    val isCustomViewShowing: Boolean
        @Override
        @Deprecated
        get() = mCustomView != null
    val cookieManager: ICordovaCookieManager
        @Override
        get() = engine.getCookieManager()
    val view: View
        @Override
        get() = engine.getView()
    val context: Context
        @Override
        get() = engine.getView().getContext()

    // Engine method proxies:
    val url: String
        @Override
        get() = engine.getUrl()

    // Convenience method for when creating programmatically (not from Config.xml).
    fun init(cordova: CordovaInterface) {
        init(cordova, ArrayList<PluginEntry>(), CordovaPreferences())
    }

    @SuppressLint("Assert")
    @Override
    fun init(cordova: CordovaInterface, pluginEntries: List<PluginEntry>, preferences: CordovaPreferences) {
        if (this.cordova != null) {
            throw IllegalStateException()
        }
        this.cordova = cordova
        this.preferences = preferences
        pluginManager = PluginManager(this, this.cordova, pluginEntries)
        resourceApi = CordovaResourceApi(engine.getView().getContext(), pluginManager)
        nativeToJsMessageQueue = NativeToJsMessageQueue()
        nativeToJsMessageQueue!!.addBridgeMode(NativeToJsMessageQueue.NoOpBridgeMode())
        nativeToJsMessageQueue!!.addBridgeMode(NativeToJsMessageQueue.LoadUrlBridgeMode(engine, cordova))

        if (preferences.getBoolean("DisallowOverscroll", false)) {
            engine.getView().setOverScrollMode(View.OVER_SCROLL_NEVER)
        }
        engine.init(this, cordova, engineClient, resourceApi, pluginManager, nativeToJsMessageQueue)
        // This isn't enforced by the compiler, so assert here.
        assert(engine.getView() is CordovaWebViewEngine.EngineView)

        pluginManager!!.addService(CoreAndroid.PLUGIN_NAME, "org.apache.cordova.CoreAndroid")
        pluginManager!!.init()

    }

    @Override
    fun loadUrlIntoView(url: String, recreatePlugins: Boolean) {
        var recreatePlugins = recreatePlugins
        LOG.d(TAG, ">>> loadUrl($url)")
        if (url.equals("about:blank") || url.startsWith("javascript:")) {
            engine.loadUrl(url, false)
            return
        }

        recreatePlugins = recreatePlugins || loadedUrl == null

        if (recreatePlugins) {
            // Don't re-initialize on first load.
            if (loadedUrl != null) {
                appPlugin = null
                pluginManager!!.init()
            }
            loadedUrl = url
        }

        // Create a timeout timer for loadUrl
        val currentLoadUrlTimeout = loadUrlTimeout
        val loadUrlTimeoutValue = preferences!!.getInteger("LoadUrlTimeoutValue", 20000)

        // Timeout error method
        val loadError = object : Runnable() {
            fun run() {
                stopLoading()
                LOG.e(TAG, "CordovaWebView: TIMEOUT ERROR!")

                // Handle other errors by passing them to the webview in JS
                val data = JSONObject()
                try {
                    data.put("errorCode", -6)
                    data.put("description", "The connection to the server was unsuccessful.")
                    data.put("url", url)
                } catch (e: JSONException) {
                    // Will never happen.
                }

                pluginManager!!.postMessage("onReceivedError", data)
            }
        }

        // Timeout timer method
        val timeoutCheck = object : Runnable() {
            fun run() {
                try {
                    synchronized(this) {
                        wait(loadUrlTimeoutValue)
                    }
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }

                // If timeout, then stop loading and handle error
                if (loadUrlTimeout == currentLoadUrlTimeout) {
                    cordova!!.getActivity().runOnUiThread(loadError)
                }
            }
        }

        val _recreatePlugins = recreatePlugins
        cordova!!.getActivity().runOnUiThread(object : Runnable() {
            fun run() {
                if (loadUrlTimeoutValue > 0) {
                    cordova!!.getThreadPool().execute(timeoutCheck)
                }
                engine.loadUrl(url, _recreatePlugins)
            }
        })
    }


    @Override
    fun loadUrl(url: String) {
        loadUrlIntoView(url, true)
    }

    @Override
    fun showWebPage(url: String, openExternal: Boolean, clearHistory: Boolean, params: Map<String, Object>?) {
        LOG.d(TAG, "showWebPage(%s, %b, %b, HashMap)", url, openExternal, clearHistory)

        // If clearing history
        if (clearHistory) {
            engine.clearHistory()
        }

        // If loading into our webview
        if (!openExternal) {
            // Make sure url is in whitelist
            if (pluginManager!!.shouldAllowNavigation(url)) {
                // TODO: What about params?
                // Load new URL
                loadUrlIntoView(url, true)
                return
            } else {
                LOG.w(TAG, "showWebPage: Refusing to load URL into webview since it is not in the <allow-navigation> whitelist. URL=$url")
                return
            }
        }
        if (!pluginManager!!.shouldOpenExternalUrl(url)) {
            LOG.w(TAG, "showWebPage: Refusing to send intent for URL since it is not in the <allow-intent> whitelist. URL=$url")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            // To send an intent without CATEGORY_BROWSER, a custom plugin should be used.
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            val uri = Uri.parse(url)
            // Omitting the MIME type for file: URLs causes "No Activity found to handle Intent".
            // Adding the MIME type to http: URLs causes them to not be handled by the downloader.
            if ("file".equals(uri.getScheme())) {
                intent.setDataAndType(uri, resourceApi!!.getMimeType(uri))
            } else {
                intent.setData(uri)
            }
            cordova!!.getActivity().startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            LOG.e(TAG, "Error loading url $url", e)
        }

    }

    @Override
    @Deprecated
    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        // This code is adapted from the original Android Browser code, licensed under the Apache License, Version 2.0
        LOG.d(TAG, "showing Custom View")
        // if a view already exists then immediately terminate the new one
        if (mCustomView != null) {
            callback.onCustomViewHidden()
            return
        }

        // Store the view and its callback for later (to kill it properly)
        mCustomView = view
        mCustomViewCallback = callback

        // Add the custom view to its container.
        val parent = engine.getView().getParent() as ViewGroup
        parent.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER))

        // Hide the content view.
        engine.getView().setVisibility(View.GONE)

        // Finally show the custom view container.
        parent.setVisibility(View.VISIBLE)
        parent.bringToFront()
    }

    @Override
    @Deprecated
    fun hideCustomView() {
        // This code is adapted from the original Android Browser code, licensed under the Apache License, Version 2.0
        if (mCustomView == null) return
        LOG.d(TAG, "Hiding Custom View")

        // Hide the custom view.
        mCustomView!!.setVisibility(View.GONE)

        // Remove the custom view from its container.
        val parent = engine.getView().getParent() as ViewGroup
        parent.removeView(mCustomView)
        mCustomView = null
        mCustomViewCallback!!.onCustomViewHidden()

        // Show the content view.
        engine.getView().setVisibility(View.VISIBLE)
    }

    @Override
    @Deprecated
    fun sendJavascript(statement: String) {
        nativeToJsMessageQueue!!.addJavaScript(statement)
    }

    @Override
    fun sendPluginResult(cr: PluginResult, callbackId: String) {
        nativeToJsMessageQueue!!.addPluginResult(cr, callbackId)
    }

    private fun sendJavascriptEvent(event: String) {
        if (appPlugin == null) {
            appPlugin = pluginManager!!.getPlugin(CoreAndroid.PLUGIN_NAME) as CoreAndroid
        }

        if (appPlugin == null) {
            LOG.w(TAG, "Unable to fire event without existing plugin")
            return
        }
        appPlugin!!.fireJavascriptEvent(event)
    }

    @Override
    fun setButtonPlumbedToJs(keyCode: Int, override: Boolean) {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                // TODO: Why are search and menu buttons handled separately?
                if (override) {
                    boundKeyCodes.add(keyCode)
                } else {
                    boundKeyCodes.remove(keyCode)
                }
                return
            }
            else -> throw IllegalArgumentException("Unsupported keycode: $keyCode")
        }
    }

    @Override
    fun isButtonPlumbedToJs(keyCode: Int): Boolean {
        return boundKeyCodes.contains(keyCode)
    }

    @Override
    fun postMessage(id: String, data: Object): Object {
        return pluginManager!!.postMessage(id, data)
    }

    @Override
    fun stopLoading() {
        // Clear timeout flag
        loadUrlTimeout++
    }

    @Override
    fun canGoBack(): Boolean {
        return engine.canGoBack()
    }

    @Override
    fun clearCache() {
        engine.clearCache()
    }

    @Override
    @Deprecated
    fun clearCache(b: Boolean) {
        engine.clearCache()
    }

    @Override
    fun clearHistory() {
        engine.clearHistory()
    }

    @Override
    fun backHistory(): Boolean {
        return engine.goBack()
    }

    /////// LifeCycle methods ///////
    @Override
    fun onNewIntent(intent: Intent) {
        if (this.pluginManager != null) {
            this.pluginManager!!.onNewIntent(intent)
        }
    }

    @Override
    fun handlePause(keepRunning: Boolean) {
        if (!isInitialized) {
            return
        }
        hasPausedEver = true
        pluginManager!!.onPause(keepRunning)
        sendJavascriptEvent("pause")

        // If app doesn't want to run in background
        if (!keepRunning) {
            // Pause JavaScript timers. This affects all webviews within the app!
            engine.setPaused(true)
        }
    }

    @Override
    fun handleResume(keepRunning: Boolean) {
        if (!isInitialized) {
            return
        }

        // Resume JavaScript timers. This affects all webviews within the app!
        engine.setPaused(false)
        this.pluginManager!!.onResume(keepRunning)

        // In order to match the behavior of the other platforms, we only send onResume after an
        // onPause has occurred. The resume event might still be sent if the Activity was killed
        // while waiting for the result of an external Activity once the result is obtained
        if (hasPausedEver) {
            sendJavascriptEvent("resume")
        }
    }

    @Override
    fun handleStart() {
        if (!isInitialized) {
            return
        }
        pluginManager!!.onStart()
    }

    @Override
    fun handleStop() {
        if (!isInitialized) {
            return
        }
        pluginManager!!.onStop()
    }

    @Override
    fun handleDestroy() {
        if (!isInitialized) {
            return
        }
        // Cancel pending timeout timer.
        loadUrlTimeout++

        // Forward to plugins
        this.pluginManager!!.onDestroy()

        // TODO: about:blank is a bit special (and the default URL for new frames)
        // We should use a blank data: url instead so it's more obvious
        this.loadUrl("about:blank")

        // TODO: Should not destroy webview until after about:blank is done loading.
        engine.destroy()
        hideCustomView()
    }

    protected inner class EngineClient : CordovaWebViewEngine.Client {
        @Override
        fun clearLoadTimeoutTimer() {
            loadUrlTimeout++
        }

        @Override
        fun onPageStarted(newUrl: String) {
            LOG.d(TAG, "onPageDidNavigate($newUrl)")
            boundKeyCodes.clear()
            pluginManager!!.onReset()
            pluginManager!!.postMessage("onPageStarted", newUrl)
        }

        @Override
        fun onReceivedError(errorCode: Int, description: String, failingUrl: String) {
            clearLoadTimeoutTimer()
            val data = JSONObject()
            try {
                data.put("errorCode", errorCode)
                data.put("description", description)
                data.put("url", failingUrl)
            } catch (e: JSONException) {
                e.printStackTrace()
            }

            pluginManager!!.postMessage("onReceivedError", data)
        }

        @Override
        fun onPageFinishedLoading(url: String) {
            LOG.d(TAG, "onPageFinished($url)")

            clearLoadTimeoutTimer()

            // Broadcast message that page has loaded
            pluginManager!!.postMessage("onPageFinished", url)

            // Make app visible after 2 sec in case there was a JS error and Cordova JS never initialized correctly
            if (engine.getView().getVisibility() !== View.VISIBLE) {
                val t = Thread(object : Runnable() {
                    fun run() {
                        try {
                            Thread.sleep(2000)
                            cordova!!.getActivity().runOnUiThread(object : Runnable() {
                                fun run() {
                                    pluginManager!!.postMessage("spinner", "stop")
                                }
                            })
                        } catch (e: InterruptedException) {
                        }

                    }
                })
                t.start()
            }

            // Shutdown if blank loaded
            if (url.equals("about:blank")) {
                pluginManager!!.postMessage("exit", null)
            }
        }

        @Override
        fun onDispatchKeyEvent(event: KeyEvent): Boolean? {
            val keyCode = event.getKeyCode()
            val isBackButton = keyCode == KeyEvent.KEYCODE_BACK
            if (event.getAction() === KeyEvent.ACTION_DOWN) {
                if (isBackButton && mCustomView != null) {
                    return true
                } else if (boundKeyCodes.contains(keyCode)) {
                    return true
                } else if (isBackButton) {
                    return engine.canGoBack()
                }
            } else if (event.getAction() === KeyEvent.ACTION_UP) {
                if (isBackButton && mCustomView != null) {
                    hideCustomView()
                    return true
                } else if (boundKeyCodes.contains(keyCode)) {
                    var eventName: String? = null
                    when (keyCode) {
                        KeyEvent.KEYCODE_VOLUME_DOWN -> eventName = "volumedownbutton"
                        KeyEvent.KEYCODE_VOLUME_UP -> eventName = "volumeupbutton"
                        KeyEvent.KEYCODE_SEARCH -> eventName = "searchbutton"
                        KeyEvent.KEYCODE_MENU -> eventName = "menubutton"
                        KeyEvent.KEYCODE_BACK -> eventName = "backbutton"
                    }
                    if (eventName != null) {
                        sendJavascriptEvent(eventName)
                        return true
                    }
                } else if (isBackButton) {
                    return engine.goBack()
                }
            }
            return null
        }

        @Override
        fun onNavigationAttempt(url: String): Boolean {
            // Give plugins the chance to handle the url
            if (pluginManager!!.onOverrideUrlLoading(url)) {
                return true
            } else if (pluginManager!!.shouldAllowNavigation(url)) {
                return false
            } else if (pluginManager!!.shouldOpenExternalUrl(url)) {
                showWebPage(url, true, false, null)
                return true
            }
            LOG.w(TAG, "Blocked (possibly sub-frame) navigation to non-allowed URL: $url")
            return true
        }
    }

    companion object {

        val TAG = "CordovaWebViewImpl"

        fun createEngine(context: Context, preferences: CordovaPreferences): CordovaWebViewEngine {
            val className = preferences.getString("webview", SystemWebViewEngine::class.java!!.getCanonicalName())
            try {
                val webViewClass = Class.forName(className)
                val constructor = webViewClass.getConstructor(Context::class.java, CordovaPreferences::class.java)
                return constructor.newInstance(context, preferences) as CordovaWebViewEngine
            } catch (e: Exception) {
                throw RuntimeException("Failed to create webview. ", e)
            }

        }
    }
}
