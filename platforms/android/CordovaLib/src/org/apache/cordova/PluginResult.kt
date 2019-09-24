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
import org.json.JSONObject

import android.util.Base64

class PluginResult {
    val status: Int
    val messageType: Int
    var keepCallback = false
    /**
     * If messageType == MESSAGE_TYPE_STRING, then returns the message string.
     * Otherwise, returns null.
     */
    val strMessage: String?
    private var encodedMessage: String? = null
    private val multipartMessages: List<PluginResult>

    val message: String?
        get() {
            if (encodedMessage == null) {
                encodedMessage = JSONObject.quote(strMessage)
            }
            return encodedMessage
        }

    val multipartMessagesSize: Int
        get() = multipartMessages.size()

    // Use sendPluginResult instead of sendJavascript.
    val jsonString: String
        @Deprecated
        get() = "{\"status\":" + this.status + ",\"message\":" + this.message + ",\"keepCallback\":" + this.keepCallback + "}"

    @JvmOverloads
    constructor(status: Status, message: String? = PluginResult.StatusMessages[status.ordinal()]) {
        this.status = status.ordinal()
        this.messageType = if (message == null) MESSAGE_TYPE_NULL else MESSAGE_TYPE_STRING
        this.strMessage = message
    }

    constructor(status: Status, message: JSONArray) {
        this.status = status.ordinal()
        this.messageType = MESSAGE_TYPE_JSON
        encodedMessage = message.toString()
    }

    constructor(status: Status, message: JSONObject) {
        this.status = status.ordinal()
        this.messageType = MESSAGE_TYPE_JSON
        encodedMessage = message.toString()
    }

    constructor(status: Status, i: Int) {
        this.status = status.ordinal()
        this.messageType = MESSAGE_TYPE_NUMBER
        this.encodedMessage = "" + i
    }

    constructor(status: Status, f: Float) {
        this.status = status.ordinal()
        this.messageType = MESSAGE_TYPE_NUMBER
        this.encodedMessage = "" + f
    }

    constructor(status: Status, b: Boolean) {
        this.status = status.ordinal()
        this.messageType = MESSAGE_TYPE_BOOLEAN
        this.encodedMessage = Boolean.toString(b)
    }

    @JvmOverloads
    constructor(status: Status, data: ByteArray, binaryString: Boolean = false) {
        this.status = status.ordinal()
        this.messageType = if (binaryString) MESSAGE_TYPE_BINARYSTRING else MESSAGE_TYPE_ARRAYBUFFER
        this.encodedMessage = Base64.encodeToString(data, Base64.NO_WRAP)
    }

    // The keepCallback and status of multipartMessages are ignored.
    constructor(status: Status, multipartMessages: List<PluginResult>) {
        this.status = status.ordinal()
        this.messageType = MESSAGE_TYPE_MULTIPART
        this.multipartMessages = multipartMessages
    }

    fun getMultipartMessage(index: Int): PluginResult {
        return multipartMessages[index]
    }

    @Deprecated // Use sendPluginResult instead of sendJavascript.
    fun toCallbackString(callbackId: String): String? {
        // If no result to be sent and keeping callback, then no need to sent back to JavaScript
        if (status == PluginResult.Status.NO_RESULT.ordinal() && keepCallback) {
            return null
        }

        // Check the success (OK, NO_RESULT & !KEEP_CALLBACK)
        return if (status == PluginResult.Status.OK.ordinal() || status == PluginResult.Status.NO_RESULT.ordinal()) {
            toSuccessCallbackString(callbackId)
        } else toErrorCallbackString(callbackId)

    }

    @Deprecated // Use sendPluginResult instead of sendJavascript.
    fun toSuccessCallbackString(callbackId: String): String {
        return "cordova.callbackSuccess('" + callbackId + "'," + this.jsonString + ");"
    }

    @Deprecated // Use sendPluginResult instead of sendJavascript.
    fun toErrorCallbackString(callbackId: String): String {
        return "cordova.callbackError('" + callbackId + "', " + this.jsonString + ");"
    }

    enum class Status {
        NO_RESULT,
        OK,
        CLASS_NOT_FOUND_EXCEPTION,
        ILLEGAL_ACCESS_EXCEPTION,
        INSTANTIATION_EXCEPTION,
        MALFORMED_URL_EXCEPTION,
        IO_EXCEPTION,
        INVALID_ACTION,
        JSON_EXCEPTION,
        ERROR
    }

    companion object {

        val MESSAGE_TYPE_STRING = 1
        val MESSAGE_TYPE_JSON = 2
        val MESSAGE_TYPE_NUMBER = 3
        val MESSAGE_TYPE_BOOLEAN = 4
        val MESSAGE_TYPE_NULL = 5
        val MESSAGE_TYPE_ARRAYBUFFER = 6
        // Use BINARYSTRING when your string may contain null characters.
        // This is required to work around a bug in the platform :(.
        val MESSAGE_TYPE_BINARYSTRING = 7
        val MESSAGE_TYPE_MULTIPART = 8

        var StatusMessages = arrayOf("No result", "OK", "Class not found", "Illegal access", "Instantiation error", "Malformed url", "IO error", "Invalid action", "JSON error", "Error")
    }
}
