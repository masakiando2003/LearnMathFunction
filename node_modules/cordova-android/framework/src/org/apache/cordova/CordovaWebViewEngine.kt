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

import android.view.KeyEvent
import android.view.View
import android.webkit.ValueCallback

/**
 * Interface for all Cordova engines.
 * No methods will be added to this class (in order to be compatible with existing engines).
 * Instead, we will create a new interface: e.g. CordovaWebViewEngineV2
 */
interface CordovaWebViewEngine {

    val cordovaWebView: CordovaWebView
    val cookieManager: ICordovaCookieManager
    val view: View

    /** Return the currently loaded URL  */
    val url: String

    fun init(parentWebView: CordovaWebView, cordova: CordovaInterface, client: Client,
             resourceApi: CordovaResourceApi, pluginManager: PluginManager,
             nativeToJsMessageQueue: NativeToJsMessageQueue)

    fun loadUrl(url: String, clearNavigationStack: Boolean)

    fun stopLoading()

    fun clearCache()

    /** After calling clearHistory(), canGoBack() should be false.  */
    fun clearHistory()

    fun canGoBack(): Boolean

    /** Returns whether a navigation occurred  */
    fun goBack(): Boolean

    /** Pauses / resumes the WebView's event loop.  */
    fun setPaused(value: Boolean)

    /** Clean up all resources associated with the WebView.  */
    fun destroy()

    /** Add the evaulate Javascript method  */
    fun evaluateJavascript(js: String, callback: ValueCallback<String>)

    /**
     * Used to retrieve the associated CordovaWebView given a View without knowing the type of Engine.
     * E.g. ((CordovaWebView.EngineView)activity.findViewById(android.R.id.webView)).getCordovaWebView();
     */
    interface EngineView {
        val cordovaWebView: CordovaWebView
    }

    /**
     * Contains methods that an engine uses to communicate with the parent CordovaWebView.
     * Methods may be added in future cordova versions, but never removed.
     */
    interface Client {
        fun onDispatchKeyEvent(event: KeyEvent): Boolean
        fun clearLoadTimeoutTimer()
        fun onPageStarted(newUrl: String)
        fun onReceivedError(errorCode: Int, description: String, failingUrl: String)
        fun onPageFinishedLoading(url: String)
        fun onNavigationAttempt(url: String): Boolean
    }
}
