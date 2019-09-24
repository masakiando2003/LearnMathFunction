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

import java.util.Arrays
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions.Callback
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.PermissionRequest
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout

import org.apache.cordova.CordovaDialogsHelper
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.LOG

/**
 * This class is the WebChromeClient that implements callbacks for our web view.
 * The kind of callbacks that happen here are on the chrome outside the document,
 * such as onCreateWindow(), onConsoleMessage(), onProgressChanged(), etc. Related
 * to but different than CordovaWebViewClient.
 */
class SystemWebChromeClient(protected val parentEngine: SystemWebViewEngine) : WebChromeClient() {
    private val MAX_QUOTA = (100 * 1024 * 1024).toLong()

    // the video progress view
    private var mVideoProgressView: View? = null

    private val dialogsHelper: CordovaDialogsHelper
    private val appContext: Context

    private val mCustomViewCallback: WebChromeClient.CustomViewCallback? = null
    private val mCustomView: View? = null

    /**
     * Ask the host application for a custom progress view to show while
     * a <video> is loading.
     * @return View The progress view.
    </video> */// Create a new Loading view programmatically.
    // create the linear layout
    // the proress bar
    val videoLoadingProgressView: View
        @Override
        get() {

            if (mVideoProgressView == null) {
                val layout = LinearLayout(parentEngine.getView().getContext())
                layout.setOrientation(LinearLayout.VERTICAL)
                val layoutParams = RelativeLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT)
                layout.setLayoutParams(layoutParams)
                val bar = ProgressBar(parentEngine.getView().getContext())
                val barLayoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                barLayoutParams.gravity = Gravity.CENTER
                bar.setLayoutParams(barLayoutParams)
                layout.addView(bar)

                mVideoProgressView = layout
            }
            return mVideoProgressView
        }

    init {
        appContext = parentEngine.webView.getContext()
        dialogsHelper = CordovaDialogsHelper(appContext)
    }

    /**
     * Tell the client to display a javascript alert dialog.
     */
    @Override
    fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        dialogsHelper.showAlert(message, object : CordovaDialogsHelper.Result() {
            @Override
            fun gotResult(success: Boolean, value: String) {
                if (success) {
                    result.confirm()
                } else {
                    result.cancel()
                }
            }
        })
        return true
    }

    /**
     * Tell the client to display a confirm dialog to the user.
     */
    @Override
    fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
        dialogsHelper.showConfirm(message, object : CordovaDialogsHelper.Result() {
            @Override
            fun gotResult(success: Boolean, value: String) {
                if (success) {
                    result.confirm()
                } else {
                    result.cancel()
                }
            }
        })
        return true
    }

    /**
     * Tell the client to display a prompt dialog to the user.
     * If the client returns true, WebView will assume that the client will
     * handle the prompt dialog and call the appropriate JsPromptResult method.
     *
     * Since we are hacking prompts for our own purposes, we should not be using them for
     * this purpose, perhaps we should hack console.log to do this instead!
     */
    @Override
    fun onJsPrompt(view: WebView, origin: String, message: String, defaultValue: String, result: JsPromptResult): Boolean {
        // Unlike the @JavascriptInterface bridge, this method is always called on the UI thread.
        val handledRet = parentEngine.bridge.promptOnJsPrompt(origin, message, defaultValue)
        if (handledRet != null) {
            result.confirm(handledRet)
        } else {
            dialogsHelper.showPrompt(message, defaultValue, object : CordovaDialogsHelper.Result() {
                @Override
                fun gotResult(success: Boolean, value: String) {
                    if (success) {
                        result.confirm(value)
                    } else {
                        result.cancel()
                    }
                }
            })
        }
        return true
    }

    /**
     * Handle database quota exceeded notification.
     */
    @Override
    @SuppressWarnings("deprecation")
    fun onExceededDatabaseQuota(url: String, databaseIdentifier: String, currentQuota: Long, estimatedSize: Long,
                                totalUsedQuota: Long, quotaUpdater: WebStorage.QuotaUpdater) {
        LOG.d(LOG_TAG, "onExceededDatabaseQuota estimatedSize: %d  currentQuota: %d  totalUsedQuota: %d", estimatedSize, currentQuota, totalUsedQuota)
        quotaUpdater.updateQuota(MAX_QUOTA)
    }

    @Override
    fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        if (consoleMessage.message() != null)
            LOG.d(LOG_TAG, "%s: Line %d : %s", consoleMessage.sourceId(), consoleMessage.lineNumber(), consoleMessage.message())
        return super.onConsoleMessage(consoleMessage)
    }

    @Override
            /**
             * Instructs the client to show a prompt to ask the user to set the Geolocation permission state for the specified origin.
             *
             * This also checks for the Geolocation Plugin and requests permission from the application  to use Geolocation.
             *
             * @param origin
             * @param callback
             */
    fun onGeolocationPermissionsShowPrompt(origin: String, callback: Callback) {
        super.onGeolocationPermissionsShowPrompt(origin, callback)
        callback.invoke(origin, true, false)
        //Get the plugin, it should be loaded
        val geolocation = parentEngine.pluginManager.getPlugin("Geolocation")
        if (geolocation != null && !geolocation!!.hasPermisssion()) {
            geolocation!!.requestPermissions(0)
        }

    }

    // API level 7 is required for this, see if we could lower this using something else
    @Override
    @SuppressWarnings("deprecation")
    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        parentEngine.getCordovaWebView().showCustomView(view, callback)
    }

    @Override
    @SuppressWarnings("deprecation")
    fun onHideCustomView() {
        parentEngine.getCordovaWebView().hideCustomView()
    }

    // <input type=file> support:
    // openFileChooser() is for pre KitKat and in KitKat mr1 (it's known broken in KitKat).
    // For Lollipop, we use onShowFileChooser().
    fun openFileChooser(uploadMsg: ValueCallback<Uri>) {
        this.openFileChooser(uploadMsg, "*/*")
    }

    fun openFileChooser(uploadMsg: ValueCallback<Uri>, acceptType: String) {
        this.openFileChooser(uploadMsg, acceptType, null)
    }

    fun openFileChooser(uploadMsg: ValueCallback<Uri>, acceptType: String, capture: String?) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("*/*")
        parentEngine.cordova.startActivityForResult(object : CordovaPlugin() {
            @Override
            fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
                val result = if (intent == null || resultCode != Activity.RESULT_OK) null else intent!!.getData()
                LOG.d(LOG_TAG, "Receive file chooser URL: " + result!!)
                uploadMsg.onReceiveValue(result)
            }
        }, intent, FILECHOOSER_RESULTCODE)
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    fun onShowFileChooser(webView: WebView, filePathsCallback: ValueCallback<Array<Uri>>, fileChooserParams: WebChromeClient.FileChooserParams): Boolean {
        val intent = fileChooserParams.createIntent()
        try {
            parentEngine.cordova.startActivityForResult(object : CordovaPlugin() {
                @Override
                fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent) {
                    val result = WebChromeClient.FileChooserParams.parseResult(resultCode, intent)
                    LOG.d(LOG_TAG, "Receive file chooser URL: $result")
                    filePathsCallback.onReceiveValue(result)
                }
            }, intent, FILECHOOSER_RESULTCODE)
        } catch (e: ActivityNotFoundException) {
            LOG.w("No activity found to handle file chooser intent.", e)
            filePathsCallback.onReceiveValue(null)
        }

        return true
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    fun onPermissionRequest(request: PermissionRequest) {
        LOG.d(LOG_TAG, "onPermissionRequest: " + Arrays.toString(request.getResources()))
        request.grant(request.getResources())
    }

    fun destroyLastDialog() {
        dialogsHelper.destroyLastDialog()
    }

    companion object {

        private val FILECHOOSER_RESULTCODE = 5173
        private val LOG_TAG = "SystemWebChromeClient"
    }
}
