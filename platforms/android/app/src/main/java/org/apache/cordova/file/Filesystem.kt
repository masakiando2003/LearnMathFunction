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

import android.net.Uri

import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayList
import java.util.Arrays

import org.apache.cordova.CordovaResourceApi
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

abstract class Filesystem(val rootUri: Uri, val name: String, protected val resourceApi: CordovaResourceApi) {
    private var rootEntry: JSONObject? = null

    /**
     * Gets the free space in bytes available on this filesystem.
     * Subclasses may override this method to return nonzero free space.
     */
    val freeSpaceInBytes: Long
        get() = 0

    interface ReadFileCallback {
        @Throws(IOException::class)
        fun handleData(inputStream: InputStream, contentType: String)
    }

    fun makeEntryForURL(inputURL: LocalFilesystemURL): JSONObject? {
        val nativeUri = toNativeUri(inputURL)
        return if (nativeUri == null) null else makeEntryForURL(inputURL, nativeUri!!)
    }

    fun makeEntryForNativeUri(nativeUri: Uri): JSONObject? {
        val inputUrl = toLocalUri(nativeUri)
        return if (inputUrl == null) null else makeEntryForURL(inputUrl!!, nativeUri)
    }

    @Throws(IOException::class)
    fun getEntryForLocalURL(inputURL: LocalFilesystemURL): JSONObject? {
        return makeEntryForURL(inputURL)
    }

    fun makeEntryForFile(file: File): JSONObject? {
        return makeEntryForNativeUri(Uri.fromFile(file))
    }

    @Throws(FileExistsException::class, IOException::class, TypeMismatchException::class, EncodingException::class, JSONException::class)
    internal abstract fun getFileForLocalURL(inputURL: LocalFilesystemURL, path: String,
                                             options: JSONObject, directory: Boolean): JSONObject

    @Throws(InvalidModificationException::class, NoModificationAllowedException::class)
    internal abstract fun removeFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean

    @Throws(FileExistsException::class, NoModificationAllowedException::class)
    internal abstract fun recursiveRemoveFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean

    @Throws(FileNotFoundException::class)
    internal abstract fun listChildren(inputURL: LocalFilesystemURL): Array<LocalFilesystemURL>?

    @Throws(FileNotFoundException::class)
    fun readEntriesAtLocalURL(inputURL: LocalFilesystemURL): JSONArray {
        val children = listChildren(inputURL)
        val entries = JSONArray()
        if (children != null) {
            for (url in children) {
                entries.put(makeEntryForURL(url))
            }
        }
        return entries
    }

    @Throws(FileNotFoundException::class)
    internal abstract fun getFileMetadataForLocalURL(inputURL: LocalFilesystemURL): JSONObject

    fun exists(inputURL: LocalFilesystemURL): Boolean {
        try {
            getFileMetadataForLocalURL(inputURL)
        } catch (e: FileNotFoundException) {
            return false
        }

        return true
    }

    fun nativeUriForFullPath(fullPath: String?): Uri? {
        var ret: Uri? = null
        if (fullPath != null) {
            var encodedPath = Uri.fromFile(File(fullPath)).getEncodedPath()
            if (encodedPath.startsWith("/")) {
                encodedPath = encodedPath.substring(1)
            }
            ret = rootUri.buildUpon().appendEncodedPath(encodedPath).build()
        }
        return ret
    }

    fun localUrlforFullPath(fullPath: String): LocalFilesystemURL? {
        val nativeUri = nativeUriForFullPath(fullPath)
        return if (nativeUri != null) {
            toLocalUri(nativeUri)
        } else null
    }

    abstract fun toNativeUri(inputURL: LocalFilesystemURL): Uri?
    abstract fun toLocalUri(inputURL: Uri): LocalFilesystemURL?

    fun getRootEntry(): JSONObject? {
        if (rootEntry == null) {
            rootEntry = makeEntryForNativeUri(rootUri)
        }
        return rootEntry
    }

    @Throws(IOException::class)
    fun getParentForLocalURL(inputURL: LocalFilesystemURL): JSONObject? {
        var parentUri = inputURL.uri
        val parentPath = File(inputURL.uri.getPath()).getParent()
        if (!"/".equals(parentPath)) {
            parentUri = inputURL.uri.buildUpon().path(parentPath + '/').build()
        }
        return getEntryForLocalURL(LocalFilesystemURL.parse(parentUri))
    }

    protected fun makeDestinationURL(newName: String, srcURL: LocalFilesystemURL, destURL: LocalFilesystemURL, isDirectory: Boolean): LocalFilesystemURL {
        var newName = newName
        // I know this looks weird but it is to work around a JSON bug.
        if ("null".equals(newName) || "".equals(newName)) {
            newName = srcURL.uri.getLastPathSegment()
        }

        var newDest = destURL.uri.toString()
        if (newDest.endsWith("/")) {
            newDest = newDest + newName
        } else {
            newDest = newDest + "/" + newName
        }
        if (isDirectory) {
            newDest += '/'
        }
        return LocalFilesystemURL.parse(newDest)
    }

