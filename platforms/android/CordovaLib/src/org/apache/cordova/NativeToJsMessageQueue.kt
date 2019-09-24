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

import java.util.ArrayList
import java.util.LinkedList

/**
 * Holds the list of messages to be sent to the WebView.
 */
class NativeToJsMessageQueue {

    /**
     * When true, the active listener is not fired upon enqueue. When set to false,
     * the active listener will be fired if the queue is non-empty.
     */
    private var paused: Boolean = false

    /**
     * The list of JavaScript statements to be sent to JavaScript.
     */
    private val queue = LinkedList<JsMessage>()

    /**
     * The array of listeners that can be used to send messages to JS.
     */
    private val bridgeModes = ArrayList<BridgeMode>()

    /**
     * When null, the bridge is disabled. This occurs during page transitions.
     * When disabled, all callbacks are dropped since they are assumed to be
     * relevant to the previous page.
     */
    private var activeBridgeMode: BridgeMode? = null

    val isBridgeEnabled: Boolean
        get() = activeBridgeMode != null

    val isEmpty: Boolean
        get() = queue.isEmpty()

    fun addBridgeMode(bridgeMode: BridgeMode) {
        bridgeModes.add(bridgeMode)
    }

    /**
     * Changes the bridge mode.
     */
    fun setBridgeMode(value: Int) {
        if (value < -1 || value >= bridgeModes.size()) {
            LOG.d(LOG_TAG, "Invalid NativeToJsBridgeMode: $value")
        } else {
            val newMode = if (value < 0) null else bridgeModes.get(value)
            if (newMode !== activeBridgeMode) {
                LOG.d(LOG_TAG, "Set native->JS mode to " + if (newMode == null) "null" else newMode!!.getClass().getSimpleName())
                synchronized(this) {
                    activeBridgeMode = newMode
                    if (newMode != null) {
                        newMode!!.reset()
                        if (!paused && !queue.isEmpty()) {
                            newMode!!.onNativeToJsMessageAvailable(this)
                        }
                    }
                }
            }
        }
    }

    /**
     * Clears all messages and resets to the default bridge mode.
     */
    fun reset() {
        synchronized(this) {
            queue.clear()
            setBridgeMode(-1)
        }
    }

    private fun calculatePackedMessageLength(message: JsMessage): Int {
        val messageLen = message.calculateEncodedLength()
        val messageLenStr = String.valueOf(messageLen)
        return messageLenStr.length() + messageLen + 1
    }

    private fun packMessage(message: JsMessage, sb: StringBuilder) {
        val len = message.calculateEncodedLength()
        sb.append(len)
                .append(' ')
        message.encodeAsMessage(sb)
    }

    /**
     * Combines and returns queued messages combined into a single string.
     * Combines as many messages as possible, while staying under MAX_PAYLOAD_SIZE.
     * Returns null if the queue is empty.
     */
    fun popAndEncode(fromOnlineEvent: Boolean): String? {
        synchronized(this) {
            if (activeBridgeMode == null) {
                return null
            }
            activeBridgeMode!!.notifyOfFlush(this, fromOnlineEvent)
            if (queue.isEmpty()) {
                return null
            }
            var totalPayloadLen = 0
            var numMessagesToSend = 0
            for (message in queue) {
                val messageSize = calculatePackedMessageLength(message)
                if (numMessagesToSend > 0 && totalPayloadLen + messageSize > MAX_PAYLOAD_SIZE && MAX_PAYLOAD_SIZE > 0) {
                    break
                }
                totalPayloadLen += messageSize
                numMessagesToSend += 1
            }

            val sb = StringBuilder(totalPayloadLen)
            for (i in 0 until numMessagesToSend) {
                val message = queue.removeFirst()
                packMessage(message, sb)
            }

            if (!queue.isEmpty()) {
                // Attach a char to indicate that there are more messages pending.
                sb.append('*')
            }
            return sb.toString()
        }
    }

