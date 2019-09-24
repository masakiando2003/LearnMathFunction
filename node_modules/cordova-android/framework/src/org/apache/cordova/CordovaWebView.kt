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

import android.content.Context
import android.content.Intent
import android.view.View
import android.webkit.WebChromeClient.CustomViewCallback

/**
 * Main interface for interacting with a Cordova webview - implemented by CordovaWebViewImpl.
 * This is an interface so that it can be easily mocked in tests.
 * Methods may be added to this interface without a major version bump, as plugins & embedders
 * are not expected to implement it.
 */
interface CordovaWebView {

    val isInitialized: Boolean

    val view: View

    /**
     * Deprecated in 4.0.0. Use your own View-toggling logic.
     */
    @get:Deprecated
    val isCustomViewShowing: Boolean

    val resourceApi: CordovaResourceApi

    val pluginManager: PluginManager
    val engine: CordovaWebViewEngine
    val preferences: CordovaPreferences
    val cookieManager: ICordovaCookieManager

    val url: String

    // TODO: Work on deleting these by removing refs from plugins.
    val context: Context

    fun init(cordova: CordovaInterface, pluginEntries: List<PluginEntry>, preferences: CordovaPreferences)

    fun loadUrlIntoView(url: String, recreatePlugins: Boolean)

    fun stopLoading()

    fun canGoBack(): Boolean

    fun clearCache()

    /** Use parameter-less overload  */
    @Deprecated
    fun clearCache(b: Boolean)

    fun clearHistory()

    fun backHistory(): Boolean

    fun handlePause(keepRunning: Boolean)

    fun onNewIntent(intent: Intent)

    fun handleResume(keepRunning: Boolean)

    fun handleStart()

    fun handleStop()

    fun handleDestroy()

    /**
     * Send JavaScript statement back to JavaScript.
     *
     * Deprecated (https://issues.apache.org/jira/browse/CB-6851)
     * Instead of executing snippets of JS, you should use the exec bridge
     * to create a Java->JS communication channel.
     * To do this:
     * 1. Within plugin.xml (to have your JS run before deviceready):
     * <js-module><runs></runs></js-module>
     * 2. Within your .js (call exec on start-up):
     * require('cordova/channel').onCordovaReady.subscribe(function() {
     * require('cordova/exec')(win, null, 'Plugin', 'method', []);
     * function win(message) {
     * ... process message from java here ...
     * }
     * });
     * 3. Within your .java:
     * PluginResult dataResult = new PluginResult(PluginResult.Status.OK, CODE);
     * dataResult.setKeepCallback(true);
     * savedCallbackContext.sendPluginResult(dataResult);
     */
    @Deprecated
    fun sendJavascript(statememt: String)

    /**
     * Load the specified URL in the Cordova webview or a new browser instance.
     *
     * NOTE: If openExternal is false, only whitelisted URLs can be loaded.
     *
     * @param url           The url to load.
     * @param openExternal  Load url in browser instead of Cordova webview.
     * @param clearHistory  Clear the history stack, so new page becomes top of history
     * @param params        Parameters for new app
     */
    fun showWebPage(url: String, openExternal: Boolean, clearHistory: Boolean, params: Map<String, Object>)

    /**
     * Deprecated in 4.0.0. Use your own View-toggling logic.
     */
    @Deprecated
    fun showCustomView(view: View, callback: CustomViewCallback)

    /**
     * Deprecated in 4.0.0. Use your own View-toggling logic.
     */
    @Deprecated
    fun hideCustomView()

    fun setButtonPlumbedToJs(keyCode: Int, override: Boolean)
    fun isButtonPlumbedToJs(keyCode: Int): Boolean

    fun sendPluginResult(cr: PluginResult, callbackId: String)
    fun loadUrl(url: String)
    fun postMessage(id: String, data: Object): Object

    companion object {
        val CORDOVA_VERSION = "8.0.0"
    }
}
