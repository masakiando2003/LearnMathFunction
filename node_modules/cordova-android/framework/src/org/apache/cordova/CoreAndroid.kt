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

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.TelephonyManager
import android.view.KeyEvent

import java.lang.reflect.Field
import java.util.HashMap

/**
 * This class exposes methods in Cordova that can be called from JavaScript.
 */
class CoreAndroid : CordovaPlugin() {
    private var telephonyReceiver: BroadcastReceiver? = null
    private var messageChannel: CallbackContext? = null
    private var pendingResume: PluginResult? = null
    private var pendingPause: PluginResult? = null
    private val messageChannelLock = Object()

    /**
     * Return whether the Android back button is overridden by the user.
     *
     * @return boolean
     */
    val isBackbuttonOverridden: Boolean
        get() = webView.isButtonPlumbedToJs(KeyEvent.KEYCODE_BACK)

    /**
     * Send an event to be fired on the Javascript side.
     *
     * @param action The name of the event to be fired
     */
    fun fireJavascriptEvent(action: String) {
        sendEventMessage(action)
    }

    /**
     * Sets the context of the Command. This can then be used to do things like
     * get file paths associated with the Activity.
     */
    @Override
    fun pluginInitialize() {
        this.initTelephonyReceiver()
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action            The action to execute.
     * @param args              JSONArry of arguments for the plugin.
     * @param callbackContext   The callback context from which we were invoked.
     * @return                  A PluginResult object with a status and message.
     */
    @Throws(JSONException::class)
    fun execute(action: String, args: JSONArray, callbackContext: CallbackContext): Boolean {
        val status = PluginResult.Status.OK
        val result = ""

        try {
            if (action.equals("clearCache")) {
                this.clearCache()
            } else if (action.equals("show")) {
                // This gets called from JavaScript onCordovaReady to show the webview.
                // I recommend we change the name of the Message as spinner/stop is not
                // indicative of what this actually does (shows the webview).
                cordova.getActivity().runOnUiThread(object : Runnable() {
                    fun run() {
                        webView.getPluginManager().postMessage("spinner", "stop")
                    }
                })
            } else if (action.equals("loadUrl")) {
                this.loadUrl(args.getString(0), args.optJSONObject(1))
            } else if (action.equals("cancelLoadUrl")) {
                //this.cancelLoadUrl();
            } else if (action.equals("clearHistory")) {
                this.clearHistory()
            } else if (action.equals("backHistory")) {
                this.backHistory()
            } else if (action.equals("overrideButton")) {
                this.overrideButton(args.getString(0), args.getBoolean(1))
            } else if (action.equals("overrideBackbutton")) {
                this.overrideBackbutton(args.getBoolean(0))
            } else if (action.equals("exitApp")) {
                this.exitApp()
            } else if (action.equals("messageChannel")) {
                synchronized(messageChannelLock) {
                    messageChannel = callbackContext
                    if (pendingPause != null) {
                        sendEventMessage(pendingPause!!)
                        pendingPause = null
                    }
                    if (pendingResume != null) {
                        sendEventMessage(pendingResume!!)
                        pendingResume = null
                    }
                }
                return true
            }

            callbackContext.sendPluginResult(PluginResult(status, result))
            return true
        } catch (e: JSONException) {
            callbackContext.sendPluginResult(PluginResult(PluginResult.Status.JSON_EXCEPTION))
            return false
        }

    }

    //--------------------------------------------------------------------------
    // LOCAL METHODS
    //--------------------------------------------------------------------------

    /**
     * Clear the resource cache.
     */
    fun clearCache() {
        cordova.getActivity().runOnUiThread(object : Runnable() {
            fun run() {
                webView.clearCache()
            }
        })
    }

    /**
     * Load the url into the webview.
     *
     * @param url
     * @param props            Properties that can be passed in to the Cordova activity (i.e. loadingDialog, wait, ...)
     * @throws JSONException
     */
    @Throws(JSONException::class)
    fun loadUrl(url: String, props: JSONObject?) {
        LOG.d("App", "App.loadUrl($url,$props)")
        var wait = 0
        var openExternal = false
        var clearHistory = false

        // If there are properties, then set them on the Activity
        val params = HashMap<String, Object>()
        if (props != null) {
            val keys = props!!.names()
            for (i in 0 until keys.length()) {
                val key = keys.getString(i)
                if (key.equals("wait")) {
                    wait = props!!.getInt(key)
                } else if (key.equalsIgnoreCase("openexternal")) {
                    openExternal = props!!.getBoolean(key)
                } else if (key.equalsIgnoreCase("clearhistory")) {
                    clearHistory = props!!.getBoolean(key)
                } else {
                    val value = props!!.get(key)
                    if (value == null) {

                    } else if (value!!.getClass().equals(String::class.java)) {
                        params.put(key, value as String)
                    } else if (value!!.getClass().equals(Boolean::class.java)) {
                        params.put(key, value as Boolean)
                    } else if (value!!.getClass().equals(Integer::class.java)) {
                        params.put(key, value as Integer)
                    }
                }
            }
        }

        // If wait property, then delay loading

        if (wait > 0) {
            try {
                synchronized(this) {
                    this.wait(wait)
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }

        }
        this.webView.showWebPage(url, openExternal, clearHistory, params)
    }

    /**
     * Clear page history for the app.
     */
    fun clearHistory() {
        cordova.getActivity().runOnUiThread(object : Runnable() {
            fun run() {
                webView.clearHistory()
            }
        })
    }

    /**
     * Go to previous page displayed.
     * This is the same as pressing the backbutton on Android device.
     */
    fun backHistory() {
        cordova.getActivity().runOnUiThread(object : Runnable() {
            fun run() {
                webView.backHistory()
            }
        })
    }

    /**
     * Override the default behavior of the Android back button.
     * If overridden, when the back button is pressed, the "backKeyDown" JavaScript event will be fired.
     *
     * @param override        T=override, F=cancel override
     */
    fun overrideBackbutton(override: Boolean) {
        LOG.i("App", "WARNING: Back Button Default Behavior will be overridden.  The backbutton event will be fired!")
        webView.setButtonPlumbedToJs(KeyEvent.KEYCODE_BACK, override)
    }

    /**
     * Override the default behavior of the Android volume buttons.
     * If overridden, when the volume button is pressed, the "volume[up|down]button" JavaScript event will be fired.
     *
     * @param button        volumeup, volumedown
     * @param override      T=override, F=cancel override
     */
    fun overrideButton(button: String, override: Boolean) {
        LOG.i("App", "WARNING: Volume Button Default Behavior will be overridden.  The volume event will be fired!")
        if (button.equals("volumeup")) {
            webView.setButtonPlumbedToJs(KeyEvent.KEYCODE_VOLUME_UP, override)
        } else if (button.equals("volumedown")) {
            webView.setButtonPlumbedToJs(KeyEvent.KEYCODE_VOLUME_DOWN, override)
        } else if (button.equals("menubutton")) {
            webView.setButtonPlumbedToJs(KeyEvent.KEYCODE_MENU, override)
        }
    }

    /**
     * Exit the Android application.
     */
    fun exitApp() {
        this.webView.getPluginManager().postMessage("exit", null)
    }


    /**
     * Listen for telephony events: RINGING, OFFHOOK and IDLE
     * Send these events to all plugins using
     * CordovaActivity.onMessage("telephone", "ringing" | "offhook" | "idle")
     */
    private fun initTelephonyReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        //final CordovaInterface mycordova = this.cordova;
        this.telephonyReceiver = object : BroadcastReceiver() {

            @Override
            fun onReceive(context: Context, intent: Intent?) {

                // If state has changed
                if (intent != null && intent!!.getAction().equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                    if (intent!!.hasExtra(TelephonyManager.EXTRA_STATE)) {
                        val extraData = intent!!.getStringExtra(TelephonyManager.EXTRA_STATE)
                        if (extraData.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
                            LOG.i(TAG, "Telephone RINGING")
                            webView.getPluginManager().postMessage("telephone", "ringing")
                        } else if (extraData.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
                            LOG.i(TAG, "Telephone OFFHOOK")
                            webView.getPluginManager().postMessage("telephone", "offhook")
                        } else if (extraData.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
                            LOG.i(TAG, "Telephone IDLE")
                            webView.getPluginManager().postMessage("telephone", "idle")
                        }
                    }
                }
            }
        }

        // Register the receiver
        webView.getContext().registerReceiver(this.telephonyReceiver, intentFilter)
    }

    private fun sendEventMessage(action: String) {
        val obj = JSONObject()
        try {
            obj.put("action", action)
        } catch (e: JSONException) {
            LOG.e(TAG, "Failed to create event message", e)
        }

        val result = PluginResult(PluginResult.Status.OK, obj)

        if (messageChannel == null) {
            LOG.i(TAG, "Request to send event before messageChannel initialised: $action")
            if ("pause".equals(action)) {
                pendingPause = result
            } else if ("resume".equals(action)) {
                // When starting normally onPause then onResume is called
                pendingPause = null
            }
        } else {
            sendEventMessage(result)
        }
    }

    private fun sendEventMessage(payload: PluginResult) {
        payload.setKeepCallback(true)
        if (messageChannel != null) {
            messageChannel!!.sendPluginResult(payload)
        }
    }

    /*
     * Unregister the receiver
     *
     */
    fun onDestroy() {
        webView.getContext().unregisterReceiver(this.telephonyReceiver)
    }

    /**
     * Used to send the resume event in the case that the Activity is destroyed by the OS
     *
     * @param resumeEvent PluginResult containing the payload for the resume event to be fired
     */
    fun sendResumeEvent(resumeEvent: PluginResult) {
        // This operation must be synchronized because plugin results that trigger resume
        // events can be processed asynchronously
        synchronized(messageChannelLock) {
            if (messageChannel != null) {
                sendEventMessage(resumeEvent)
            } else {
                // Might get called before the page loads, so we need to store it until the
                // messageChannel gets created
                this.pendingResume = resumeEvent
            }
        }
    }

    companion object {

        val PLUGIN_NAME = "CoreAndroid"
        protected val TAG = "CordovaApp"

        /*
     * This needs to be implemented if you wish to use the Camera Plugin or other plugins
     * that read the Build Configuration.
     *
     * Thanks to Phil@Medtronic and Graham Borland for finding the answer and posting it to
     * StackOverflow.  This is annoying as hell!
     *
     */

        fun getBuildConfigValue(ctx: Context, key: String): Object? {
            try {
                val clazz = Class.forName(ctx.getPackageName() + ".BuildConfig")
                val field = clazz.getField(key)
                return field.get(null)
            } catch (e: ClassNotFoundException) {
                LOG.d(TAG, "Unable to get the BuildConfig, is this built with ANT?")
                e.printStackTrace()
            } catch (e: NoSuchFieldException) {
                LOG.d(TAG, "$key is not a valid field. Check your build.gradle")
            } catch (e: IllegalAccessException) {
                LOG.d(TAG, "Illegal Access Exception: Let's print a stack trace.")
                e.printStackTrace()
            }

            return null
        }
    }
}
