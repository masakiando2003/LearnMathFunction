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

import android.content.res.AssetManager
import android.net.Uri

import org.apache.cordova.CordovaResourceApi
import org.apache.cordova.LOG
import org.apache.cordova.file.AssetFilesystem.Companion.lengthCache
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.ObjectInputStream
import java.util.HashMap

class AssetFilesystem(private val assetManager: AssetManager, resourceApi: CordovaResourceApi) : Filesystem(Uri.parse("file:///android_asset/"), "assets", resourceApi) {

    private fun lazyInitCaches() {
        synchronized(listCacheLock) {
            if (listCache == null) {
                var ois: ObjectInputStream? = null
                try {
                    ois = ObjectInputStream(assetManager.open("cdvasset.manifest"))
                    listCache = ois!!.readObject()
                    lengthCache = ois!!.readObject()
                    listCacheFromFile = true
                } catch (e: ClassNotFoundException) {
                    e.printStackTrace()
                } catch (e: IOException) {
                    // Asset manifest won't exist if the gradle hook isn't set up correctly.
                } finally {
                    if (ois != null) {
                        try {
                            ois!!.close()
                        } catch (e: IOException) {
                            LOG.d(LOG_TAG, e.getLocalizedMessage())
                        }

                    }
                }
                if (listCache == null) {
                    LOG.w("AssetFilesystem", "Asset manifest not found. Recursive copies and directory listing will be slow.")
                    listCache = HashMap<String, Array<String>>()
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun listAssets(assetPath: String): Array<String> {
        var assetPath = assetPath
        if (assetPath.startsWith("/")) {
            assetPath = assetPath.substring(1)
        }
        if (assetPath.endsWith("/")) {
            assetPath = assetPath.substring(0, assetPath.length() - 1)
        }
        lazyInitCaches()
        var ret = listCache!![assetPath]
        if (ret == null) {
            if (listCacheFromFile) {
                ret = arrayOfNulls(0)
            } else {
                ret = assetManager.list(assetPath)
                listCache!!.put(assetPath, ret)
            }
        }
        return ret
    }

    @Throws(FileNotFoundException::class)
    private fun getAssetSize(assetPath: String): Long {
        var assetPath = assetPath
        if (assetPath.startsWith("/")) {
            assetPath = assetPath.substring(1)
        }
        lazyInitCaches()
        if (lengthCache != null) {
            return lengthCache!![assetPath]
                    ?: throw FileNotFoundException("Asset not found: $assetPath")
        }
        var offr: CordovaResourceApi.OpenForReadResult? = null
        try {
            offr = resourceApi.openForRead(nativeUriForFullPath(assetPath))
            var length = offr!!.length
            if (length < 0) {
                // available() doesn't always yield the file size, but for assets it does.
                length = offr!!.inputStream.available()
            }
            return length
        } catch (e: IOException) {
            val fnfe = FileNotFoundException("File not found: $assetPath")
            fnfe.initCause(e)
            throw fnfe
        } finally {
            if (offr != null) {
                try {
                    offr!!.inputStream.close()
                } catch (e: IOException) {
                    LOG.d(LOG_TAG, e.getLocalizedMessage())
                }

            }
        }
    }

    @Override
    fun toNativeUri(inputURL: LocalFilesystemURL): Uri {
        return nativeUriForFullPath(inputURL.path)
    }

    @Override
    fun toLocalUri(inputURL: Uri): LocalFilesystemURL? {
        if (!"file".equals(inputURL.getScheme())) {
            return null
        }
        val f = File(inputURL.getPath())
        // Removes and duplicate /s (e.g. file:///a//b/c)
        val resolvedUri = Uri.fromFile(f)
        var rootUriNoTrailingSlash = rootUri.getEncodedPath()
        rootUriNoTrailingSlash = rootUriNoTrailingSlash.substring(0, rootUriNoTrailingSlash.length() - 1)
        if (!resolvedUri.getEncodedPath().startsWith(rootUriNoTrailingSlash)) {
            return null
        }
        var subPath = resolvedUri.getEncodedPath().substring(rootUriNoTrailingSlash.length())
        // Strip leading slash
        if (!subPath.isEmpty()) {
            subPath = subPath.substring(1)
        }
        val b = Uri.Builder()
                .scheme(LocalFilesystemURL.FILESYSTEM_PROTOCOL)
                .authority("localhost")
                .path(name)
        if (!subPath.isEmpty()) {
            b.appendEncodedPath(subPath)
        }
        if (isDirectory(subPath) || inputURL.getPath().endsWith("/")) {
            // Add trailing / for directories.
            b.appendEncodedPath("")
        }
        return LocalFilesystemURL.parse(b.build())
    }

    private fun isDirectory(assetPath: String): Boolean {
        try {
            return listAssets(assetPath).size != 0
        } catch (e: IOException) {
            return false
        }

    }

    @Override
    @Throws(FileNotFoundException::class)
    fun listChildren(inputURL: LocalFilesystemURL): Array<LocalFilesystemURL> {
        var pathNoSlashes = inputURL.path.substring(1)
        if (pathNoSlashes.endsWith("/")) {
            pathNoSlashes = pathNoSlashes.substring(0, pathNoSlashes.length() - 1)
        }

        val files: Array<String>
        try {
            files = listAssets(pathNoSlashes)
        } catch (e: IOException) {
            val fnfe = FileNotFoundException()
            fnfe.initCause(e)
            throw fnfe
        }

        val entries = arrayOfNulls<LocalFilesystemURL>(files.size)
        for (i in files.indices) {
            entries[i] = localUrlforFullPath(File(inputURL.path, files[i]).getPath())
        }
        return entries
    }

    @Override
    @Throws(FileExistsException::class, IOException::class, TypeMismatchException::class, EncodingException::class, JSONException::class)
    fun getFileForLocalURL(inputURL: LocalFilesystemURL,
                           path: String, options: JSONObject?, directory: Boolean): JSONObject {
        var path = path
        if (options != null && options!!.optBoolean("create")) {
            throw UnsupportedOperationException("Assets are read-only")
        }

        // Check whether the supplied path is absolute or relative
        if (directory && !path.endsWith("/")) {
            path += "/"
        }

        val requestedURL: LocalFilesystemURL
        if (path.startsWith("/")) {
            requestedURL = localUrlforFullPath(normalizePath(path))
        } else {
            requestedURL = localUrlforFullPath(normalizePath(inputURL.path + "/" + path))
        }

        // Throws a FileNotFoundException if it doesn't exist.
        getFileMetadataForLocalURL(requestedURL)

        val isDir = isDirectory(requestedURL.path)
        if (directory && !isDir) {
            throw TypeMismatchException("path doesn't exist or is file")
        } else if (!directory && isDir) {
            throw TypeMismatchException("path doesn't exist or is directory")
        }

        // Return the directory
        return makeEntryForURL(requestedURL)
    }

    @Override
    @Throws(FileNotFoundException::class)
    fun getFileMetadataForLocalURL(inputURL: LocalFilesystemURL): JSONObject? {
        val metadata = JSONObject()
        val size = if (inputURL.isDirectory) 0 else getAssetSize(inputURL.path)
        try {
            metadata.put("size", size)
            metadata.put("type", if (inputURL.isDirectory) "text/directory" else resourceApi.getMimeType(toNativeUri(inputURL)))
            metadata.put("name", File(inputURL.path).getName())
            metadata.put("fullPath", inputURL.path)
            metadata.put("lastModifiedDate", 0)
        } catch (e: JSONException) {
            return null
        }

        return metadata
    }

    @Override
    fun canRemoveFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean {
        return false
    }

    @Override
    @Throws(NoModificationAllowedException::class, IOException::class)
    internal fun writeToFileAtURL(inputURL: LocalFilesystemURL, data: String, offset: Int, isBinary: Boolean): Long {
        throw NoModificationAllowedException("Assets are read-only")
    }

    @Override
    @Throws(IOException::class, NoModificationAllowedException::class)
    internal fun truncateFileAtURL(inputURL: LocalFilesystemURL, size: Long): Long {
        throw NoModificationAllowedException("Assets are read-only")
    }

    @Override
    internal fun filesystemPathForURL(url: LocalFilesystemURL): String {
        return File(rootUri.getPath(), url.path).toString()
    }

    @Override
    internal fun URLforFilesystemPath(path: String): LocalFilesystemURL? {
        return null
    }

    @Override
    @Throws(InvalidModificationException::class, NoModificationAllowedException::class)
    internal fun removeFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean {
        throw NoModificationAllowedException("Assets are read-only")
    }

    @Override
    @Throws(NoModificationAllowedException::class)
    internal fun recursiveRemoveFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean {
        throw NoModificationAllowedException("Assets are read-only")
    }

    companion object {

        // A custom gradle hook creates the cdvasset.manifest file, which speeds up asset listing a tonne.
        // See: http://stackoverflow.com/questions/16911558/android-assetmanager-list-incredibly-slow
        private val listCacheLock = Object()
        private var listCacheFromFile: Boolean = false
        private var listCache: Map<String, Array<String>>? = null
        private var lengthCache: Map<String, Long>? = null

        private val LOG_TAG = "AssetFilesystem"
    }

}