    /**
     * Same as popAndEncode(), except encodes in a form that can be executed as JS.
     */
    fun popAndEncodeAsJs(): String? {
        synchronized(this) {
            val length = queue.size()
            if (length == 0) {
                return null
            }
            var totalPayloadLen = 0
            var numMessagesToSend = 0
            for (message in queue) {
                val messageSize = message.calculateEncodedLength() + 50 // overestimate.
                if (numMessagesToSend > 0 && totalPayloadLen + messageSize > MAX_PAYLOAD_SIZE && MAX_PAYLOAD_SIZE > 0) {
                    break
                }
                totalPayloadLen += messageSize
                numMessagesToSend += 1
            }
            val willSendAllMessages = numMessagesToSend == queue.size()
            val sb = StringBuilder(totalPayloadLen + if (willSendAllMessages) 0 else 100)
            // Wrap each statement in a try/finally so that if one throws it does
            // not affect the next.
            for (i in 0 until numMessagesToSend) {
                val message = queue.removeFirst()
                if (willSendAllMessages && i + 1 == numMessagesToSend) {
                    message.encodeAsJsMessage(sb)
                } else {
                    sb.append("try{")
                    message.encodeAsJsMessage(sb)
                    sb.append("}finally{")
                }
            }
            if (!willSendAllMessages) {
                sb.append("window.setTimeout(function(){cordova.require('cordova/plugin/android/polling').pollOnce();},0);")
            }
            for (i in (if (willSendAllMessages) 1 else 0) until numMessagesToSend) {
                sb.append('}')
            }
            return sb.toString()
        }
    }

    /**
     * Add a JavaScript statement to the list.
     */
    fun addJavaScript(statement: String) {
        enqueueMessage(JsMessage(statement))
    }

    /**
     * Add a JavaScript statement to the list.
     */
    fun addPluginResult(result: PluginResult, callbackId: String?) {
        if (callbackId == null) {
            LOG.e(LOG_TAG, "Got plugin result with no callbackId", Throwable())
            return
        }
        // Don't send anything if there is no result and there is no need to
        // clear the callbacks.
        val noResult = result.getStatus() === PluginResult.Status.NO_RESULT.ordinal()
        val keepCallback = result.getKeepCallback()
        if (noResult && keepCallback) {
            return
        }
        var message = JsMessage(result, callbackId)
        if (FORCE_ENCODE_USING_EVAL) {
            val sb = StringBuilder(message.calculateEncodedLength() + 50)
            message.encodeAsJsMessage(sb)
            message = JsMessage(sb.toString())
        }

        enqueueMessage(message)
    }

    private fun enqueueMessage(message: JsMessage) {
        synchronized(this) {
            if (activeBridgeMode == null) {
                LOG.d(LOG_TAG, "Dropping Native->JS message due to disabled bridge")
                return
            }
            queue.add(message)
            if (!paused) {
                activeBridgeMode!!.onNativeToJsMessageAvailable(this)
            }
        }
    }

    fun setPaused(value: Boolean) {
        if (paused && value) {
            // This should never happen. If a use-case for it comes up, we should
            // change pause to be a counter.
            LOG.e(LOG_TAG, "nested call to setPaused detected.", Throwable())
        }
        paused = value
        if (!value) {
            synchronized(this) {
                if (!queue.isEmpty() && activeBridgeMode != null) {
                    activeBridgeMode!!.onNativeToJsMessageAvailable(this)
                }
            }
        }
    }

    abstract class BridgeMode {
        abstract fun onNativeToJsMessageAvailable(queue: NativeToJsMessageQueue)
        fun notifyOfFlush(queue: NativeToJsMessageQueue, fromOnlineEvent: Boolean) {}
        fun reset() {}
    }

    /** Uses JS polls for messages on a timer..  */
    class NoOpBridgeMode : BridgeMode() {
        @Override
        override fun onNativeToJsMessageAvailable(queue: NativeToJsMessageQueue) {
        }
    }

    /** Uses webView.loadUrl("javascript:") to execute messages.  */
    class LoadUrlBridgeMode(private val engine: CordovaWebViewEngine, private val cordova: CordovaInterface) : BridgeMode() {

        @Override
        override fun onNativeToJsMessageAvailable(queue: NativeToJsMessageQueue) {
            cordova.getActivity().runOnUiThread(object : Runnable() {
                fun run() {
                    val js = queue.popAndEncodeAsJs()
                    if (js != null) {
                        engine.loadUrl("javascript:$js", false)
                    }
                }
            })
        }
    }

    /** Uses online/offline events to tell the JS when to poll for messages.  */
    class OnlineEventsBridgeMode(private val delegate: OnlineEventsBridgeModeDelegate) : BridgeMode() {
        private var online: Boolean = false
        private var ignoreNextFlush: Boolean = false

        interface OnlineEventsBridgeModeDelegate {
            fun setNetworkAvailable(value: Boolean)
            fun runOnUiThread(r: Runnable)
        }

