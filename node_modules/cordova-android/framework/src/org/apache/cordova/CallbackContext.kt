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

import org.apache.cordova.CordovaWebView
import org.apache.cordova.PluginResult
import org.json.JSONObject

class CallbackContext(val callbackId: String, private val webView: CordovaWebView) {
    var isFinished: Boolean = false
        protected set
    private val changingThreads: Int = 0

    val isChangingThreads: Boolean
        get() = changingThreads > 0

    fun sendPluginResult(pluginResult: PluginResult) {
        synchronized(this) {
            if (isFinished) {
                LOG.w(LOG_TAG, "Attempted to send a second callback for ID: " + callbackId + "\nResult was: " + pluginResult.getMessage())
                return
            } else {
                isFinished = !pluginResult.getKeepCallback()
            }
        }
        webView.sendPluginResult(pluginResult, callbackId)
    }

    /**
     * Helper for success callbacks that just returns the Status.OK by default
     *
     * @param message           The message to add to the success result.
     */
    fun success(message: JSONObject) {
        sendPluginResult(PluginResult(PluginResult.Status.OK, message))
    }

    /**
     * Helper for success callbacks that just returns the Status.OK by default
     *
     * @param message           The message to add to the success result.
     */
    fun success(message: String) {
        sendPluginResult(PluginResult(PluginResult.Status.OK, message))
    }

    /**
     * Helper for success callbacks that just returns the Status.OK by default
     *
     * @param message           The message to add to the success result.
     */
    fun success(message: JSONArray) {
        sendPluginResult(PluginResult(PluginResult.Status.OK, message))
    }

    /**
     * Helper for success callbacks that just returns the Status.OK by default
     *
     * @param message           The message to add to the success result.
     */
    fun success(message: ByteArray) {
        sendPluginResult(PluginResult(PluginResult.Status.OK, message))
    }

    /**
     * Helper for success callbacks that just returns the Status.OK by default
     *
     * @param message           The message to add to the success result.
     */
    fun success(message: Int) {
        sendPluginResult(PluginResult(PluginResult.Status.OK, message))
    }

    /**
     * Helper for success callbacks that just returns the Status.OK by default
     */
    fun success() {
        sendPluginResult(PluginResult(PluginResult.Status.OK))
    }

    /**
     * Helper for error callbacks that just returns the Status.ERROR by default
     *
     * @param message           The message to add to the error result.
     */
    fun error(message: JSONObject) {
        sendPluginResult(PluginResult(PluginResult.Status.ERROR, message))
    }

    /**
     * Helper for error callbacks that just returns the Status.ERROR by default
     *
     * @param message           The message to add to the error result.
     */
    fun error(message: String) {
        sendPluginResult(PluginResult(PluginResult.Status.ERROR, message))
    }

    /**
     * Helper for error callbacks that just returns the Status.ERROR by default
     *
     * @param message           The message to add to the error result.
     */
    fun error(message: Int) {
        sendPluginResult(PluginResult(PluginResult.Status.ERROR, message))
    }

    companion object {
        private val LOG_TAG = "CordovaPlugin"
    }
}
