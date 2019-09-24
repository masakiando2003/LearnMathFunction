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
package org.apache.cordova.file

import android.util.SparseArray

import org.apache.cordova.CallbackContext

/**
 * Holds pending runtime permission requests
 */
internal class PendingRequests {
    private var currentReqId = 0
    private val requests = SparseArray<Request>()

    /**
     * Creates a request and adds it to the array of pending requests. Each created request gets a
     * unique result code for use with requestPermission()
     * @param rawArgs           The raw arguments passed to the plugin
     * @param action            The action this request corresponds to (get file, etc.)
     * @param callbackContext   The CallbackContext for this plugin call
     * @return                  The request code that can be used to retrieve the Request object
     */
    @Synchronized
    fun createRequest(rawArgs: String, action: Int, callbackContext: CallbackContext): Int {
        val req = Request(rawArgs, action, callbackContext)
        requests.put(req.requestCode, req)
        return req.requestCode
    }

    /**
     * Gets the request corresponding to this request code and removes it from the pending requests
     * @param requestCode   The request code for the desired request
     * @return              The request corresponding to the given request code or null if such a
     * request is not found
     */
    @Synchronized
    fun getAndRemove(requestCode: Int): Request {
        val result = requests.get(requestCode)
        requests.remove(requestCode)
        return result
    }

    /**
     * Holds the options and CallbackContext for a call made to the plugin.
     */
    inner class Request private constructor(// Raw arguments passed to plugin
            val rawArgs: String, // Action to be performed after permission request result
            val action: Int, // The callback context for this plugin request
            val callbackContext: CallbackContext) {

        // Unique int used to identify this request in any Android permission callback
        private val requestCode: Int

        init {
            this.requestCode = currentReqId++
        }
    }
}
