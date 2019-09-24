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

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import org.apache.cordova.CordovaResourceApi
import org.json.JSONException
import org.json.JSONObject

import android.os.Build
import android.os.Environment
import android.util.Base64
import android.net.Uri
import android.content.Context
import android.content.Intent

import java.nio.charset.Charset

class LocalFilesystem(name: String, private val context: Context, resourceApi: CordovaResourceApi, fsRoot: File) : Filesystem(Uri.fromFile(fsRoot).buildUpon().appendEncodedPath("").build(), name, resourceApi) {

    val freeSpaceInBytes: Long
        @Override
        get() = DirectoryManager.getFreeSpaceInBytes(rootUri.getPath())

    fun filesystemPathForFullPath(fullPath: String): String {
        return File(rootUri.getPath(), fullPath).toString()
    }

    @Override
    fun filesystemPathForURL(url: LocalFilesystemURL): String {
        return filesystemPathForFullPath(url.path)
    }

    private fun fullPathForFilesystemPath(absolutePath: String?): String? {
        return if (absolutePath != null && absolutePath.startsWith(rootUri.getPath())) {
            absolutePath.substring(rootUri.getPath().length() - 1)
        } else null
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
        if (f.isDirectory()) {
            // Add trailing / for directories.
            b.appendEncodedPath("")
        }
        return LocalFilesystemURL.parse(b.build())
    }

    @Override
    fun URLforFilesystemPath(path: String): LocalFilesystemURL {
        return localUrlforFullPath(fullPathForFilesystemPath(path))
    }

    @Override
    @Throws(FileExistsException::class, IOException::class, TypeMismatchException::class, EncodingException::class, JSONException::class)
    fun getFileForLocalURL(inputURL: LocalFilesystemURL,
                           path: String, options: JSONObject?, directory: Boolean): JSONObject {
        var path = path
        var create = false
        var exclusive = false

        if (options != null) {
            create = options!!.optBoolean("create")
            if (create) {
                exclusive = options!!.optBoolean("exclusive")
            }
        }

        // Check for a ":" character in the file to line up with BB and iOS
        if (path.contains(":")) {
            throw EncodingException("This path has an invalid \":\" in it.")
        }

        val requestedURL: LocalFilesystemURL

        // Check whether the supplied path is absolute or relative
        if (directory && !path.endsWith("/")) {
            path += "/"
        }
        if (path.startsWith("/")) {
            requestedURL = localUrlforFullPath(normalizePath(path))
        } else {
            requestedURL = localUrlforFullPath(normalizePath(inputURL.path + "/" + path))
        }

        val fp = File(this.filesystemPathForURL(requestedURL))

        if (create) {
            if (exclusive && fp.exists()) {
                throw FileExistsException("create/exclusive fails")
            }
            if (directory) {
                fp.mkdir()
            } else {
                fp.createNewFile()
            }
            if (!fp.exists()) {
                throw FileExistsException("create fails")
            }
        } else {
            if (!fp.exists()) {
                throw FileNotFoundException("path does not exist")
            }
            if (directory) {
                if (fp.isFile()) {
                    throw TypeMismatchException("path doesn't exist or is file")
                }
            } else {
                if (fp.isDirectory()) {
                    throw TypeMismatchException("path doesn't exist or is directory")
                }
            }
        }

        // Return the directory
        return makeEntryForURL(requestedURL)
    }

    @Override
    @Throws(InvalidModificationException::class)
    fun removeFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean {

        val fp = File(filesystemPathForURL(inputURL))

        // You can't delete a directory that is not empty
        if (fp.isDirectory() && fp.list().length > 0) {
            throw InvalidModificationException("You can't delete a directory that is not empty.")
        }

        return fp.delete()
    }

    @Override
    fun exists(inputURL: LocalFilesystemURL): Boolean {
        val fp = File(filesystemPathForURL(inputURL))
        return fp.exists()
    }

    @Override
    @Throws(FileExistsException::class)
    fun recursiveRemoveFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean {
        val directory = File(filesystemPathForURL(inputURL))
        return removeDirRecursively(directory)
    }

    @Throws(FileExistsException::class)
    protected fun removeDirRecursively(directory: File): Boolean {
        if (directory.isDirectory()) {
            for (file in directory.listFiles()) {
                removeDirRecursively(file)
            }
        }

        return if (!directory.delete()) {
            throw FileExistsException("could not delete: " + directory.getName())
        } else {
            true
        }
    }

