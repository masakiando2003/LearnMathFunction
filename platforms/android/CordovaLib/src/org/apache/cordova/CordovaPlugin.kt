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

import org.apache.cordova.CordovaArgs
import org.apache.cordova.CordovaWebView
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CallbackContext
import org.json.JSONArray
import org.json.JSONException

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle

import java.io.FileNotFoundException
import java.io.IOException

/**
 * Plugins must extend this class and override one of the execute methods.
 */
class CordovaPlugin {
    var webView: CordovaWebView
    var cordova: CordovaInterface? = null
    protected var preferences: CordovaPreferences
    /**
     * Returns the plugin's service name (what you'd use when calling pluginManger.getPlugin())
     */
    var serviceName: String? = null
        private set

    /**
     * Call this after constructing to initialize the plugin.
     * Final because we want to be able to change args without breaking plugins.
     */
    fun privateInitialize(serviceName: String, cordova: CordovaInterface, webView: CordovaWebView, preferences: CordovaPreferences) {
        assert(this.cordova == null)
        this.serviceName = serviceName
        this.cordova = cordova
        this.webView = webView
        this.preferences = preferences
        initialize(cordova, webView)
        pluginInitialize()
    }

    /**
     * Called after plugin construction and fields have been initialized.
     * Prefer to use pluginInitialize instead since there is no value in
     * having parameters on the initialize() function.
     */
    fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {}

    /**
     * Called after plugin construction and fields have been initialized.
     */
    protected fun pluginInitialize() {}

    /**
     * Executes the request.
     *
     * This method is called from the WebView thread. To do a non-trivial amount of work, use:
     * cordova.getThreadPool().execute(runnable);
     *
     * To run on the UI thread, use:
     * cordova.getActivity().runOnUiThread(runnable);
     *
     * @param action          The action to execute.
     * @param rawArgs         The exec() arguments in JSON form.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return                Whether the action was valid.
     */
    @Throws(JSONException::class)
    fun execute(action: String, rawArgs: String, callbackContext: CallbackContext): Boolean {
        val args = JSONArray(rawArgs)
        return execute(action, args, callbackContext)
    }

    /**
     * Executes the request.
     *
     * This method is called from the WebView thread. To do a non-trivial amount of work, use:
     * cordova.getThreadPool().execute(runnable);
     *
     * To run on the UI thread, use:
     * cordova.getActivity().runOnUiThread(runnable);
     *
     * @param action          The action to execute.
     * @param args            The exec() arguments.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return                Whether the action was valid.
     */
    @Throws(JSONException::class)
    fun execute(action: String, args: JSONArray, callbackContext: CallbackContext): Boolean {
        val cordovaArgs = CordovaArgs(args)
        return execute(action, cordovaArgs, callbackContext)
    }

    /**
     * Executes the request.
     *
     * This method is called from the WebView thread. To do a non-trivial amount of work, use:
     * cordova.getThreadPool().execute(runnable);
     *
     * To run on the UI thread, use:
     * cordova.getActivity().runOnUiThread(runnable);
     *
     * @param action          The action to execute.
     * @param args            The exec() arguments, wrapped with some Cordova helpers.
     * @param callbackContext The callback context used when calling back into JavaScript.
     * @return                Whether the action was valid.
     */
    @Throws(JSONException::class)
    fun execute(action: String, args: CordovaArgs, callbackContext: CallbackContext): Boolean {
        return false
    }

    /**
     * Called when the system is about to start resuming a previous activity.
     *
     * @param multitasking        Flag indicating if multitasking is turned on for app
     */
    fun onPause(multitasking: Boolean) {}

    /**
     * Called when the activity will start interacting with the user.
     *
     * @param multitasking        Flag indicating if multitasking is turned on for app
     */
    fun onResume(multitasking: Boolean) {}

    /**
     * Called when the activity is becoming visible to the user.
     */
    fun onStart() {}

    /**
     * Called when the activity is no longer visible to the user.
     */
    fun onStop() {}

    /**
     * Called when the activity receives a new intent.
     */
    fun onNewIntent(intent: Intent) {}

    /**
     * The final call you receive before your activity is destroyed.
     */
    fun onDestroy() {}

    /**
     * Called when the Activity is being destroyed (e.g. if a plugin calls out to an external
     * Activity and the OS kills the CordovaActivity in the background). The plugin should save its
     * state in this method only if it is awaiting the result of an external Activity and needs
     * to preserve some information so as to handle that result; onRestoreStateForActivityResult()
     * will only be called if the plugin is the recipient of an Activity result
     *
     * @return  Bundle containing the state of the plugin or null if state does not need to be saved
     */
    fun onSaveInstanceState(): Bundle? {
        return null
    }

    /**
     * Called when a plugin is the recipient of an Activity result after the CordovaActivity has
     * been destroyed. The Bundle will be the same as the one the plugin returned in
     * onSaveInstanceState()
     *
     * @param state             Bundle containing the state of the plugin
     * @param callbackContext   Replacement Context to return the plugin result to
     */
    fun onRestoreStateForActivityResult(state: Bundle, callbackContext: CallbackContext) {}

    /**
     * Called when a message is sent to plugin.
     *
     * @param id            The message id
     * @param data          The message data
     * @return              Object to stop propagation or null
     */
    fun onMessage(id: String, data: Object): Object? {
        return null
    }

