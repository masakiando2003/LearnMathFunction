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

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64

import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.apache.cordova.LOG
import org.apache.cordova.PermissionHelper
import org.apache.cordova.PluginResult

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.security.Permission
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet

/**
 * This class provides file and directory services to JavaScript.
 */
class FileUtils : CordovaPlugin() {

    private var configured = false

    private var pendingRequests: PendingRequests? = null


    /*
     * We need both read and write when accessing the storage, I think.
     */

    private val permissions = arrayOf<String>(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private var filesystems: ArrayList<Filesystem>? = null

    private interface FileOp {
        @Throws(Exception::class)
        fun run(args: JSONArray)
    }

    fun registerFilesystem(fs: Filesystem?) {
        if (fs != null && filesystemForName(fs!!.name) == null) {
            this.filesystems!!.add(fs)
        }
    }

    private fun filesystemForName(name: String): Filesystem? {
        for (fs in filesystems!!) {
            if (fs != null && fs!!.name != null && fs!!.name.equals(name)) {
                return fs
            }
        }
        return null
    }

    protected fun getExtraFileSystemsPreference(activity: Activity): Array<String> {
        val fileSystemsStr = preferences.getString("androidextrafilesystems", "files,files-external,documents,sdcard,cache,cache-external,assets,root")
        return fileSystemsStr.split(",")
    }

    protected fun registerExtraFileSystems(filesystems: Array<String>, availableFileSystems: HashMap<String, String>) {
        val installedFileSystems = HashSet<String>()

        /* Register filesystems in order */
        for (fsName in filesystems) {
            if (!installedFileSystems.contains(fsName)) {
                val fsRoot = availableFileSystems.get(fsName)
                if (fsRoot != null) {
                    val newRoot = File(fsRoot)
                    if (newRoot.mkdirs() || newRoot.isDirectory()) {
                        registerFilesystem(LocalFilesystem(fsName, webView.getContext(), webView.getResourceApi(), newRoot))
                        installedFileSystems.add(fsName)
                    } else {
                        LOG.d(LOG_TAG, "Unable to create root dir for filesystem \"$fsName\", skipping")
                    }
                } else {
                    LOG.d(LOG_TAG, "Unrecognized extra filesystem identifier: $fsName")
                }
            }
        }
    }

    protected fun getAvailableFileSystems(activity: Activity): HashMap<String, String> {
        val context = activity.getApplicationContext()
        val availableFileSystems = HashMap<String, String>()

        availableFileSystems.put("files", context.getFilesDir().getAbsolutePath())
        availableFileSystems.put("documents", File(context.getFilesDir(), "Documents").getAbsolutePath())
        availableFileSystems.put("cache", context.getCacheDir().getAbsolutePath())
        availableFileSystems.put("root", "/")
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            try {
                availableFileSystems.put("files-external", context.getExternalFilesDir(null).getAbsolutePath())
                availableFileSystems.put("sdcard", Environment.getExternalStorageDirectory().getAbsolutePath())
                availableFileSystems.put("cache-external", context.getExternalCacheDir().getAbsolutePath())
            } catch (e: NullPointerException) {
                LOG.d(LOG_TAG, "External storage unavailable, check to see if USB Mass Storage Mode is on")
            }

        }

        return availableFileSystems
    }