    @Override
    @Throws(FileNotFoundException::class)
    fun listChildren(inputURL: LocalFilesystemURL): Array<LocalFilesystemURL>? {
        val fp = File(filesystemPathForURL(inputURL))

        if (!fp.exists()) {
            // The directory we are listing doesn't exist so we should fail.
            throw FileNotFoundException()
        }

        val files = fp.listFiles()
                ?: // inputURL is a directory
                return null
        val entries = arrayOfNulls<LocalFilesystemURL>(files.size)
        for (i in files.indices) {
            entries[i] = URLforFilesystemPath(files[i].getPath())
        }

        return entries
    }

    @Override
    @Throws(FileNotFoundException::class)
    fun getFileMetadataForLocalURL(inputURL: LocalFilesystemURL): JSONObject? {
        val file = File(filesystemPathForURL(inputURL))

        if (!file.exists()) {
            throw FileNotFoundException("File at " + inputURL.uri + " does not exist.")
        }

        val metadata = JSONObject()
        try {
            // Ensure that directories report a size of 0
            metadata.put("size", if (file.isDirectory()) 0 else file.length())
            metadata.put("type", resourceApi.getMimeType(Uri.fromFile(file)))
            metadata.put("name", file.getName())
            metadata.put("fullPath", inputURL.path)
            metadata.put("lastModifiedDate", file.lastModified())
        } catch (e: JSONException) {
            return null
        }

        return metadata
    }

    @Throws(IOException::class, InvalidModificationException::class, NoModificationAllowedException::class)
    private fun copyFile(srcFs: Filesystem, srcURL: LocalFilesystemURL, destFile: File, move: Boolean) {
        if (move) {
            val realSrcPath = srcFs.filesystemPathForURL(srcURL)
            if (realSrcPath != null) {
                val srcFile = File(realSrcPath)
                if (srcFile.renameTo(destFile)) {
                    return
                }
                // Trying to rename the file failed.  Possibly because we moved across file system on the device.
            }
        }

        val offr = resourceApi.openForRead(srcFs.toNativeUri(srcURL))
        copyResource(offr, FileOutputStream(destFile))

        if (move) {
            srcFs.removeFileAtLocalURL(srcURL)
        }
    }

    @Throws(IOException::class, NoModificationAllowedException::class, InvalidModificationException::class, FileExistsException::class)
    private fun copyDirectory(srcFs: Filesystem, srcURL: LocalFilesystemURL, dstDir: File, move: Boolean) {
        if (move) {
            val realSrcPath = srcFs.filesystemPathForURL(srcURL)
            if (realSrcPath != null) {
                val srcDir = File(realSrcPath)
                // If the destination directory already exists and is empty then delete it.  This is according to spec.
                if (dstDir.exists()) {
                    if (dstDir.list().length > 0) {
                        throw InvalidModificationException("directory is not empty")
                    }
                    dstDir.delete()
                }
                // Try to rename the directory
                if (srcDir.renameTo(dstDir)) {
                    return
                }
                // Trying to rename the file failed.  Possibly because we moved across file system on the device.
            }
        }

        if (dstDir.exists()) {
            if (dstDir.list().length > 0) {
                throw InvalidModificationException("directory is not empty")
            }
        } else {
            if (!dstDir.mkdir()) {
                // If we can't create the directory then fail
                throw NoModificationAllowedException("Couldn't create the destination directory")
            }
        }

        val children = srcFs.listChildren(srcURL)
        for (childLocalUrl in children) {
            val target = File(dstDir, File(childLocalUrl.path).getName())
            if (childLocalUrl.isDirectory) {
                copyDirectory(srcFs, childLocalUrl, target, false)
            } else {
                copyFile(srcFs, childLocalUrl, target, false)
            }
        }

        if (move) {
            srcFs.recursiveRemoveFileAtLocalURL(srcURL)
        }
    }

    @Override
    @Throws(IOException::class, InvalidModificationException::class, JSONException::class, NoModificationAllowedException::class, FileExistsException::class)
    fun copyFileToURL(destURL: LocalFilesystemURL, newName: String,
                      srcFs: Filesystem, srcURL: LocalFilesystemURL, move: Boolean): JSONObject {

        // Check to see if the destination directory exists
        val newParent = this.filesystemPathForURL(destURL)
        val destinationDir = File(newParent)
        if (!destinationDir.exists()) {
            // The destination does not exist so we should fail.
            throw FileNotFoundException("The source does not exist")
        }

        // Figure out where we should be copying to
        val destinationURL = makeDestinationURL(newName, srcURL, destURL, srcURL.isDirectory)

        val dstNativeUri = toNativeUri(destinationURL)
        val srcNativeUri = srcFs.toNativeUri(srcURL)
        // Check to see if source and destination are the same file
        if (dstNativeUri.equals(srcNativeUri)) {
            throw InvalidModificationException("Can't copy onto itself")
        }

        if (move && !srcFs.canRemoveFileAtLocalURL(srcURL)) {
            throw InvalidModificationException("Source URL is read-only (cannot move)")
        }

        val destFile = File(dstNativeUri.getPath())
        if (destFile.exists()) {
            if (!srcURL.isDirectory && destFile.isDirectory()) {
                throw InvalidModificationException("Can't copy/move a file to an existing directory")
            } else if (srcURL.isDirectory && destFile.isFile()) {
                throw InvalidModificationException("Can't copy/move a directory to an existing file")
            }
        }

        if (srcURL.isDirectory) {
            // E.g. Copy /sdcard/myDir to /sdcard/myDir/backup
            if (dstNativeUri.toString().startsWith(srcNativeUri.toString() + '/')) {
                throw InvalidModificationException("Can't copy directory into itself")
            }
            copyDirectory(srcFs, srcURL, destFile, move)
        } else {
            copyFile(srcFs, srcURL, destFile, move)
        }
        return makeEntryForURL(destinationURL)
    }