        @Override
        override fun reset() {
            delegate.runOnUiThread(object : Runnable() {
                fun run() {
                    online = false
                    // If the following call triggers a notifyOfFlush, then ignore it.
                    ignoreNextFlush = true
                    delegate.setNetworkAvailable(true)
                }
            })
        }

        @Override
        override fun onNativeToJsMessageAvailable(queue: NativeToJsMessageQueue) {
            delegate.runOnUiThread(object : Runnable() {
                fun run() {
                    if (!queue.isEmpty) {
                        ignoreNextFlush = false
                        delegate.setNetworkAvailable(online)
                    }
                }
            })
        }

        // Track when online/offline events are fired so that we don't fire excess events.
        @Override
        override fun notifyOfFlush(queue: NativeToJsMessageQueue, fromOnlineEvent: Boolean) {
            if (fromOnlineEvent && !ignoreNextFlush) {
                online = !online
            }
        }
    }

    /** Uses webView.evaluateJavascript to execute messages.  */
    class EvalBridgeMode(private val engine: CordovaWebViewEngine, private val cordova: CordovaInterface) : BridgeMode() {

        @Override
        override fun onNativeToJsMessageAvailable(queue: NativeToJsMessageQueue) {
            cordova.getActivity().runOnUiThread(object : Runnable() {
                fun run() {
                    val js = queue.popAndEncodeAsJs()
                    if (js != null) {
                        engine.evaluateJavascript(js, null)
                    }
                }
            })
        }
    }


    private class JsMessage {
        internal val jsPayloadOrCallbackId: String
        internal val pluginResult: PluginResult?

        internal constructor(js: String?) {
            if (js == null) {
                throw NullPointerException()
            }
            jsPayloadOrCallbackId = js
            pluginResult = null
        }

        internal constructor(pluginResult: PluginResult?, callbackId: String?) {
            if (callbackId == null || pluginResult == null) {
                throw NullPointerException()
            }
            jsPayloadOrCallbackId = callbackId
            this.pluginResult = pluginResult
        }

        internal fun calculateEncodedLength(): Int {
            if (pluginResult == null) {
                return jsPayloadOrCallbackId.length() + 1
            }
            val statusLen = String.valueOf(pluginResult!!.getStatus()).length()
            val ret = 2 + statusLen + 1 + jsPayloadOrCallbackId.length() + 1
            return ret + calculateEncodedLengthHelper(pluginResult!!)
        }

        internal fun encodeAsMessage(sb: StringBuilder) {
            if (pluginResult == null) {
                sb.append('J')
                        .append(jsPayloadOrCallbackId)
                return
            }
            val status = pluginResult!!.getStatus()
            val noResult = status == PluginResult.Status.NO_RESULT.ordinal()
            val resultOk = status == PluginResult.Status.OK.ordinal()
            val keepCallback = pluginResult!!.getKeepCallback()

            sb.append(if (noResult || resultOk) 'S' else 'F')
                    .append(if (keepCallback) '1' else '0')
                    .append(status)
                    .append(' ')
                    .append(jsPayloadOrCallbackId)
                    .append(' ')

            encodeAsMessageHelper(sb, pluginResult!!)
        }

        internal fun buildJsMessage(sb: StringBuilder) {
            when (pluginResult!!.getMessageType()) {
                PluginResult.MESSAGE_TYPE_MULTIPART -> {
                    val size = pluginResult!!.getMultipartMessagesSize()
                    for (i in 0 until size) {
                        val subresult = pluginResult!!.getMultipartMessage(i)
                        val submessage = JsMessage(subresult, jsPayloadOrCallbackId)
                        submessage.buildJsMessage(sb)
                        if (i < size - 1) {
                            sb.append(",")
                        }
                    }
                }
                PluginResult.MESSAGE_TYPE_BINARYSTRING -> sb.append("atob('")
                        .append(pluginResult!!.getMessage())
                        .append("')")
                PluginResult.MESSAGE_TYPE_ARRAYBUFFER -> sb.append("cordova.require('cordova/base64').toArrayBuffer('")
                        .append(pluginResult!!.getMessage())
                        .append("')")
                PluginResult.MESSAGE_TYPE_NULL -> sb.append("null")
                else -> sb.append(pluginResult!!.getMessage())
            }
        }

