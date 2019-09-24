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

import android.annotation.TargetApi
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

import org.apache.cordova.AuthenticationToken
import org.apache.cordova.CordovaClientCertRequest
import org.apache.cordova.CordovaHttpAuthHandler
import org.apache.cordova.CordovaResourceApi
import org.apache.cordova.LOG
import org.apache.cordova.PluginManager

import java.io.FileNotFoundException
import java.io.IOException
import java.util.Hashtable


/**
 * This class is the WebViewClient that implements callbacks for our web view.
 * The kind of callbacks that happen here are regarding the rendering of the
 * document instead of the chrome surrounding it, such as onPageStarted(),
 * shouldOverrideUrlLoading(), etc. Related to but different than
 * CordovaChromeClient.
 */
class SystemWebViewClient(protected val parentEngine: SystemWebViewEngine) : WebViewClient() {
    private var doClearHistory = false
    internal var isCurrentlyLoading: Boolean = false

    /** The authorization tokens.  */
    private val authenticationTokens = Hashtable<String, AuthenticationToken>()

    /**
     * Give the host application a chance to take over the control when a new url
     * is about to be loaded in the current WebView.
     *
     * @param view          The WebView that is initiating the callback.
     * @param url           The url to be loaded.
     * @return              true to override, false for default behavior
     */
    @Override
    @SuppressWarnings("deprecation")
    fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return parentEngine.client.onNavigationAttempt(url)
    }

    /**
     * On received http auth request.
     * The method reacts on all registered authentication tokens. There is one and only one authentication token for any host + realm combination
     */
    @Override
    fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {

        // Get the authentication token (if specified)
        val token = this.getAuthenticationToken(host, realm)
        if (token != null) {
            handler.proceed(token!!.getUserName(), token!!.getPassword())
            return
        }

        // Check if there is some plugin which can resolve this auth challenge
        val pluginManager = this.parentEngine.pluginManager
        if (pluginManager != null && pluginManager!!.onReceivedHttpAuthRequest(null, CordovaHttpAuthHandler(handler), host, realm)) {
            parentEngine.client.clearLoadTimeoutTimer()
            return
        }

        // By default handle 401 like we'd normally do!
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    /**
     * On received client cert request.
     * The method forwards the request to any running plugins before using the default implementation.
     *
     * @param view
     * @param request
     */
    @Override
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {

        // Check if there is some plugin which can resolve this certificate request
        val pluginManager = this.parentEngine.pluginManager
        if (pluginManager != null && pluginManager!!.onReceivedClientCertRequest(null, CordovaClientCertRequest(request))) {
            parentEngine.client.clearLoadTimeoutTimer()
            return
        }

        // By default pass to WebViewClient
        super.onReceivedClientCertRequest(view, request)
    }

    /**
     * Notify the host application that a page has started loading.
     * This method is called once for each main frame load so a page with iframes or framesets will call onPageStarted
     * one time for the main frame. This also means that onPageStarted will not be called when the contents of an
     * embedded frame changes, i.e. clicking a link whose target is an iframe.
     *
     * @param view          The webview initiating the callback.
     * @param url           The url of the page.
     */
    @Override
    fun onPageStarted(view: WebView, url: String, favicon: Bitmap) {
        super.onPageStarted(view, url, favicon)
        isCurrentlyLoading = true
        // Flush stale messages & reset plugins.
        parentEngine.bridge.reset()
        parentEngine.client.onPageStarted(url)
    }

    /**
     * Notify the host application that a page has finished loading.
     * This method is called only for main frame. When onPageFinished() is called, the rendering picture may not be updated yet.
     *
     *
     * @param view          The webview initiating the callback.
     * @param url           The url of the page.
     */
    @Override
    fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        // Ignore excessive calls, if url is not about:blank (CB-8317).
        if (!isCurrentlyLoading && !url.startsWith("about:")) {
            return
        }
        isCurrentlyLoading = false

        /**
         * Because of a timing issue we need to clear this history in onPageFinished as well as
         * onPageStarted. However we only want to do this if the doClearHistory boolean is set to
         * true. You see when you load a url with a # in it which is common in jQuery applications
         * onPageStared is not called. Clearing the history at that point would break jQuery apps.
         */
        if (this.doClearHistory) {
            view.clearHistory()
            this.doClearHistory = false
        }
        parentEngine.client.onPageFinishedLoading(url)

    }

    /**
     * Report an error to the host application. These errors are unrecoverable (i.e. the main resource is unavailable).
     * The errorCode parameter corresponds to one of the ERROR_* constants.
     *
     * @param view          The WebView that is initiating the callback.
     * @param errorCode     The error code corresponding to an ERROR_* value.
     * @param description   A String describing the error.
     * @param failingUrl    The url that failed to load.
     */
    @Override
    @SuppressWarnings("deprecation")
    fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
        // Ignore error due to stopLoading().
        if (!isCurrentlyLoading) {
            return
        }
        LOG.d(TAG, "CordovaWebViewClient.onReceivedError: Error code=%s Description=%s URL=%s", errorCode, description, failingUrl)

        // If this is a "Protocol Not Supported" error, then revert to the previous
        // page. If there was no previous page, then punt. The application's config
        // is likely incorrect (start page set to sms: or something like that)
        if (errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME) {
            parentEngine.client.clearLoadTimeoutTimer()

            if (view.canGoBack()) {
                view.goBack()
                return
            } else {
                super.onReceivedError(view, errorCode, description, failingUrl)
            }
        }
        parentEngine.client.onReceivedError(errorCode, description, failingUrl)
    }

    /**
     * Notify the host application that an SSL error occurred while loading a resource.
     * The host application must call either handler.cancel() or handler.proceed().
     * Note that the decision may be retained for use in response to future SSL errors.
     * The default behavior is to cancel the load.
     *
     * @param view          The WebView that is initiating the callback.
     * @param handler       An SslErrorHandler object that will handle the user's response.
     * @param error         The SSL error object.
     */
    @Override
    fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {

        val packageName = parentEngine.cordova.getActivity().getPackageName()
        val pm = parentEngine.cordova.getActivity().getPackageManager()

        val appInfo: ApplicationInfo
        try {
            appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            if (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE !== 0) {
                // debug = true
                handler.proceed()
                return
            } else {
                // debug = false
                super.onReceivedSslError(view, handler, error)
            }
        } catch (e: NameNotFoundException) {
            // When it doubt, lock it out!
            super.onReceivedSslError(view, handler, error)
        }

    }


    /**
     * Sets the authentication token.
     *
     * @param authenticationToken
     * @param host
     * @param realm
     */
    fun setAuthenticationToken(authenticationToken: AuthenticationToken, host: String?, realm: String?) {
        var host = host
        var realm = realm
        if (host == null) {
            host = ""
        }
        if (realm == null) {
            realm = ""
        }
        this.authenticationTokens.put(host.concat(realm), authenticationToken)
    }

    /**
     * Removes the authentication token.
     *
     * @param host
     * @param realm
     *
     * @return the authentication token or null if did not exist
     */
    fun removeAuthenticationToken(host: String, realm: String): AuthenticationToken {
        return this.authenticationTokens.remove(host.concat(realm))
    }

    /**
     * Gets the authentication token.
     *
     * In order it tries:
     * 1- host + realm
     * 2- host
     * 3- realm
     * 4- no host, no realm
     *
     * @param host
     * @param realm
     *
     * @return the authentication token
     */
    fun getAuthenticationToken(host: String, realm: String): AuthenticationToken? {
        var token: AuthenticationToken? = null
        token = this.authenticationTokens.get(host.concat(realm))

        if (token == null) {
            // try with just the host
            token = this.authenticationTokens.get(host)

            // Try the realm
            if (token == null) {
                token = this.authenticationTokens.get(realm)
            }

            // if no host found, just query for default
            if (token == null) {
                token = this.authenticationTokens.get("")
            }
        }

        return token
    }

    /**
     * Clear all authentication tokens.
     */
    fun clearAuthenticationTokens() {
        this.authenticationTokens.clear()
    }

    @Override
    @SuppressWarnings("deprecation")
    fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
        try {
            // Check the against the whitelist and lock out access to the WebView directory
            // Changing this will cause problems for your application
            if (!parentEngine.pluginManager.shouldAllowRequest(url)) {
                LOG.w(TAG, "URL blocked by whitelist: $url")
                // Results in a 404.
                return WebResourceResponse("text/plain", "UTF-8", null)
            }

            val resourceApi = parentEngine.resourceApi
            val origUri = Uri.parse(url)
            // Allow plugins to intercept WebView requests.
            val remappedUri = resourceApi.remapUri(origUri)

            if (!origUri.equals(remappedUri) || needsSpecialsInAssetUrlFix(origUri) || needsKitKatContentUrlFix(origUri)) {
                val result = resourceApi.openForRead(remappedUri, true)
                return WebResourceResponse(result.mimeType, "UTF-8", result.inputStream)
            }
            // If we don't need to special-case the request, let the browser load it.
            return null
        } catch (e: IOException) {
            if (e !is FileNotFoundException) {
                LOG.e(TAG, "Error occurred while loading a file (returning a 404).", e)
            }
            // Results in a 404.
            return WebResourceResponse("text/plain", "UTF-8", null)
        }

    }

    companion object {

        private val TAG = "SystemWebViewClient"

        private fun needsKitKatContentUrlFix(uri: Uri): Boolean {
            return "content".equals(uri.getScheme())
        }

        private fun needsSpecialsInAssetUrlFix(uri: Uri): Boolean {
            if (CordovaResourceApi.getUriType(uri) !== CordovaResourceApi.URI_TYPE_ASSET) {
                return false
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                return true
            }

            return if (!uri.toString().contains("%")) {
                false
            } else false

        }
    }
}
