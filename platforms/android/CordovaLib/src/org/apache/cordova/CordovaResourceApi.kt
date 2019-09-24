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

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.util.Base64
import android.webkit.MimeTypeMap

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * What this class provides:
 * 1. Helpers for reading & writing to URLs.
 * - E.g. handles assets, resources, content providers, files, data URIs, http[s]
 * - E.g. Can be used to query for mime-type & content length.
 *
 * 2. To allow plugins to redirect URLs (via remapUrl).
 * - All plugins should call remapUrl() on URLs they receive from JS *before*
 * passing the URL onto other utility functions in this class.
 * - For an example usage of this, refer to the org.apache.cordova.file plugin.
 *
 * Future Work:
 * - Consider using a Cursor to query content URLs for their size (like the file plugin does).
 * - Allow plugins to remapUri to "cdv-plugin://plugin-name/foo", which CordovaResourceApi
 * would then delegate to pluginManager.getPlugin(plugin-name).openForRead(url)
 * - Currently, plugins *can* do this by remapping to a data: URL, but it's inefficient
 * for large payloads.
 */
class CordovaResourceApi(context: Context, private val pluginManager: PluginManager) {

    private val assetManager: AssetManager
    private val contentResolver: ContentResolver
    var isThreadCheckingEnabled = true


    init {
        this.contentResolver = context.getContentResolver()
        this.assetManager = context.getAssets()
    }

    fun remapUri(uri: Uri): Uri {
        assertNonRelative(uri)
        val pluginUri = pluginManager.remapUri(uri)
        return if (pluginUri != null) pluginUri else uri
    }

    fun remapPath(path: String): String {
        return remapUri(Uri.fromFile(File(path))).getPath()
    }

    /**
     * Returns a File that points to the resource, or null if the resource
     * is not on the local filesystem.
     */
    fun mapUriToFile(uri: Uri): File? {
        assertBackgroundThread()
        when (getUriType(uri)) {
            URI_TYPE_FILE -> return File(uri.getPath())
            URI_TYPE_CONTENT -> {
                val cursor = contentResolver.query(uri, LOCAL_FILE_PROJECTION, null, null, null)
                if (cursor != null) {
                    try {
                        val columnIndex = cursor!!.getColumnIndex(LOCAL_FILE_PROJECTION[0])
                        if (columnIndex != -1 && cursor!!.getCount() > 0) {
                            cursor!!.moveToFirst()
                            val realPath = cursor!!.getString(columnIndex)
                            if (realPath != null) {
                                return File(realPath)
                            }
                        }
                    } finally {
                        cursor!!.close()
                    }
                }
            }
        }
        return null
    }

    fun getMimeType(uri: Uri): String? {
        when (getUriType(uri)) {
            URI_TYPE_FILE, URI_TYPE_ASSET -> return getMimeTypeFromPath(uri.getPath())
            URI_TYPE_CONTENT, URI_TYPE_RESOURCE -> return contentResolver.getType(uri)
            URI_TYPE_DATA -> {
                return getDataUriMimeType(uri)
            }
            URI_TYPE_HTTP, URI_TYPE_HTTPS -> {
                try {
                    val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                    conn.setDoInput(false)
                    conn.setRequestMethod("HEAD")
                    var mimeType = conn.getHeaderField("Content-Type")
                    if (mimeType != null) {
                        mimeType = mimeType!!.split(";")[0]
                    }
                    return mimeType
                } catch (e: IOException) {
                }

            }
        }

        return null
    }


