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

import android.os.Environment
import android.os.StatFs

import java.io.File

/**
 * This class provides file directory utilities.
 * All file operations are performed on the SD card.
 *
 * It is used by the FileUtils class.
 */
object DirectoryManager {

    @SuppressWarnings("unused")
    private val LOG_TAG = "DirectoryManager"

    /**
     * Get the free space in external storage
     *
     * @return        Size in KB or -1 if not available
     */
    // Check if external storage exists
    // If no external storage then return -1
    val freeExternalStorageSpace: Long
        get() {
            val status = Environment.getExternalStorageState()
            var freeSpaceInBytes: Long = 0
            if (status.equals(Environment.MEDIA_MOUNTED)) {
                freeSpaceInBytes = getFreeSpaceInBytes(Environment.getExternalStorageDirectory().getPath())
            } else {
                return -1
            }

            return freeSpaceInBytes / 1024
        }

    /**
     * Determine if a file or directory exists.
     * @param name                The name of the file to check.
     * @return                    T=exists, F=not found
     */
    fun testFileExists(name: String): Boolean {
        val status: Boolean

        // If SD card exists
        if (testSaveLocationExists() && !name.equals("")) {
            val path = Environment.getExternalStorageDirectory()
            val newPath = constructFilePaths(path.toString(), name)
            status = newPath.exists()
        } else {
            status = false
        }// If no SD card
        return status
    }

    /**
     * Given a path return the number of free bytes in the filesystem containing the path.
     *
     * @param path to the file system
     * @return free space in bytes
     */
    fun getFreeSpaceInBytes(path: String): Long {
        try {
            val stat = StatFs(path)
            val blockSize = stat.getBlockSize()
            val availableBlocks = stat.getAvailableBlocks()
            return availableBlocks * blockSize
        } catch (e: IllegalArgumentException) {
            // The path was invalid. Just return 0 free bytes.
            return 0
        }

    }

    /**
     * Determine if SD card exists.
     *
     * @return                T=exists, F=not found
     */
    fun testSaveLocationExists(): Boolean {
        val sDCardStatus = Environment.getExternalStorageState()
        val status: Boolean

        // If SD card is mounted
        if (sDCardStatus.equals(Environment.MEDIA_MOUNTED)) {
            status = true
        } else {
            status = false
        }// If no SD card
        return status
    }

    /**
     * Create a new file object from two file paths.
     *
     * @param file1            Base file path
     * @param file2            Remaining file path
     * @return                File object
     */
    private fun constructFilePaths(file1: String, file2: String): File {
        val newPath: File
        if (file2.startsWith(file1)) {
            newPath = File(file2)
        } else {
            newPath = File("$file1/$file2")
        }
        return newPath
    }
}