    /**
     * Called when an activity you launched exits, giving you the requestCode you started it with,
     * the resultCode it returned, and any additional data from it.
     *
     * @param requestCode   The request code originally supplied to startActivityForResult(),
     * allowing you to identify who this result came from.
     * @param resultCode    The integer result code returned by the child activity through its setResult().
     * @param intent        An Intent, which can return result data to the caller (various data can be
     * attached to Intent "extras").
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent) {}

    /**
     * Hook for blocking the loading of external resources.
     *
     * This will be called when the WebView's shouldInterceptRequest wants to
     * know whether to open a connection to an external resource. Return false
     * to block the request: if any plugin returns false, Cordova will block
     * the request. If all plugins return null, the default policy will be
     * enforced. If at least one plugin returns true, and no plugins return
     * false, then the request will proceed.
     *
     * Note that this only affects resource requests which are routed through
     * WebViewClient.shouldInterceptRequest, such as XMLHttpRequest requests and
     * img tag loads. WebSockets and media requests (such as <video> and <audio>
     * tags) are not affected by this method. Use CSP headers to control access
     * to such resources.
    </audio></video> */
    fun shouldAllowRequest(url: String): Boolean? {
        return null
    }

    /**
     * Hook for blocking navigation by the Cordova WebView. This applies both to top-level and
     * iframe navigations.
     *
     * This will be called when the WebView's needs to know whether to navigate
     * to a new page. Return false to block the navigation: if any plugin
     * returns false, Cordova will block the navigation. If all plugins return
     * null, the default policy will be enforced. It at least one plugin returns
     * true, and no plugins return false, then the navigation will proceed.
     */
    fun shouldAllowNavigation(url: String): Boolean? {
        return null
    }

    /**
     * Hook for allowing page to call exec(). By default, this returns the result of
     * shouldAllowNavigation(). It's generally unsafe to allow untrusted content to be loaded
     * into a CordovaWebView, even within an iframe, so it's best not to touch this.
     */
    fun shouldAllowBridgeAccess(url: String): Boolean? {
        return shouldAllowNavigation(url)
    }

    /**
     * Hook for blocking the launching of Intents by the Cordova application.
     *
     * This will be called when the WebView will not navigate to a page, but
     * could launch an intent to handle the URL. Return false to block this: if
     * any plugin returns false, Cordova will block the navigation. If all
     * plugins return null, the default policy will be enforced. If at least one
     * plugin returns true, and no plugins return false, then the URL will be
     * opened.
     */
    fun shouldOpenExternalUrl(url: String): Boolean? {
        return null
    }

    /**
     * Allows plugins to handle a link being clicked. Return true here to cancel the navigation.
     *
     * @param url           The URL that is trying to be loaded in the Cordova webview.
     * @return              Return true to prevent the URL from loading. Default is false.
     */
    fun onOverrideUrlLoading(url: String): Boolean {
        return false
    }

    /**
     * Hook for redirecting requests. Applies to WebView requests as well as requests made by plugins.
     * To handle the request directly, return a URI in the form:
     *
     * cdvplugin://pluginId/...
     *
     * And implement handleOpenForRead().
     * To make this easier, use the toPluginUri() and fromPluginUri() helpers:
     *
     * public Uri remapUri(Uri uri) { return toPluginUri(uri); }
     *
     * public CordovaResourceApi.OpenForReadResult handleOpenForRead(Uri uri) throws IOException {
     * Uri origUri = fromPluginUri(uri);
     * ...
     * }
     */
    fun remapUri(uri: Uri): Uri? {
        return null
    }

    /**
     * Called to handle CordovaResourceApi.openForRead() calls for a cdvplugin://pluginId/ URL.
     * Should never return null.
     * Added in cordova-android@4.0.0
     */
    @Throws(IOException::class)
    fun handleOpenForRead(uri: Uri): CordovaResourceApi.OpenForReadResult {
        throw FileNotFoundException("Plugin can't handle uri: $uri")
    }

    /**
     * Refer to remapUri()
     * Added in cordova-android@4.0.0
     */
    protected fun toPluginUri(origUri: Uri): Uri {
        return Uri.Builder()
                .scheme(CordovaResourceApi.PLUGIN_URI_SCHEME)
                .authority(serviceName)
                .appendQueryParameter("origUri", origUri.toString())
                .build()
    }

    /**
     * Refer to remapUri()
     * Added in cordova-android@4.0.0
     */
    protected fun fromPluginUri(pluginUri: Uri): Uri {
        return Uri.parse(pluginUri.getQueryParameter("origUri"))
    }

    /**
     * Called when the WebView does a top-level navigation or refreshes.
     *
     * Plugins should stop any long-running processes and clean up internal state.
     *
     * Does nothing by default.
     */
    fun onReset() {}

    /**
     * Called when the system received an HTTP authentication request. Plugin can use
     * the supplied HttpAuthHandler to process this auth challenge.
     *
     * @param view              The WebView that is initiating the callback
     * @param handler           The HttpAuthHandler used to set the WebView's response
     * @param host              The host requiring authentication
     * @param realm             The realm for which authentication is required
     *
     * @return                  Returns True if plugin will resolve this auth challenge, otherwise False
     */
    fun onReceivedHttpAuthRequest(view: CordovaWebView, handler: ICordovaHttpAuthHandler, host: String, realm: String): Boolean {
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
        return false
    }

    /**
     * Called by the system when the device configuration changes while your activity is running.
     *
     * @param newConfig        The new device configuration
     */
    fun onConfigurationChanged(newConfig: Configuration) {}

    /**
     * Called by the Plugin Manager when we need to actually request permissions
     *
     * @param requestCode   Passed to the activity to track the request
     *
     * @return              Returns the permission that was stored in the plugin
     */

    fun requestPermissions(requestCode: Int) {}

    /*
     * Called by the WebView implementation to check for geolocation permissions, can be used
     * by other Java methods in the event that a plugin is using this as a dependency.
     *
     * @return          Returns true if the plugin has all the permissions it needs to operate.
     */

    fun hasPermisssion(): Boolean {
        return true
    }

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

    }
}
