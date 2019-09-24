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

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.apache.cordova.CordovaResourceApi
import org.json.JSONException
import org.json.JSONObject

class ContentFilesystem(private val context: Context, resourceApi: CordovaResourceApi) : Filesystem(Uri.parse("content://"), "content", resourceApi) {

    @Override
    fun toNativeUri(inputURL: LocalFilesystemURL): Uri? {
        val authorityAndPath = inputURL.uri.getEncodedPath().substring(this.name.length() + 2)
        if (authorityAndPath.length() < 2) {
            return null
        }
        var ret = "content://$authorityAndPath"
        val query = inputURL.uri.getEncodedQuery()
        if (query != null) {
            ret += '?' + query!!
        }
        val frag = inputURL.uri.getEncodedFragment()
        if (frag != null) {
            ret += '#' + frag!!
        }
        return Uri.parse(ret)
    }

    @Override
    fun toLocalUri(inputURL: Uri): LocalFilesystemURL? {
        if (!"content".equals(inputURL.getScheme())) {
            return null
        }
        var subPath = inputURL.getEncodedPath()
        if (subPath.length() > 0) {
            subPath = subPath.substring(1)
        }
        val b = Uri.Builder()
                .scheme(LocalFilesystemURL.FILESYSTEM_PROTOCOL)
                .authority("localhost")
                .path(name)
                .appendPath(inputURL.getAuthority())
        if (subPath.length() > 0) {
            b.appendEncodedPath(subPath)
        }
        val localUri = b.encodedQuery(inputURL.getEncodedQuery())
                .encodedFragment(inputURL.getEncodedFragment())
                .build()
        return LocalFilesystemURL.parse(localUri)
    }

    @Override
    @Throws(IOException::class, TypeMismatchException::class, JSONException::class)
    fun getFileForLocalURL(inputURL: LocalFilesystemURL,
                           fileName: String, options: JSONObject, directory: Boolean): JSONObject {
        throw UnsupportedOperationException("getFile() not supported for content:. Use resolveLocalFileSystemURL instead.")
    }

    @Override
    @Throws(NoModificationAllowedException::class)
    fun removeFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean {
        val contentUri = toNativeUri(inputURL)
        try {
            context.getContentResolver().delete(contentUri, null, null)
        } catch (t: UnsupportedOperationException) {
            // Was seeing this on the File mobile-spec tests on 4.0.3 x86 emulator.
            // The ContentResolver applies only when the file was registered in the
            // first case, which is generally only the case with images.
            val nmae = NoModificationAllowedException("Deleting not supported for content uri: " + contentUri!!)
            nmae.initCause(t)
            throw nmae
        }

        return true
    }

    @Override
    @Throws(NoModificationAllowedException::class)
    fun recursiveRemoveFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean {
        throw NoModificationAllowedException("Cannot remove content url")
    }

    @Override
    @Throws(FileNotFoundException::class)
    fun listChildren(inputURL: LocalFilesystemURL): Array<LocalFilesystemURL> {
        throw UnsupportedOperationException("readEntriesAtLocalURL() not supported for content:. Use resolveLocalFileSystemURL instead.")
    }

    @Override
    @Throws(FileNotFoundException::class)
    fun getFileMetadataForLocalURL(inputURL: LocalFilesystemURL): JSONObject? {
        var size: Long = -1
        var lastModified: Long = 0
        val nativeUri = toNativeUri(inputURL)
        val mimeType = resourceApi.getMimeType(nativeUri)
        val cursor = openCursorForURL(nativeUri)
        try {
            if (cursor != null && cursor!!.moveToFirst()) {
                val sizeForCursor = resourceSizeForCursor(cursor!!)
                if (sizeForCursor != null) {
                    size = sizeForCursor.longValue()
                }
                val modified = lastModifiedDateForCursor(cursor!!)
                if (modified != null)
                    lastModified = modified.longValue()
            } else {
                // Some content providers don't support cursors at all!
                val offr = resourceApi.openForRead(nativeUri)
                size = offr.length
            }
        } catch (e: IOException) {
            val fnfe = FileNotFoundException()
            fnfe.initCause(e)
            throw fnfe
        } finally {
            if (cursor != null)
                cursor!!.close()
        }

        val metadata = JSONObject()
        try {
            metadata.put("size", size)
            metadata.put("type", mimeType)
            metadata.put("name", name)
            metadata.put("fullPath", inputURL.path)
            metadata.put("lastModifiedDate", lastModified)
        } catch (e: JSONException) {
            return null
        }

        return metadata
    }

    @Override
    @Throws(NoModificationAllowedException::class)
    fun writeToFileAtURL(inputURL: LocalFilesystemURL, data: String,
                         offset: Int, isBinary: Boolean): Long {
        throw NoModificationAllowedException("Couldn't write to file given its content URI")
    }

    @Override
    @Throws(NoModificationAllowedException::class)
    fun truncateFileAtURL(inputURL: LocalFilesystemURL, size: Long): Long {
        throw NoModificationAllowedException("Couldn't truncate file given its content URI")
    }

    protected fun openCursorForURL(nativeUri: Uri?): Cursor? {
        val contentResolver = context.getContentResolver()
        try {
            return contentResolver.query(nativeUri, null, null, null, null)
        } catch (e: UnsupportedOperationException) {
            return null
        }

    }

    private fun resourceSizeForCursor(cursor: Cursor): Long? {
        val columnIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (columnIndex != -1) {
            val sizeStr = cursor.getString(columnIndex)
            if (sizeStr != null) {
                return Long.parseLong(sizeStr)
            }
        }
        return null
    }

    protected fun lastModifiedDateForCursor(cursor: Cursor): Long? {
        var columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
        if (columnIndex == -1) {
            columnIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        }
        if (columnIndex != -1) {
            val dateStr = cursor.getString(columnIndex)
            if (dateStr != null) {
                return Long.parseLong(dateStr)
            }
        }
        return null
    }

    @Override
    fun filesystemPathForURL(url: LocalFilesystemURL): String? {
        val f = resourceApi.mapUriToFile(toNativeUri(url))
        return if (f == null) null else f!!.getAbsolutePath()
    }

    @Override
    fun URLforFilesystemPath(path: String): LocalFilesystemURL? {
        // Returns null as we don't support reverse mapping back to content:// URLs
        return null
    }

    @Override
    fun canRemoveFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean {
        return true
    }
}
