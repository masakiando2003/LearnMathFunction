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

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaWebView
import org.apache.cordova.CordovaWebViewEngine

/**
 * Custom WebView subclass that enables us to capture events needed for Cordova.
 */
class SystemWebView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs), CordovaWebViewEngine.EngineView {
    private var viewClient: SystemWebViewClient? = null
    internal var chromeClient: SystemWebChromeClient? = null
    private var parentEngine: SystemWebViewEngine? = null
    private var cordova: CordovaInterface? = null

    val cordovaWebView: CordovaWebView?
        @Override
        get() = if (parentEngine != null) parentEngine!!.getCordovaWebView() else null

    // Package visibility to enforce that only SystemWebViewEngine should call this method.
    internal fun init(parentEngine: SystemWebViewEngine, cordova: CordovaInterface) {
        this.cordova = cordova
        this.parentEngine = parentEngine
        if (this.viewClient == null) {
            setWebViewClient(SystemWebViewClient(parentEngine))
        }

        if (this.chromeClient == null) {
            setWebChromeClient(SystemWebChromeClient(parentEngine))
        }
    }

    @Override
    fun setWebViewClient(client: WebViewClient) {
        viewClient = client as SystemWebViewClient
        super.setWebViewClient(client)
    }

    @Override
    fun setWebChromeClient(client: WebChromeClient) {
        chromeClient = client as SystemWebChromeClient
        super.setWebChromeClient(client)
    }

    @Override
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ret = parentEngine!!.client.onDispatchKeyEvent(event)
        return if (ret != null) {
            ret!!.booleanValue()
        } else super.dispatchKeyEvent(event)
    }
}
