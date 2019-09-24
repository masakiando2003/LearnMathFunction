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

class LocalFilesystemURL private constructor(val uri: Uri, val fsName: String, val path: String, val isDirectory: Boolean) {

    fun toString(): String {
        return uri.toString()
    }

    companion object {

        val FILESYSTEM_PROTOCOL = "cdvfile"

        fun parse(uri: Uri): LocalFilesystemURL? {
            if (!FILESYSTEM_PROTOCOL.equals(uri.getScheme())) {
                return null
            }
            var path = uri.getPath()
            if (path.length() < 1) {
                return null
            }
            val firstSlashIdx = path.indexOf('/', 1)
            if (firstSlashIdx < 0) {
                return null
            }
            val fsName = path.substring(1, firstSlashIdx)
            path = path.substring(firstSlashIdx)
            val isDirectory = path.charAt(path.length() - 1) === '/'
            return LocalFilesystemURL(uri, fsName, path, isDirectory)
        }

        fun parse(uri: String): LocalFilesystemURL {
            return parse(Uri.parse(uri))
        }
    }
}
