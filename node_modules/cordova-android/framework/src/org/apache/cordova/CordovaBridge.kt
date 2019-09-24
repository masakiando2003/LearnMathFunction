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

import java.security.SecureRandom

import org.json.JSONArray
import org.json.JSONException

/**
 * Contains APIs that the JS can call. All functions in here should also have
 * an equivalent entry in CordovaChromeClient.java, and be added to
 * cordova-js/lib/android/plugin/android/promptbasednativeapi.js
 */
class CordovaBridge(private val pluginManager: PluginManager, private val jsMessageQueue: NativeToJsMessageQueue) {
    @Volatile
    private var expectedBridgeSecret = -1 // written by UI thread, read by JS thread.

    val isSecretEstablished: Boolean
        get() = expectedBridgeSecret != -1

    @Throws(JSONException::class, IllegalAccessException::class)
    fun jsExec(bridgeSecret: Int, service: String, action: String, callbackId: String, arguments: String?): String? {
        if (!verifySecret("exec()", bridgeSecret)) {
            return null
        }
        // If the arguments weren't received, send a message back to JS.  It will switch bridge modes and try again.  See CB-2666.
        // We send a message meant specifically for this case.  It starts with "@" so no other message can be encoded into the same string.
        if (arguments == null) {
            return "@Null arguments."
        }

        jsMessageQueue.setPaused(true)
        try {
            // Tell the resourceApi what thread the JS is running on.
            CordovaResourceApi.jsThread = Thread.currentThread()

            pluginManager.exec(service, action, callbackId, arguments)
            var ret: String? = null
            if (!NativeToJsMessageQueue.DISABLE_EXEC_CHAINING) {
                ret = jsMessageQueue.popAndEncode(false)
            }
            return ret
        } catch (e: Throwable) {
            e.printStackTrace()
            return ""
        } finally {
            jsMessageQueue.setPaused(false)
        }
    }

    @Throws(IllegalAccessException::class)
    fun jsSetNativeToJsBridgeMode(bridgeSecret: Int, value: Int) {
        if (!verifySecret("setNativeToJsBridgeMode()", bridgeSecret)) {
            return
        }
        jsMessageQueue.setBridgeMode(value)
    }

    @Throws(IllegalAccessException::class)
    fun jsRetrieveJsMessages(bridgeSecret: Int, fromOnlineEvent: Boolean): String? {
        return if (!verifySecret("retrieveJsMessages()", bridgeSecret)) {
            null
        } else jsMessageQueue.popAndEncode(fromOnlineEvent)
    }

    @Throws(IllegalAccessException::class)
    private fun verifySecret(action: String, bridgeSecret: Int): Boolean {
        if (!jsMessageQueue.isBridgeEnabled()) {
            if (bridgeSecret == -1) {
                LOG.d(LOG_TAG, "$action call made before bridge was enabled.")
            } else {
                LOG.d(LOG_TAG, "Ignoring $action from previous page load.")
            }
            return false
        }
        // Bridge secret wrong and bridge not due to it being from the previous page.
        if (expectedBridgeSecret < 0 || bridgeSecret != expectedBridgeSecret) {
            LOG.e(LOG_TAG, "Bridge access attempt with wrong secret token, possibly from malicious code. Disabling exec() bridge!")
            clearBridgeSecret()
            throw IllegalAccessException()
        }
        return true
    }

    /** Called on page transitions  */
    internal fun clearBridgeSecret() {
        expectedBridgeSecret = -1
    }

    /** Called by cordova.js to initialize the bridge.  */
    //On old Androids SecureRandom isn't really secure, this is the least of your problems if
    //you're running Android 4.3 and below in 2017
    @SuppressLint("TrulyRandom")
    internal fun generateBridgeSecret(): Int {
        val randGen = SecureRandom()
        expectedBridgeSecret = randGen.nextInt(Integer.MAX_VALUE)
        return expectedBridgeSecret
    }

    fun reset() {
        jsMessageQueue.reset()
        clearBridgeSecret()
    }

    fun promptOnJsPrompt(origin: String, message: String, defaultValue: String?): String? {
        if (defaultValue != null && defaultValue.length() > 3 && defaultValue.startsWith("gap:")) {
            val array: JSONArray
            try {
                array = JSONArray(defaultValue.substring(4))
                val bridgeSecret = array.getInt(0)
                val service = array.getString(1)
                val action = array.getString(2)
                val callbackId = array.getString(3)
                val r = jsExec(bridgeSecret, service, action, callbackId, message)
                return r ?: ""
            } catch (e: JSONException) {
                e.printStackTrace()
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            }

            return ""
        } else if (defaultValue != null && defaultValue.startsWith("gap_bridge_mode:")) {
            try {
                val bridgeSecret = Integer.parseInt(defaultValue.substring(16))
                jsSetNativeToJsBridgeMode(bridgeSecret, Integer.parseInt(message))
            } catch (e: NumberFormatException) {
                e.printStackTrace()
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            }

            return ""
        } else if (defaultValue != null && defaultValue.startsWith("gap_poll:")) {
            val bridgeSecret = Integer.parseInt(defaultValue.substring(9))
            try {
                val r = jsRetrieveJsMessages(bridgeSecret, "1".equals(message))
                return r ?: ""
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            }

            return ""
        } else if (defaultValue != null && defaultValue.startsWith("gap_init:")) {
            // Protect against random iframes being able to talk through the bridge.
            // Trust only pages which the app would have been allowed to navigate to anyway.
            if (pluginManager.shouldAllowBridgeAccess(origin)) {
                // Enable the bridge
                val bridgeMode = Integer.parseInt(defaultValue.substring(9))
                jsMessageQueue.setBridgeMode(bridgeMode)
                // Tell JS the bridge secret.
                val secret = generateBridgeSecret()
                return "" + secret
            } else {
                LOG.e(LOG_TAG, "gap_init called from restricted origin: $origin")
            }
            return ""
        }// Polling for JavaScript messages
        // Sets the native->JS bridge mode.
        return null
    }

    companion object {
        private val LOG_TAG = "CordovaBridge"
    }
}