    @Override
    fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)
        this.filesystems = ArrayList<Filesystem>()
        this.pendingRequests = PendingRequests()

        var tempRoot: String? = null
        var persistentRoot: String? = null

        val activity = cordova.getActivity()
        val packageName = activity.getPackageName()

        val location = preferences.getString("androidpersistentfilelocation", "internal")

        tempRoot = activity.getCacheDir().getAbsolutePath()
        if ("internal".equalsIgnoreCase(location)) {
            persistentRoot = activity.getFilesDir().getAbsolutePath() + "/files/"
            this.configured = true
        } else if ("compatibility".equalsIgnoreCase(location)) {
            /*
    		 *  Fall-back to compatibility mode -- this is the logic implemented in
    		 *  earlier versions of this plugin, and should be maintained here so
    		 *  that apps which were originally deployed with older versions of the
    		 *  plugin can continue to provide access to files stored under those
    		 *  versions.
    		 */
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                persistentRoot = Environment.getExternalStorageDirectory().getAbsolutePath()
                tempRoot = Environment.getExternalStorageDirectory().getAbsolutePath() +
                        "/Android/data/" + packageName + "/cache/"
            } else {
                persistentRoot = "/data/data/$packageName"
            }
            this.configured = true
        }

        if (this.configured) {
            // Create the directories if they don't exist.
            val tmpRootFile = File(tempRoot)
            val persistentRootFile = File(persistentRoot)
            tmpRootFile.mkdirs()
            persistentRootFile.mkdirs()

            // Register initial filesystems
            // Note: The temporary and persistent filesystems need to be the first two
            // registered, so that they will match window.TEMPORARY and window.PERSISTENT,
            // per spec.
            this.registerFilesystem(LocalFilesystem("temporary", webView.getContext(), webView.getResourceApi(), tmpRootFile))
            this.registerFilesystem(LocalFilesystem("persistent", webView.getContext(), webView.getResourceApi(), persistentRootFile))
            this.registerFilesystem(ContentFilesystem(webView.getContext(), webView.getResourceApi()))
            this.registerFilesystem(AssetFilesystem(webView.getContext().getAssets(), webView.getResourceApi()))

            registerExtraFileSystems(getExtraFileSystemsPreference(activity), getAvailableFileSystems(activity))

            // Initialize static plugin reference for deprecated getEntry method
            if (filePlugin == null) {
                FileUtils.filePlugin = this
            }
        } else {
            LOG.e(LOG_TAG, "File plugin configuration error: Please set AndroidPersistentFileLocation in config.xml to one of \"internal\" (for new applications) or \"compatibility\" (for compatibility with previous versions)")
            activity.finish()
        }
    }

    private fun filesystemForURL(localURL: LocalFilesystemURL?): Filesystem? {
        return if (localURL == null) null else filesystemForName(localURL!!.fsName)
    }

    @Override
    fun remapUri(uri: Uri): Uri? {
        // Remap only cdvfile: URLs (not content:).
        if (!LocalFilesystemURL.FILESYSTEM_PROTOCOL.equals(uri.getScheme())) {
            return null
        }
        try {
            val inputURL = LocalFilesystemURL.parse(uri)
            val fs = this.filesystemForURL(inputURL) ?: return null
            val path = fs.filesystemPathForURL(inputURL)
            return if (path != null) {
                Uri.parse("file://" + fs.filesystemPathForURL(inputURL))
            } else null
        } catch (e: IllegalArgumentException) {
            return null
        }

    }

    fun execute(action: String, rawArgs: String, callbackContext: CallbackContext): Boolean {
        if (!configured) {
            callbackContext.sendPluginResult(PluginResult(PluginResult.Status.ERROR, "File plugin is not configured. Please see the README.md file for details on how to update config.xml"))
            return true
        }
        if (action.equals("testSaveLocationExists")) {
            threadhelper(object : FileOp {
                override fun run(args: JSONArray) {
                    val b = DirectoryManager.testSaveLocationExists()
                    callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, b))
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("getFreeDiskSpace")) {
            threadhelper(object : FileOp {
                override fun run(args: JSONArray) {
                    // The getFreeDiskSpace plugin API is not documented, but some apps call it anyway via exec().
                    // For compatibility it always returns free space in the primary external storage, and
                    // does NOT fallback to internal store if external storage is unavailable.
                    val l = DirectoryManager.getFreeExternalStorageSpace()
                    callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, l))
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("testFileExists")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val b = DirectoryManager.testFileExists(fname)
                    callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, b))
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("testDirectoryExists")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val b = DirectoryManager.testFileExists(fname)
                    callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, b))
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("readAsText")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, MalformedURLException::class)
                override fun run(args: JSONArray) {
                    val encoding = args.getString(1)
                    val start = args.getInt(2)
                    val end = args.getInt(3)
                    val fname = args.getString(0)
                    readFileAs(fname, start, end, callbackContext, encoding, PluginResult.MESSAGE_TYPE_STRING)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("readAsDataURL")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, MalformedURLException::class)
                override fun run(args: JSONArray) {
                    val start = args.getInt(1)
                    val end = args.getInt(2)
                    val fname = args.getString(0)
                    readFileAs(fname, start, end, callbackContext, null, -1)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("readAsArrayBuffer")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, MalformedURLException::class)
                override fun run(args: JSONArray) {
                    val start = args.getInt(1)
                    val end = args.getInt(2)
                    val fname = args.getString(0)
                    readFileAs(fname, start, end, callbackContext, null, PluginResult.MESSAGE_TYPE_ARRAYBUFFER)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("readAsBinaryString")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, MalformedURLException::class)
                override fun run(args: JSONArray) {
                    val start = args.getInt(1)
                    val end = args.getInt(2)
                    val fname = args.getString(0)
                    readFileAs(fname, start, end, callbackContext, null, PluginResult.MESSAGE_TYPE_BINARYSTRING)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("write")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, FileNotFoundException::class, IOException::class, NoModificationAllowedException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val nativeURL = resolveLocalFileSystemURI(fname).getString("nativeURL")
                    val data = args.getString(1)
                    val offset = args.getInt(2)
                    val isBinary = args.getBoolean(3)

                    if (needPermission(nativeURL, WRITE)) {
                        getWritePermission(rawArgs, ACTION_WRITE, callbackContext)
                    } else {
                        val fileSize = write(fname, data, offset, isBinary)
                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, fileSize))
                    }

                }
            }, rawArgs, callbackContext)
        } else if (action.equals("truncate")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, FileNotFoundException::class, IOException::class, NoModificationAllowedException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val offset = args.getInt(1)
                    val fileSize = truncateFile(fname, offset.toLong())
                    callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, fileSize))
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("requestAllFileSystems")) {
            threadhelper(object : FileOp {
                @Throws(IOException::class, JSONException::class)
                override fun run(args: JSONArray) {
                    callbackContext.success(requestAllFileSystems())
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("requestAllPaths")) {
            cordova.getThreadPool().execute(
                    object : Runnable() {
                        fun run() {
                            try {
                                callbackContext.success(requestAllPaths())
                            } catch (e: JSONException) {
                                // TODO Auto-generated catch block
                                e.printStackTrace()
                            }

                        }
                    }
            )
        } else if (action.equals("requestFileSystem")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class)
                override fun run(args: JSONArray) {
                    val fstype = args.getInt(0)
                    val requiredSize = args.optLong(1)
                    requestFileSystem(fstype, requiredSize, callbackContext)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("resolveLocalFileSystemURI")) {
            threadhelper(object : FileOp {
                @Throws(IOException::class, JSONException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val obj = resolveLocalFileSystemURI(fname)
                    callbackContext.success(obj)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("getFileMetadata")) {
            threadhelper(object : FileOp {
                @Throws(FileNotFoundException::class, JSONException::class, MalformedURLException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val obj = getFileMetadata(fname)
                    callbackContext.success(obj)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("getParent")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, IOException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val obj = getParent(fname)
                    callbackContext.success(obj)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("getDirectory")) {
            threadhelper(object : FileOp {
                @Throws(FileExistsException::class, IOException::class, TypeMismatchException::class, EncodingException::class, JSONException::class)
                override fun run(args: JSONArray) {
                    val dirname = args.getString(0)
                    val path = args.getString(1)
                    val nativeURL = resolveLocalFileSystemURI(dirname).getString("nativeURL")
                    val containsCreate = if (args.isNull(2)) false else args.getJSONObject(2).optBoolean("create", false)

                    if (containsCreate && needPermission(nativeURL, WRITE)) {
                        getWritePermission(rawArgs, ACTION_GET_DIRECTORY, callbackContext)
                    } else if (!containsCreate && needPermission(nativeURL, READ)) {
                        getReadPermission(rawArgs, ACTION_GET_DIRECTORY, callbackContext)
                    } else {
                        val obj = getFile(dirname, path, args.optJSONObject(2), true)
                        callbackContext.success(obj)
                    }
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("getFile")) {
            threadhelper(object : FileOp {
                @Throws(FileExistsException::class, IOException::class, TypeMismatchException::class, EncodingException::class, JSONException::class)
                override fun run(args: JSONArray) {
                    val dirname = args.getString(0)
                    val path = args.getString(1)
                    val nativeURL = resolveLocalFileSystemURI(dirname).getString("nativeURL")
                    val containsCreate = if (args.isNull(2)) false else args.getJSONObject(2).optBoolean("create", false)

                    if (containsCreate && needPermission(nativeURL, WRITE)) {
                        getWritePermission(rawArgs, ACTION_GET_FILE, callbackContext)
                    } else if (!containsCreate && needPermission(nativeURL, READ)) {
                        getReadPermission(rawArgs, ACTION_GET_FILE, callbackContext)
                    } else {
                        val obj = getFile(dirname, path, args.optJSONObject(2), false)
                        callbackContext.success(obj)
                    }
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("remove")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, NoModificationAllowedException::class, InvalidModificationException::class, MalformedURLException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val success = remove(fname)
                    if (success) {
                        callbackContext.success()
                    } else {
                        callbackContext.error(FileUtils.NO_MODIFICATION_ALLOWED_ERR)
                    }
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("removeRecursively")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, FileExistsException::class, MalformedURLException::class, NoModificationAllowedException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val success = removeRecursively(fname)
                    if (success) {
                        callbackContext.success()
                    } else {
                        callbackContext.error(FileUtils.NO_MODIFICATION_ALLOWED_ERR)
                    }
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("moveTo")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, NoModificationAllowedException::class, IOException::class, InvalidModificationException::class, EncodingException::class, FileExistsException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val newParent = args.getString(1)
                    val newName = args.getString(2)
                    val entry = transferTo(fname, newParent, newName, true)
                    callbackContext.success(entry)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("copyTo")) {
            threadhelper(object : FileOp {
                @Throws(JSONException::class, NoModificationAllowedException::class, IOException::class, InvalidModificationException::class, EncodingException::class, FileExistsException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val newParent = args.getString(1)
                    val newName = args.getString(2)
                    val entry = transferTo(fname, newParent, newName, false)
                    callbackContext.success(entry)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("readEntries")) {
            threadhelper(object : FileOp {
                @Throws(FileNotFoundException::class, JSONException::class, MalformedURLException::class)
                override fun run(args: JSONArray) {
                    val fname = args.getString(0)
                    val entries = readEntries(fname)
                    callbackContext.success(entries)
                }
            }, rawArgs, callbackContext)
        } else if (action.equals("_getLocalFilesystemPath")) {
            // Internal method for testing: Get the on-disk location of a local filesystem url.
            // [Currently used for testing file-transfer]
            threadhelper(object : FileOp {
                @Throws(FileNotFoundException::class, JSONException::class, MalformedURLException::class)
                override fun run(args: JSONArray) {
                    val localURLstr = args.getString(0)
                    val fname = filesystemPathForURL(localURLstr)
                    callbackContext.success(fname)
                }
            }, rawArgs, callbackContext)
        } else {
            return false
        }
        return true
    }

    private fun getReadPermission(rawArgs: String, action: Int, callbackContext: CallbackContext) {
        val requestCode = pendingRequests!!.createRequest(rawArgs, action, callbackContext)
        PermissionHelper.requestPermission(this, requestCode, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun getWritePermission(rawArgs: String, action: Int, callbackContext: CallbackContext) {
        val requestCode = pendingRequests!!.createRequest(rawArgs, action, callbackContext)
        PermissionHelper.requestPermission(this, requestCode, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun hasReadPermission(): Boolean {
        return PermissionHelper.hasPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasWritePermission(): Boolean {
        return PermissionHelper.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @Throws(JSONException::class)
    private fun needPermission(nativeURL: String, permissionType: Int): Boolean {
        val j = requestAllPaths()
        val allowedStorageDirectories = ArrayList<String>()
        allowedStorageDirectories.add(j.getString("applicationDirectory"))
        allowedStorageDirectories.add(j.getString("applicationStorageDirectory"))
        if (j.has("externalApplicationStorageDirectory")) {
            allowedStorageDirectories.add(j.getString("externalApplicationStorageDirectory"))
        }

        if (permissionType == READ && hasReadPermission()) {
            return false
        } else if (permissionType == WRITE && hasWritePermission()) {
            return false
        }

        // Permission required if the native url lies outside the allowed storage directories
        for (directory in allowedStorageDirectories) {
            if (nativeURL.startsWith(directory)) {
                return false
            }
        }
        return true
    }


    fun resolveNativeUri(nativeUri: Uri): LocalFilesystemURL? {
        var localURL: LocalFilesystemURL? = null

        // Try all installed filesystems. Return the best matching URL
        // (determined by the shortest resulting URL)
        for (fs in filesystems!!) {
            val url = fs.toLocalUri(nativeUri)
            if (url != null) {
                // A shorter fullPath implies that the filesystem is a better
                // match for the local path than the previous best.
                if (localURL == null || url!!.uri.toString().length() < localURL!!.toString().length()) {
                    localURL = url
                }
            }
        }
        return localURL
    }

    /*
     * These two native-only methods can be used by other plugins to translate between
     * device file system paths and URLs. By design, there is no direct JavaScript
     * interface to these methods.
     */

    @Throws(MalformedURLException::class)
    fun filesystemPathForURL(localURLstr: String): String {
        try {
            val inputURL = LocalFilesystemURL.parse(localURLstr)
            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")
            return fs.filesystemPathForURL(inputURL)
        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        }

    }

    fun filesystemURLforLocalPath(localPath: String): LocalFilesystemURL? {
        var localURL: LocalFilesystemURL? = null
        var shortestFullPath = 0

        // Try all installed filesystems. Return the best matching URL
        // (determined by the shortest resulting URL)
        for (fs in filesystems!!) {
            val url = fs.URLforFilesystemPath(localPath)
            if (url != null) {
                // A shorter fullPath implies that the filesystem is a better
                // match for the local path than the previous best.
                if (localURL == null || url!!.path.length() < shortestFullPath) {
                    localURL = url
                    shortestFullPath = url!!.path.length()
                }
            }
        }
        return localURL
    }


    /* helper to execute functions async and handle the result codes
     *
     */
    private fun threadhelper(f: FileOp, rawArgs: String, callbackContext: CallbackContext) {
        cordova.getThreadPool().execute(object : Runnable() {
            fun run() {
                try {
                    val args = JSONArray(rawArgs)
                    f.run(args)
                } catch (e: Exception) {
                    if (e is EncodingException) {
                        callbackContext.error(FileUtils.ENCODING_ERR)
                    } else if (e is FileNotFoundException) {
                        callbackContext.error(FileUtils.NOT_FOUND_ERR)
                    } else if (e is FileExistsException) {
                        callbackContext.error(FileUtils.PATH_EXISTS_ERR)
                    } else if (e is NoModificationAllowedException) {
                        callbackContext.error(FileUtils.NO_MODIFICATION_ALLOWED_ERR)
                    } else if (e is InvalidModificationException) {
                        callbackContext.error(FileUtils.INVALID_MODIFICATION_ERR)
                    } else if (e is MalformedURLException) {
                        callbackContext.error(FileUtils.ENCODING_ERR)
                    } else if (e is IOException) {
                        callbackContext.error(FileUtils.INVALID_MODIFICATION_ERR)
                    } else if (e is EncodingException) {
                        callbackContext.error(FileUtils.ENCODING_ERR)
                    } else if (e is TypeMismatchException) {
                        callbackContext.error(FileUtils.TYPE_MISMATCH_ERR)
                    } else if (e is JSONException) {
                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.JSON_EXCEPTION))
                    } else if (e is SecurityException) {
                        callbackContext.error(FileUtils.SECURITY_ERR)
                    } else {
                        e.printStackTrace()
                        callbackContext.error(FileUtils.UNKNOWN_ERR)
                    }
                }

            }
        })
    }

    /**
     * Allows the user to look up the Entry for a file or directory referred to by a local URI.
     *
     * @param uriString of the file/directory to look up
     * @return a JSONObject representing a Entry from the filesystem
     * @throws MalformedURLException if the url is not valid
     * @throws FileNotFoundException if the file does not exist
     * @throws IOException if the user can't read the file
     * @throws JSONException
     */
    @Throws(IOException::class, JSONException::class)
    private fun resolveLocalFileSystemURI(uriString: String?): JSONObject {
        if (uriString == null) {
            throw MalformedURLException("Unrecognized filesystem URL")
        }
        val uri = Uri.parse(uriString)
        var isNativeUri = false

        var inputURL = LocalFilesystemURL.parse(uri)
        if (inputURL == null) {
            /* Check for file://, content:// urls */
            inputURL = resolveNativeUri(uri)
            isNativeUri = true
        }

        try {
            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")
            if (fs.exists(inputURL)) {
                if (!isNativeUri) {
                    // If not already resolved as native URI, resolve to a native URI and back to
                    // fix the terminating slash based on whether the entry is a directory or file.
                    inputURL = fs.toLocalUri(fs.toNativeUri(inputURL))
                }

                return fs.getEntryForLocalURL(inputURL)
            }
        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        }

        throw FileNotFoundException()
    }

    /**
     * Read the list of files from this directory.
     *
     * @return a JSONArray containing JSONObjects that represent Entry objects.
     * @throws FileNotFoundException if the directory is not found.
     * @throws JSONException
     * @throws MalformedURLException
     */
    @Throws(FileNotFoundException::class, JSONException::class, MalformedURLException::class)
    private fun readEntries(baseURLstr: String): JSONArray {
        try {
            val inputURL = LocalFilesystemURL.parse(baseURLstr)
            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")
            return fs.readEntriesAtLocalURL(inputURL)

        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        }

    }

    /**
     * A setup method that handles the move/copy of files/directories
     *
     * @param newName for the file directory to be called, if null use existing file name
     * @param move if false do a copy, if true do a move
     * @return a Entry object
     * @throws NoModificationAllowedException
     * @throws IOException
     * @throws InvalidModificationException
     * @throws EncodingException
     * @throws JSONException
     * @throws FileExistsException
     */
    @Throws(JSONException::class, NoModificationAllowedException::class, IOException::class, InvalidModificationException::class, EncodingException::class, FileExistsException::class)
    private fun transferTo(srcURLstr: String?, destURLstr: String?, newName: String?, move: Boolean): JSONObject {
        if (srcURLstr == null || destURLstr == null) {
            // either no source or no destination provided
            throw FileNotFoundException()
        }

        val srcURL = LocalFilesystemURL.parse(srcURLstr)
        val destURL = LocalFilesystemURL.parse(destURLstr)

        val srcFs = this.filesystemForURL(srcURL)
        val destFs = this.filesystemForURL(destURL)

        // Check for invalid file name
        if (newName != null && newName.contains(":")) {
            throw EncodingException("Bad file name")
        }

        return destFs!!.copyFileToURL(destURL, newName, srcFs, srcURL, move)
    }

    /**
     * Deletes a directory and all of its contents, if any. In the event of an error
     * [e.g. trying to delete a directory that contains a file that cannot be removed],
     * some of the contents of the directory may be deleted.
     * It is an error to attempt to delete the root directory of a filesystem.
     *
     * @return a boolean representing success of failure
     * @throws FileExistsException
     * @throws NoModificationAllowedException
     * @throws MalformedURLException
     */
    @Throws(FileExistsException::class, NoModificationAllowedException::class, MalformedURLException::class)
    private fun removeRecursively(baseURLstr: String): Boolean {
        try {
            val inputURL = LocalFilesystemURL.parse(baseURLstr)
            // You can't delete the root directory.
            if ("".equals(inputURL.path) || "/".equals(inputURL.path)) {
                throw NoModificationAllowedException("You can't delete the root directory")
            }

            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")
            return fs.recursiveRemoveFileAtLocalURL(inputURL)

        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        }

    }


    /**
     * Deletes a file or directory. It is an error to attempt to delete a directory that is not empty.
     * It is an error to attempt to delete the root directory of a filesystem.
     *
     * @return a boolean representing success of failure
     * @throws NoModificationAllowedException
     * @throws InvalidModificationException
     * @throws MalformedURLException
     */
    @Throws(NoModificationAllowedException::class, InvalidModificationException::class, MalformedURLException::class)
    private fun remove(baseURLstr: String): Boolean {
        try {
            val inputURL = LocalFilesystemURL.parse(baseURLstr)
            // You can't delete the root directory.
            if ("".equals(inputURL.path) || "/".equals(inputURL.path)) {

                throw NoModificationAllowedException("You can't delete the root directory")
            }

            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")
            return fs.removeFileAtLocalURL(inputURL)

        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        }

    }

    /**
     * Creates or looks up a file.
     *
     * @param baseURLstr base directory
     * @param path file/directory to lookup or create
     * @param options specify whether to create or not
     * @param directory if true look up directory, if false look up file
     * @return a Entry object
     * @throws FileExistsException
     * @throws IOException
     * @throws TypeMismatchException
     * @throws EncodingException
     * @throws JSONException
     */
    @Throws(FileExistsException::class, IOException::class, TypeMismatchException::class, EncodingException::class, JSONException::class)
    private fun getFile(baseURLstr: String, path: String, options: JSONObject, directory: Boolean): JSONObject {
        try {
            val inputURL = LocalFilesystemURL.parse(baseURLstr)
            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")
            return fs.getFileForLocalURL(inputURL, path, options, directory)

        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        }

    }

    /**
     * Look up the parent DirectoryEntry containing this Entry.
     * If this Entry is the root of its filesystem, its parent is itself.
     */
    @Throws(JSONException::class, IOException::class)
    private fun getParent(baseURLstr: String): JSONObject {
        try {
            val inputURL = LocalFilesystemURL.parse(baseURLstr)
            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")
            return fs.getParentForLocalURL(inputURL)

        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        }

    }

    /**
     * Returns a File that represents the current state of the file that this FileEntry represents.
     *
     * @return returns a JSONObject represent a W3C File object
     */
    @Throws(FileNotFoundException::class, JSONException::class, MalformedURLException::class)
    private fun getFileMetadata(baseURLstr: String): JSONObject {
        try {
            val inputURL = LocalFilesystemURL.parse(baseURLstr)
            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")
            return fs.getFileMetadataForLocalURL(inputURL)

        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        }

    }

    /**
     * Requests a filesystem in which to store application data.
     *
     * @param type of file system requested
     * @param requiredSize required free space in the file system in bytes
     * @param callbackContext context for returning the result or error
     * @throws JSONException
     */
    @Throws(JSONException::class)
    private fun requestFileSystem(type: Int, requiredSize: Long, callbackContext: CallbackContext) {
        var rootFs: Filesystem? = null
        try {
            rootFs = this.filesystems!!.get(type)
        } catch (e: ArrayIndexOutOfBoundsException) {
            // Pass null through
        }

        if (rootFs == null) {
            callbackContext.sendPluginResult(PluginResult(PluginResult.Status.ERROR, FileUtils.NOT_FOUND_ERR))
        } else {
            // If a nonzero required size was specified, check that the retrieved filesystem has enough free space.
            var availableSize: Long = 0
            if (requiredSize > 0) {
                availableSize = rootFs!!.getFreeSpaceInBytes()
            }

            if (availableSize < requiredSize) {
                callbackContext.sendPluginResult(PluginResult(PluginResult.Status.ERROR, FileUtils.QUOTA_EXCEEDED_ERR))
            } else {
                val fs = JSONObject()
                fs.put("name", rootFs!!.name)
                fs.put("root", rootFs!!.getRootEntry())
                callbackContext.success(fs)
            }
        }
    }

    /**
     * Requests a filesystem in which to store application data.
     *
     * @return a JSONObject representing the file system
     */
    @Throws(IOException::class, JSONException::class)
    private fun requestAllFileSystems(): JSONArray {
        val ret = JSONArray()
        for (fs in filesystems!!) {
            ret.put(fs.getRootEntry())
        }
        return ret
    }

    @Throws(JSONException::class)
    private fun requestAllPaths(): JSONObject {
        val context = cordova.getActivity()
        val ret = JSONObject()
        ret.put("applicationDirectory", "file:///android_asset/")
        ret.put("applicationStorageDirectory", toDirUrl(context.getFilesDir().getParentFile()))
        ret.put("dataDirectory", toDirUrl(context.getFilesDir()))
        ret.put("cacheDirectory", toDirUrl(context.getCacheDir()))
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            try {
                ret.put("externalApplicationStorageDirectory", toDirUrl(context.getExternalFilesDir(null).getParentFile()))
                ret.put("externalDataDirectory", toDirUrl(context.getExternalFilesDir(null)))
                ret.put("externalCacheDirectory", toDirUrl(context.getExternalCacheDir()))
                ret.put("externalRootDirectory", toDirUrl(Environment.getExternalStorageDirectory()))
            } catch (e: NullPointerException) {
                /* If external storage is unavailable, context.getExternal* returns null */
                LOG.d(LOG_TAG, "Unable to access these paths, most liklely due to USB storage")
            }

        }
        return ret
    }

    /**
     * Returns a JSON object representing the given File. Internal APIs should be modified
     * to use URLs instead of raw FS paths wherever possible, when interfacing with this plugin.
     *
     * @param file the File to convert
     * @return a JSON representation of the given File
     * @throws JSONException
     */
    @Throws(JSONException::class)
    fun getEntryForFile(file: File): JSONObject? {
        var entry: JSONObject?

        for (fs in filesystems!!) {
            entry = fs.makeEntryForFile(file)
            if (entry != null) {
                return entry
            }
        }
        return null
    }

    /**
     * Read the contents of a file.
     * This is done in a background thread; the result is sent to the callback.
     *
     * @param start             Start position in the file.
     * @param end               End position to stop at (exclusive).
     * @param callbackContext   The context through which to send the result.
     * @param encoding          The encoding to return contents as.  Typical value is UTF-8. (see http://www.iana.org/assignments/character-sets)
     * @param resultType        The desired type of data to send to the callback.
     * @return                  Contents of file.
     */
    @Throws(MalformedURLException::class)
    fun readFileAs(srcURLstr: String, start: Int, end: Int, callbackContext: CallbackContext, encoding: String?, resultType: Int) {
        try {
            val inputURL = LocalFilesystemURL.parse(srcURLstr)
            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")

            fs.readFileAtURL(inputURL, start, end, object : Filesystem.ReadFileCallback() {
                fun handleData(inputStream: InputStream, contentType: String) {
                    try {
                        val os = ByteArrayOutputStream()
                        val BUFFER_SIZE = 8192
                        val buffer = ByteArray(BUFFER_SIZE)

                        while (true) {
                            val bytesRead = inputStream.read(buffer, 0, BUFFER_SIZE)

                            if (bytesRead <= 0) {
                                break
                            }
                            os.write(buffer, 0, bytesRead)
                        }

                        val result: PluginResult
                        when (resultType) {
                            PluginResult.MESSAGE_TYPE_STRING -> result = PluginResult(PluginResult.Status.OK, os.toString(encoding))
                            PluginResult.MESSAGE_TYPE_ARRAYBUFFER -> result = PluginResult(PluginResult.Status.OK, os.toByteArray())
                            PluginResult.MESSAGE_TYPE_BINARYSTRING -> result = PluginResult(PluginResult.Status.OK, os.toByteArray(), true)
                            else // Base64.
                            -> {
                                val base64 = Base64.encode(os.toByteArray(), Base64.NO_WRAP)
                                val s = "data:" + contentType + ";base64," + String(base64, "US-ASCII")
                                result = PluginResult(PluginResult.Status.OK, s)
                            }
                        }

                        callbackContext.sendPluginResult(result)
                    } catch (e: IOException) {
                        LOG.d(LOG_TAG, e.getLocalizedMessage())
                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.IO_EXCEPTION, NOT_READABLE_ERR))
                    }

                }
            })


        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        } catch (e: FileNotFoundException) {
            callbackContext.sendPluginResult(PluginResult(PluginResult.Status.IO_EXCEPTION, NOT_FOUND_ERR))
        } catch (e: IOException) {
            LOG.d(LOG_TAG, e.getLocalizedMessage())
            callbackContext.sendPluginResult(PluginResult(PluginResult.Status.IO_EXCEPTION, NOT_READABLE_ERR))
        }

    }


    /**
     * Write contents of file.
     *
     * @param data                The contents of the file.
     * @param offset            The position to begin writing the file.
     * @param isBinary          True if the file contents are base64-encoded binary data
     */
    /**/
    @Throws(FileNotFoundException::class, IOException::class, NoModificationAllowedException::class)
    fun write(srcURLstr: String, data: String, offset: Int, isBinary: Boolean): Long {
        try {
            val inputURL = LocalFilesystemURL.parse(srcURLstr)
            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")

            val x = fs.writeToFileAtURL(inputURL, data, offset, isBinary)
            LOG.d("TEST", "$srcURLstr: $x")
            return x
        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        }

    }

    /**
     * Truncate the file to size
     */
    @Throws(FileNotFoundException::class, IOException::class, NoModificationAllowedException::class)
    private fun truncateFile(srcURLstr: String, size: Long): Long {
        try {
            val inputURL = LocalFilesystemURL.parse(srcURLstr)
            val fs = this.filesystemForURL(inputURL)
                    ?: throw MalformedURLException("No installed handlers for this URL")

            return fs.truncateFileAtURL(inputURL, size)
        } catch (e: IllegalArgumentException) {
            val mue = MalformedURLException("Unrecognized filesystem URL")
            mue.initCause(e)
            throw mue
        }

    }


    /*
     * Handle the response
     */

    @Throws(JSONException::class)
    fun onRequestPermissionResult(requestCode: Int, permissions: Array<String>,
                                  grantResults: IntArray) {

        val req = pendingRequests!!.getAndRemove(requestCode)
        if (req != null) {
            for (r in grantResults) {
                if (r == PackageManager.PERMISSION_DENIED) {
                    req!!.getCallbackContext().sendPluginResult(PluginResult(PluginResult.Status.ERROR, SECURITY_ERR))
                    return
                }
            }
            when (req!!.getAction()) {
                ACTION_GET_FILE -> threadhelper(object : FileOp {
                    @Throws(FileExistsException::class, IOException::class, TypeMismatchException::class, EncodingException::class, JSONException::class)
                    override fun run(args: JSONArray) {
                        val dirname = args.getString(0)

                        val path = args.getString(1)
                        val obj = getFile(dirname, path, args.optJSONObject(2), false)
                        req!!.getCallbackContext().success(obj)
                    }
                }, req!!.getRawArgs(), req!!.getCallbackContext())
                ACTION_GET_DIRECTORY -> threadhelper(object : FileOp {
                    @Throws(FileExistsException::class, IOException::class, TypeMismatchException::class, EncodingException::class, JSONException::class)
                    override fun run(args: JSONArray) {
                        val dirname = args.getString(0)

                        val path = args.getString(1)
                        val obj = getFile(dirname, path, args.optJSONObject(2), true)
                        req!!.getCallbackContext().success(obj)
                    }
                }, req!!.getRawArgs(), req!!.getCallbackContext())
                ACTION_WRITE -> threadhelper(object : FileOp {
                    @Throws(JSONException::class, FileNotFoundException::class, IOException::class, NoModificationAllowedException::class)
                    override fun run(args: JSONArray) {
                        val fname = args.getString(0)
                        val data = args.getString(1)
                        val offset = args.getInt(2)
                        val isBinary = args.getBoolean(3)
                        val fileSize = write(fname, data, offset, isBinary)
                        req!!.getCallbackContext().sendPluginResult(PluginResult(PluginResult.Status.OK, fileSize))
                    }
                }, req!!.getRawArgs(), req!!.getCallbackContext())
            }
        } else {
            LOG.d(LOG_TAG, "Received permission callback for unknown request code")
        }
    }

    companion object {
        private val LOG_TAG = "FileUtils"

        var NOT_FOUND_ERR = 1
        var SECURITY_ERR = 2
        var ABORT_ERR = 3

        var NOT_READABLE_ERR = 4
        var ENCODING_ERR = 5
        var NO_MODIFICATION_ALLOWED_ERR = 6
        var INVALID_STATE_ERR = 7
        var SYNTAX_ERR = 8
        var INVALID_MODIFICATION_ERR = 9
        var QUOTA_EXCEEDED_ERR = 10
        var TYPE_MISMATCH_ERR = 11
        var PATH_EXISTS_ERR = 12

        /*
     * Permission callback codes
     */

        val ACTION_GET_FILE = 0
        val ACTION_WRITE = 1
        val ACTION_GET_DIRECTORY = 2

        val WRITE = 3
        val READ = 4

        var UNKNOWN_ERR = 1000

        // This field exists only to support getEntry, below, which has been deprecated
        var filePlugin: FileUtils? = null
            private set

        private fun toDirUrl(f: File): String {
            return Uri.fromFile(f).toString() + '/'
        }

        /**
         * Returns a JSON object representing the given File. Deprecated, as this is only used by
         * FileTransfer, and because it is a static method that should really be an instance method,
         * since it depends on the actual filesystem roots in use. Internal APIs should be modified
         * to use URLs instead of raw FS paths wherever possible, when interfacing with this plugin.
         *
         * @param file the File to convert
         * @return a JSON representation of the given File
         * @throws JSONException
         */
        @Deprecated
        @Throws(JSONException::class)
        fun getEntry(file: File): JSONObject? {
            return if (filePlugin != null) {
                filePlugin!!.getEntryForFile(file)
            } else null
        }
    }
}