        internal fun encodeAsJsMessage(sb: StringBuilder) {
            if (pluginResult == null) {
                sb.append(jsPayloadOrCallbackId)
            } else {
                val status = pluginResult!!.getStatus()
                val success = status == PluginResult.Status.OK.ordinal() || status == PluginResult.Status.NO_RESULT.ordinal()
                sb.append("cordova.callbackFromNative('")
                        .append(jsPayloadOrCallbackId)
                        .append("',")
                        .append(success)
                        .append(",")
                        .append(status)
                        .append(",[")
                buildJsMessage(sb)
                sb.append("],")
                        .append(pluginResult!!.getKeepCallback())
                        .append(");")
            }
        }

        companion object {

            internal fun calculateEncodedLengthHelper(pluginResult: PluginResult): Int {
                when (pluginResult.getMessageType()) {
                    PluginResult.MESSAGE_TYPE_BOOLEAN // f or t
                        , PluginResult.MESSAGE_TYPE_NULL // N
                    -> return 1
                    PluginResult.MESSAGE_TYPE_NUMBER // n
                    -> return 1 + pluginResult.getMessage().length()
                    PluginResult.MESSAGE_TYPE_STRING // s
                    -> return 1 + pluginResult.getStrMessage().length()
                    PluginResult.MESSAGE_TYPE_BINARYSTRING -> return 1 + pluginResult.getMessage().length()
                    PluginResult.MESSAGE_TYPE_ARRAYBUFFER -> return 1 + pluginResult.getMessage().length()
                    PluginResult.MESSAGE_TYPE_MULTIPART -> {
                        var ret = 1
                        for (i in 0 until pluginResult.getMultipartMessagesSize()) {
                            val length = calculateEncodedLengthHelper(pluginResult.getMultipartMessage(i))
                            val argLength = String.valueOf(length).length()
                            ret += argLength + 1 + length
                        }
                        return ret
                    }
                    PluginResult.MESSAGE_TYPE_JSON -> return pluginResult.getMessage().length()
                    else -> return pluginResult.getMessage().length()
                }
            }

            internal fun encodeAsMessageHelper(sb: StringBuilder, pluginResult: PluginResult) {
                when (pluginResult.getMessageType()) {
                    PluginResult.MESSAGE_TYPE_BOOLEAN -> sb.append(pluginResult.getMessage().charAt(0)) // t or f.
                    PluginResult.MESSAGE_TYPE_NULL // N
                    -> sb.append('N')
                    PluginResult.MESSAGE_TYPE_NUMBER // n
                    -> sb.append('n')
                            .append(pluginResult.getMessage())
                    PluginResult.MESSAGE_TYPE_STRING // s
                    -> {
                        sb.append('s')
                        sb.append(pluginResult.getStrMessage())
                    }
                    PluginResult.MESSAGE_TYPE_BINARYSTRING // S
                    -> {
                        sb.append('S')
                        sb.append(pluginResult.getMessage())
                    }
                    PluginResult.MESSAGE_TYPE_ARRAYBUFFER // A
                    -> {
                        sb.append('A')
                        sb.append(pluginResult.getMessage())
                    }
                    PluginResult.MESSAGE_TYPE_MULTIPART -> {
                        sb.append('M')
                        for (i in 0 until pluginResult.getMultipartMessagesSize()) {
                            val multipartMessage = pluginResult.getMultipartMessage(i)
                            sb.append(String.valueOf(calculateEncodedLengthHelper(multipartMessage)))
                            sb.append(' ')
                            encodeAsMessageHelper(sb, multipartMessage)
                        }
                    }
                    PluginResult.MESSAGE_TYPE_JSON -> sb.append(pluginResult.getMessage()) // [ or {
                    else -> sb.append(pluginResult.getMessage())
                }
            }
        }
    }

    companion object {
        private val LOG_TAG = "JsMessageQueue"

        // Set this to true to force plugin results to be encoding as
        // JS instead of the custom format (useful for benchmarking).
        // Doesn't work for multipart messages.
        private val FORCE_ENCODE_USING_EVAL = false

        // Disable sending back native->JS messages during an exec() when the active
        // exec() is asynchronous. Set this to true when running bridge benchmarks.
        internal val DISABLE_EXEC_CHAINING = false

        // Arbitrarily chosen upper limit for how much data to send to JS in one shot.
        // This currently only chops up on message boundaries. It may be useful
        // to allow it to break up messages.
        private val MAX_PAYLOAD_SIZE = 50 * 1024 * 10240
    }
}