    /* Read a source URL (possibly from a different filesystem, srcFs,) and copy it to
	 * the destination URL on this filesystem, optionally with a new filename.
	 * If move is true, then this method should either perform an atomic move operation
	 * or remove the source file when finished.
	 */
    @Throws(IOException::class, InvalidModificationException::class, JSONException::class, NoModificationAllowedException::class, FileExistsException::class)
    fun copyFileToURL(destURL: LocalFilesystemURL, newName: String,
                      srcFs: Filesystem, srcURL: LocalFilesystemURL, move: Boolean): JSONObject? {
        // First, check to see that we can do it
        if (move && !srcFs.canRemoveFileAtLocalURL(srcURL)) {
            throw NoModificationAllowedException("Cannot move file at source URL")
        }
        val destination = makeDestinationURL(newName, srcURL, destURL, srcURL.isDirectory)

        val srcNativeUri = srcFs.toNativeUri(srcURL)

        val ofrr = resourceApi.openForRead(srcNativeUri)
        var os: OutputStream? = null
        try {
            os = getOutputStreamForURL(destination)
        } catch (e: IOException) {
            ofrr.inputStream.close()
            throw e
        }

        // Closes streams.
        resourceApi.copyResource(ofrr, os)

        if (move) {
            srcFs.removeFileAtLocalURL(srcURL)
        }
        return getEntryForLocalURL(destination)
    }

    @Throws(IOException::class)
    fun getOutputStreamForURL(inputURL: LocalFilesystemURL): OutputStream {
        return resourceApi.openOutputStream(toNativeUri(inputURL))
    }

    @Throws(IOException::class)
    fun readFileAtURL(inputURL: LocalFilesystemURL, start: Long, end: Long,
                      readFileCallback: ReadFileCallback) {
        var end = end
        val ofrr = resourceApi.openForRead(toNativeUri(inputURL))
        if (end < 0) {
            end = ofrr.length
        }
        val numBytesToRead = end - start
        try {
            if (start > 0) {
                ofrr.inputStream.skip(start)
            }
            var inputStream = ofrr.inputStream
            if (end < ofrr.length) {
                inputStream = LimitedInputStream(inputStream, numBytesToRead)
            }
            readFileCallback.handleData(inputStream, ofrr.mimeType)
        } finally {
            ofrr.inputStream.close()
        }
    }

    @Throws(NoModificationAllowedException::class, IOException::class)
    internal abstract fun writeToFileAtURL(inputURL: LocalFilesystemURL, data: String, offset: Int,
                                           isBinary: Boolean): Long

    @Throws(IOException::class, NoModificationAllowedException::class)
    internal abstract fun truncateFileAtURL(inputURL: LocalFilesystemURL, size: Long): Long

    // This method should return null if filesystem urls cannot be mapped to paths
    internal abstract fun filesystemPathForURL(url: LocalFilesystemURL): String

    internal abstract fun URLforFilesystemPath(path: String): LocalFilesystemURL

    internal abstract fun canRemoveFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean

    protected inner class LimitedInputStream(`in`: InputStream, internal var numBytesToRead: Long) : FilterInputStream(`in`) {
        @Override
        @Throws(IOException::class)
        fun read(): Int {
            if (numBytesToRead <= 0) {
                return -1
            }
            numBytesToRead--
            return `in`.read()
        }

        @Override
        @Throws(IOException::class)
        fun read(buffer: ByteArray, byteOffset: Int, byteCount: Int): Int {
            if (numBytesToRead <= 0) {
                return -1
            }
            var bytesToRead = byteCount
            if (byteCount > numBytesToRead) {
                bytesToRead = numBytesToRead.toInt() // Cast okay; long is less than int here.
            }
            val numBytesRead = `in`.read(buffer, byteOffset, bytesToRead)
            numBytesToRead -= numBytesRead.toLong()
            return numBytesRead
        }
    }

    companion object {

        fun makeEntryForURL(inputURL: LocalFilesystemURL, nativeURL: Uri): JSONObject {
            try {
                val path = inputURL.path
                val end = if (path.endsWith("/")) 1 else 0
                val parts = path.substring(0, path.length() - end).split("/+")
                val fileName = parts[parts.size - 1]

                val entry = JSONObject()
                entry.put("isFile", !inputURL.isDirectory)
                entry.put("isDirectory", inputURL.isDirectory)
                entry.put("name", fileName)
                entry.put("fullPath", path)
                // The file system can't be specified, as it would lead to an infinite loop,
                // but the filesystem name can be.
                entry.put("filesystemName", inputURL.fsName)
                // Backwards compatibility
                entry.put("filesystem", if ("temporary".equals(inputURL.fsName)) 0 else 1)

                var nativeUrlStr = nativeURL.toString()
                if (inputURL.isDirectory && !nativeUrlStr.endsWith("/")) {
                    nativeUrlStr += "/"
                }
                entry.put("nativeURL", nativeUrlStr)
                return entry
            } catch (e: JSONException) {
                e.printStackTrace()
                throw RuntimeException(e)
            }

        }

        /**
         * Removes multiple repeated //s, and collapses processes ../s.
         */
        protected fun normalizePath(rawPath: String): String {
            var rawPath = rawPath
            // If this is an absolute path, trim the leading "/" and replace it later
            val isAbsolutePath = rawPath.startsWith("/")
            if (isAbsolutePath) {
                rawPath = rawPath.replaceFirst("/+", "")
            }
            val components = ArrayList<String>(Arrays.asList(rawPath.split("/+")))
            var index = 0
            while (index < components.size()) {
                if (components.get(index).equals("..")) {
                    components.remove(index)
                    if (index > 0) {
                        components.remove(index - 1)
                        --index
                    }
                }
                ++index
            }
            val normalizedPath = StringBuilder()
            for (component in components) {
                normalizedPath.append("/")
                normalizedPath.append(component)
            }
            return if (isAbsolutePath) {
                normalizedPath.toString()
            } else {
                normalizedPath.toString().substring(1)
            }
        }
    }
}