    //This already exists
    private fun getMimeTypeFromPath(path: String): String {
        var extension = path
        val lastDot = extension.lastIndexOf('.')
        if (lastDot != -1) {
            extension = extension.substring(lastDot + 1)
        }
        // Convert the URI string to lower case to ensure compatibility with MimeTypeMap (see CB-2185).
        extension = extension.toLowerCase(Locale.getDefault())
        if (extension.equals("3ga")) {
            return "audio/3gpp"
        } else if (extension.equals("js")) {
            // Missing from the map :(.
            return "text/javascript"
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    /**
     * Opens a stream to the given URI, also providing the MIME type & length.
     * @return Never returns null.
     * @throws Throws an InvalidArgumentException for relative URIs. Relative URIs should be
     * resolved before being passed into this function.
     * @throws Throws an IOException if the URI cannot be opened.
     * @throws Throws an IllegalStateException if called on a foreground thread and skipThreadCheck is false.
     */
    @Throws(IOException::class)
    @JvmOverloads
    fun openForRead(uri: Uri, skipThreadCheck: Boolean = false): OpenForReadResult {
        if (!skipThreadCheck) {
            assertBackgroundThread()
        }
        when (getUriType(uri)) {
            URI_TYPE_FILE -> {
                val inputStream = FileInputStream(uri.getPath())
                val mimeType = getMimeTypeFromPath(uri.getPath())
                val length = inputStream.getChannel().size()
                return OpenForReadResult(uri, inputStream, mimeType, length, null)
            }
            URI_TYPE_ASSET -> {
                val assetPath = uri.getPath().substring(15)
                var assetFd: AssetFileDescriptor? = null
                var inputStream: InputStream
                var length: Long = -1
                try {
                    assetFd = assetManager.openFd(assetPath)
                    inputStream = assetFd!!.createInputStream()
                    length = assetFd!!.getLength()
                } catch (e: FileNotFoundException) {
                    // Will occur if the file is compressed.
                    inputStream = assetManager.open(assetPath)
                    length = inputStream.available()
                }

                val mimeType = getMimeTypeFromPath(assetPath)
                return OpenForReadResult(uri, inputStream, mimeType, length, assetFd)
            }
            URI_TYPE_CONTENT, URI_TYPE_RESOURCE -> {
                val mimeType = contentResolver.getType(uri)
                val assetFd = contentResolver.openAssetFileDescriptor(uri, "r")
                val inputStream = assetFd.createInputStream()
                val length = assetFd.getLength()
                return OpenForReadResult(uri, inputStream, mimeType, length, assetFd)
            }
            URI_TYPE_DATA -> {
                return readDataUri(uri) ?: break
            }
            URI_TYPE_HTTP, URI_TYPE_HTTPS -> {
                val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                conn.setDoInput(true)
                var mimeType = conn.getHeaderField("Content-Type")
                if (mimeType != null) {
                    mimeType = mimeType!!.split(";")[0]
                }
                val length = conn.getContentLength()
                val inputStream = conn.getInputStream()
                return OpenForReadResult(uri, inputStream, mimeType, length.toLong(), null)
            }
            URI_TYPE_PLUGIN -> {
                val pluginId = uri.getHost()
                val plugin = pluginManager.getPlugin(pluginId)
                        ?: throw FileNotFoundException("Invalid plugin ID in URI: $uri")
                return plugin.handleOpenForRead(uri)
            }
        }
        throw FileNotFoundException("URI not supported by CordovaResourceApi: $uri")
    }

    /**
     * Opens a stream to the given URI.
     * @return Never returns null.
     * @throws Throws an InvalidArgumentException for relative URIs. Relative URIs should be
     * resolved before being passed into this function.
     * @throws Throws an IOException if the URI cannot be opened.
     */
    @Throws(IOException::class)
    @JvmOverloads
    fun openOutputStream(uri: Uri, append: Boolean = false): OutputStream {
        assertBackgroundThread()
        when (getUriType(uri)) {
            URI_TYPE_FILE -> {
                val localFile = File(uri.getPath())
                val parent = localFile.getParentFile()
                if (parent != null) {
                    parent!!.mkdirs()
                }
                return FileOutputStream(localFile, append)
            }
            URI_TYPE_CONTENT, URI_TYPE_RESOURCE -> {
                val assetFd = contentResolver.openAssetFileDescriptor(uri, if (append) "wa" else "w")
                return assetFd.createOutputStream()
            }
        }
        throw FileNotFoundException("URI not supported by CordovaResourceApi: $uri")
    }

    @Throws(IOException::class)
    fun createHttpConnection(uri: Uri): HttpURLConnection {
        assertBackgroundThread()
        return URL(uri.toString()).openConnection() as HttpURLConnection
    }

    // Copies the input to the output in the most efficient manner possible.
    // Closes both streams.
    @Throws(IOException::class)
    fun copyResource(input: OpenForReadResult, outputStream: OutputStream?) {
        assertBackgroundThread()
        try {
            val inputStream = input.inputStream
            if (inputStream is FileInputStream && outputStream is FileOutputStream) {
                val inChannel = (input.inputStream as FileInputStream).getChannel()
                val outChannel = (outputStream as FileOutputStream).getChannel()
                var offset: Long = 0
                val length = input.length
                if (input.assetFd != null) {
                    offset = input.assetFd!!.getStartOffset()
                }
                // transferFrom()'s 2nd arg is a relative position. Need to set the absolute
                // position first.
                inChannel.position(offset)
                outChannel.transferFrom(inChannel, 0, length)
            } else {
                val BUFFER_SIZE = 8192
                val buffer = ByteArray(BUFFER_SIZE)

                while (true) {
                    val bytesRead = inputStream.read(buffer, 0, BUFFER_SIZE)

                    if (bytesRead <= 0) {
                        break
                    }
                    outputStream!!.write(buffer, 0, bytesRead)
                }
            }
        } finally {
            input.inputStream.close()
            if (outputStream != null) {
                outputStream!!.close()
            }
        }
    }

    @Throws(IOException::class)
    fun copyResource(sourceUri: Uri, outputStream: OutputStream) {
        copyResource(openForRead(sourceUri), outputStream)
    }

    // Added in 3.5.0.
    @Throws(IOException::class)
    fun copyResource(sourceUri: Uri, dstUri: Uri) {
        copyResource(openForRead(sourceUri), openOutputStream(dstUri))
    }

    private fun assertBackgroundThread() {
        if (isThreadCheckingEnabled) {
            val curThread = Thread.currentThread()
            if (curThread === Looper.getMainLooper().getThread()) {
                throw IllegalStateException("Do not perform IO operations on the UI thread. Use CordovaInterface.getThreadPool() instead.")
            }
            if (curThread === jsThread) {
                throw IllegalStateException("Tried to perform an IO operation on the WebCore thread. Use CordovaInterface.getThreadPool() instead.")
            }
        }
    }

    private fun getDataUriMimeType(uri: Uri): String? {
        val uriAsString = uri.getSchemeSpecificPart()
        val commaPos = uriAsString.indexOf(',')
        if (commaPos == -1) {
            return null
        }
        val mimeParts = uriAsString.substring(0, commaPos).split(";")
        return if (mimeParts.size > 0) {
            mimeParts[0]
        } else null
    }

    private fun readDataUri(uri: Uri): OpenForReadResult? {
        val uriAsString = uri.getSchemeSpecificPart()
        val commaPos = uriAsString.indexOf(',')
        if (commaPos == -1) {
            return null
        }
        val mimeParts = uriAsString.substring(0, commaPos).split(";")
        var contentType: String? = null
        var base64 = false
        if (mimeParts.size > 0) {
            contentType = mimeParts[0]
        }
        for (i in 1 until mimeParts.size) {
            if ("base64".equalsIgnoreCase(mimeParts[i])) {
                base64 = true
            }
        }
        val dataPartAsString = uriAsString.substring(commaPos + 1)
        var data: ByteArray
        if (base64) {
            data = Base64.decode(dataPartAsString, Base64.DEFAULT)
        } else {
            try {
                data = dataPartAsString.getBytes("UTF-8")
            } catch (e: UnsupportedEncodingException) {
                data = dataPartAsString.getBytes()
            }

        }
        val inputStream = ByteArrayInputStream(data)
        return OpenForReadResult(uri, inputStream, contentType, data.size.toLong(), null)
    }

    class OpenForReadResult(val uri: Uri, val inputStream: InputStream, val mimeType: String, val length: Long, val assetFd: AssetFileDescriptor?)

    companion object {
        @SuppressWarnings("unused")
        private val LOG_TAG = "CordovaResourceApi"

        val URI_TYPE_FILE = 0
        val URI_TYPE_ASSET = 1
        val URI_TYPE_CONTENT = 2
        val URI_TYPE_RESOURCE = 3
        val URI_TYPE_DATA = 4
        val URI_TYPE_HTTP = 5
        val URI_TYPE_HTTPS = 6
        val URI_TYPE_PLUGIN = 7
        val URI_TYPE_UNKNOWN = -1

        val PLUGIN_URI_SCHEME = "cdvplugin"

        private val LOCAL_FILE_PROJECTION = arrayOf("_data")

        var jsThread: Thread? = null


        fun getUriType(uri: Uri): Int {
            assertNonRelative(uri)
            val scheme = uri.getScheme()
            if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(scheme)) {
                return URI_TYPE_CONTENT
            }
            if (ContentResolver.SCHEME_ANDROID_RESOURCE.equalsIgnoreCase(scheme)) {
                return URI_TYPE_RESOURCE
            }
            if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(scheme)) {
                return if (uri.getPath().startsWith("/android_asset/")) {
                    URI_TYPE_ASSET
                } else URI_TYPE_FILE
            }
            if ("data".equalsIgnoreCase(scheme)) {
                return URI_TYPE_DATA
            }
            if ("http".equalsIgnoreCase(scheme)) {
                return URI_TYPE_HTTP
            }
            if ("https".equalsIgnoreCase(scheme)) {
                return URI_TYPE_HTTPS
            }
            return if (PLUGIN_URI_SCHEME.equalsIgnoreCase(scheme)) {
                URI_TYPE_PLUGIN
            } else URI_TYPE_UNKNOWN
        }

        private fun assertNonRelative(uri: Uri) {
            if (!uri.isAbsolute()) {
                throw IllegalArgumentException("Relative URIs are not supported.")
            }
        }
    }
}
/**
 * Opens a stream to the given URI, also providing the MIME type & length.
 * @return Never returns null.
 * @throws Throws an InvalidArgumentException for relative URIs. Relative URIs should be
 * resolved before being passed into this function.
 * @throws Throws an IOException if the URI cannot be opened.
 * @throws Throws an IllegalStateException if called on a foreground thread.
 */
