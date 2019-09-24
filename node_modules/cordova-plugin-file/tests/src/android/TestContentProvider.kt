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
package org.apache.cordova.file.test

import android.content.ContentProvider
import android.net.Uri
import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import android.content.ContentValues
import android.database.Cursor
import android.os.ParcelFileDescriptor

import org.apache.cordova.CordovaResourceApi

import java.io.IOException
import java.util.HashMap

class TestContentProvider : ContentProvider() {

    @Override
    @Throws(FileNotFoundException::class)
    fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        var fileName = uri.getQueryParameter("realPath")
        if (fileName == null) {
            fileName = uri.getPath()
        }
        if (fileName == null || fileName!!.length() < 1) {
            throw FileNotFoundException()
        }
        val resourceApi = CordovaResourceApi(getContext(), null)
        try {
            val f = File.createTempFile("test-content-provider", ".tmp")
            resourceApi.copyResource(Uri.parse("file:///android_asset" + fileName!!), Uri.fromFile(f))
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            e.printStackTrace()
            throw FileNotFoundException("IO error: " + e.toString())
        }

    }

    @Override
    fun onCreate(): Boolean {
        return false
    }

    @Override
    fun query(uri: Uri, projection: Array<String>, selection: String, selectionArgs: Array<String>, sortOrder: String): Cursor {
        throw UnsupportedOperationException()
    }

    @Override
    fun getType(uri: Uri): String {
        return "text/html"
    }

    @Override
    fun insert(uri: Uri, values: ContentValues): Uri {
        throw UnsupportedOperationException()
    }

    @Override
    fun delete(uri: Uri, selection: String, selectionArgs: Array<String>): Int {
        throw UnsupportedOperationException()
    }

    @Override
    fun update(uri: Uri, values: ContentValues, selection: String, selectionArgs: Array<String>): Int {
        throw UnsupportedOperationException()
    }


}