    @Override
    @Throws(IOException::class, NoModificationAllowedException::class)
    fun writeToFileAtURL(inputURL: LocalFilesystemURL, data: String,
                         offset: Int, isBinary: Boolean): Long {

        var append = false
        if (offset > 0) {
            this.truncateFileAtURL(inputURL, offset.toLong())
            append = true
        }

        val rawData: ByteArray
        if (isBinary) {
            rawData = Base64.decode(data, Base64.DEFAULT)
        } else {
            rawData = data.getBytes(Charset.defaultCharset())
        }
        val `in` = ByteArrayInputStream(rawData)
        try {
            val buff = ByteArray(rawData.size)
            val absolutePath = filesystemPathForURL(inputURL)
            val out = FileOutputStream(absolutePath, append)
            try {
                `in`.read(buff, 0, buff.size)
                out.write(buff, 0, rawData.size)
                out.flush()
            } finally {
                // Always close the output
                out.close()
            }
            if (isPublicDirectory(absolutePath)) {
                broadcastNewFile(Uri.fromFile(File(absolutePath)))
            }
        } catch (e: NullPointerException) {
            // This is a bug in the Android implementation of the Java Stack
            val realException = NoModificationAllowedException(inputURL.toString())
            realException.initCause(e)
            throw realException
        }

        return rawData.size.toLong()
    }

    private fun isPublicDirectory(absolutePath: String): Boolean {
        // TODO: should expose a way to scan app's private files (maybe via a flag).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Lollipop has a bug where SD cards are null.
            for (f in context.getExternalMediaDirs()) {
                if (f != null && absolutePath.startsWith(f!!.getAbsolutePath())) {
                    return true
                }
            }
        }

        val extPath = Environment.getExternalStorageDirectory().getAbsolutePath()
        return absolutePath.startsWith(extPath)
    }

    /**
     * Send broadcast of new file so files appear over MTP
     */
    private fun broadcastNewFile(nativeUri: Uri) {
        val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, nativeUri)
        context.sendBroadcast(intent)
    }

    @Override
    @Throws(IOException::class)
    fun truncateFileAtURL(inputURL: LocalFilesystemURL, size: Long): Long {
        val file = File(filesystemPathForURL(inputURL))

        if (!file.exists()) {
            throw FileNotFoundException("File at " + inputURL.uri + " does not exist.")
        }

        val raf = RandomAccessFile(filesystemPathForURL(inputURL), "rw")
        try {
            if (raf.length() >= size) {
                val channel = raf.getChannel()
                channel.truncate(size)
                return size
            }

            return raf.length()
        } finally {
            raf.close()
        }


    }

    @Override
    fun canRemoveFileAtLocalURL(inputURL: LocalFilesystemURL): Boolean {
        val path = filesystemPathForURL(inputURL)
        val file = File(path)
        return file.exists()
    }

    // This is a copy & paste from CordovaResource API that is required since CordovaResourceApi
    // has a bug pre-4.0.0.
    // TODO: Once cordova-android@4.0.0 is released, delete this copy and make the plugin depend on
    // 4.0.0 with an engine tag.
    @Throws(IOException::class)
    private fun copyResource(input: CordovaResourceApi.OpenForReadResult, outputStream: OutputStream?) {
        try {
            val inputStream = input.inputStream
            if (inputStream is FileInputStream && outputStream is FileOutputStream) {
                val inChannel = (input.inputStream as FileInputStream).getChannel()
                val outChannel = (outputStream as FileOutputStream).getChannel()
                var offset: Long = 0
                val length = input.length
                if (input.assetFd != null) {
                    offset = input.assetFd.getStartOffset()
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
}
