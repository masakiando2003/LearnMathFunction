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
package org.apache.cordova.dialogs

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.AlertDialog.Builder
import android.app.ProgressDialog
import android.content.DialogInterface
import android.content.res.Resources
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.widget.EditText
import android.widget.TextView

import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.LOG
import org.apache.cordova.PluginResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject


/**
 * This class provides access to notifications on the device.
 *
 * Be aware that this implementation gets called on
 * navigator.notification.{alert|confirm|prompt}, and that there is a separate
 * implementation in org.apache.cordova.CordovaChromeClient that gets
 * called on a simple window.{alert|confirm|prompt}.
 */
/**
 * Constructor.
 */
class Notification : CordovaPlugin() {

    var confirmResult = -1
    var spinnerDialog: ProgressDialog? = null
    var progressDialog: ProgressDialog? = null

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action            The action to execute.
     * @param args              JSONArray of arguments for the plugin.
     * @param callbackContext   The callback context used when calling back into JavaScript.
     * @return                  True when the action was valid, false otherwise.
     */
    @Throws(JSONException::class)
    fun execute(action: String, args: JSONArray, callbackContext: CallbackContext): Boolean {
        /*
    	 * Don't run any of these if the current activity is finishing
    	 * in order to avoid android.view.WindowManager$BadTokenException
    	 * crashing the app. Just return true here since false should only
    	 * be returned in the event of an invalid action.
    	 */
        if (this.cordova.getActivity().isFinishing()) return true

        if (action.equals(ACTION_BEEP)) {
            this.beep(args.getLong(0))
        } else if (action.equals(ACTION_ALERT)) {
            this.alert(args.getString(0), args.getString(1), args.getString(2), callbackContext)
            return true
        } else if (action.equals(ACTION_CONFIRM)) {
            this.confirm(args.getString(0), args.getString(1), args.getJSONArray(2), callbackContext)
            return true
        } else if (action.equals(ACTION_PROMPT)) {
            this.prompt(args.getString(0), args.getString(1), args.getJSONArray(2), args.getString(3), callbackContext)
            return true
        } else if (action.equals(ACTION_ACTIVITY_START)) {
            this.activityStart(args.getString(0), args.getString(1))
        } else if (action.equals(ACTION_ACTIVITY_STOP)) {
            this.activityStop()
        } else if (action.equals(ACTION_PROGRESS_START)) {
            this.progressStart(args.getString(0), args.getString(1))
        } else if (action.equals(ACTION_PROGRESS_VALUE)) {
            this.progressValue(args.getInt(0))
        } else if (action.equals(ACTION_PROGRESS_STOP)) {
            this.progressStop()
        } else {
            return false
        }

        // Only alert and confirm are async.
        callbackContext.success()
        return true
    }

    //--------------------------------------------------------------------------
    // LOCAL METHODS
    //--------------------------------------------------------------------------

    /**
     * Beep plays the default notification ringtone.
     *
     * @param count     Number of times to play notification
     */
    fun beep(count: Long) {
        cordova.getThreadPool().execute(object : Runnable() {
            fun run() {
                val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val notification = RingtoneManager.getRingtone(cordova.getActivity().getBaseContext(), ringtone)

                // If phone is not set to silent mode
                if (notification != null) {
                    for (i in 0 until count) {
                        notification!!.play()
                        var timeout = BEEP_TIMEOUT
                        while (notification!!.isPlaying() && timeout > 0) {
                            timeout = timeout - BEEP_WAIT_TINE
                            try {
                                Thread.sleep(BEEP_WAIT_TINE)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }

                        }
                    }
                }
            }
        })
    }

    /**
     * Builds and shows a native Android alert with given Strings
     * @param message           The message the alert should display
     * @param title             The title of the alert
     * @param buttonLabel       The label of the button
     * @param callbackContext   The callback context
     */
    @Synchronized
    fun alert(message: String, title: String, buttonLabel: String, callbackContext: CallbackContext) {
        val cordova = this.cordova

        val runnable = object : Runnable() {
            fun run() {

                val dlg = createDialog(cordova) // new AlertDialog.Builder(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
                dlg.setMessage(message)
                dlg.setTitle(title)
                dlg.setCancelable(true)
                dlg.setPositiveButton(buttonLabel,
                        object : AlertDialog.OnClickListener() {
                            fun onClick(dialog: DialogInterface, which: Int) {
                                dialog.dismiss()
                                callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, 0))
                            }
                        })
                dlg.setOnCancelListener(object : AlertDialog.OnCancelListener() {
                    fun onCancel(dialog: DialogInterface) {
                        dialog.dismiss()
                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, 0))
                    }
                })

                changeTextDirection(dlg)
            }
        }
        this.cordova.getActivity().runOnUiThread(runnable)
    }

    /**
     * Builds and shows a native Android confirm dialog with given title, message, buttons.
     * This dialog only shows up to 3 buttons.  Any labels after that will be ignored.
     * The index of the button pressed will be returned to the JavaScript callback identified by callbackId.
     *
     * @param message           The message the dialog should display
     * @param title             The title of the dialog
     * @param buttonLabels      A comma separated list of button labels (Up to 3 buttons)
     * @param callbackContext   The callback context.
     */
    @Synchronized
    fun confirm(message: String, title: String, buttonLabels: JSONArray, callbackContext: CallbackContext) {
        val cordova = this.cordova

        val runnable = object : Runnable() {
            fun run() {
                val dlg = createDialog(cordova) // new AlertDialog.Builder(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
                dlg.setMessage(message)
                dlg.setTitle(title)
                dlg.setCancelable(true)

                // First button
                if (buttonLabels.length() > 0) {
                    try {
                        dlg.setNegativeButton(buttonLabels.getString(0),
                                object : AlertDialog.OnClickListener() {
                                    fun onClick(dialog: DialogInterface, which: Int) {
                                        dialog.dismiss()
                                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, 1))
                                    }
                                })
                    } catch (e: JSONException) {
                        LOG.d(LOG_TAG, "JSONException on first button.")
                    }

                }

                // Second button
                if (buttonLabels.length() > 1) {
                    try {
                        dlg.setNeutralButton(buttonLabels.getString(1),
                                object : AlertDialog.OnClickListener() {
                                    fun onClick(dialog: DialogInterface, which: Int) {
                                        dialog.dismiss()
                                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, 2))
                                    }
                                })
                    } catch (e: JSONException) {
                        LOG.d(LOG_TAG, "JSONException on second button.")
                    }

                }

                // Third button
                if (buttonLabels.length() > 2) {
                    try {
                        dlg.setPositiveButton(buttonLabels.getString(2),
                                object : AlertDialog.OnClickListener() {
                                    fun onClick(dialog: DialogInterface, which: Int) {
                                        dialog.dismiss()
                                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, 3))
                                    }
                                })
                    } catch (e: JSONException) {
                        LOG.d(LOG_TAG, "JSONException on third button.")
                    }

                }
                dlg.setOnCancelListener(object : AlertDialog.OnCancelListener() {
                    fun onCancel(dialog: DialogInterface) {
                        dialog.dismiss()
                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, 0))
                    }
                })

                changeTextDirection(dlg)
            }
        }
        this.cordova.getActivity().runOnUiThread(runnable)
    }

    /**
     * Builds and shows a native Android prompt dialog with given title, message, buttons.
     * This dialog only shows up to 3 buttons.  Any labels after that will be ignored.
     * The following results are returned to the JavaScript callback identified by callbackId:
     * buttonIndex			Index number of the button selected
     * input1				The text entered in the prompt dialog box
     *
     * @param message           The message the dialog should display
     * @param title             The title of the dialog
     * @param buttonLabels      A comma separated list of button labels (Up to 3 buttons)
     * @param callbackContext   The callback context.
     */
    @Synchronized
    fun prompt(message: String, title: String, buttonLabels: JSONArray, defaultText: String, callbackContext: CallbackContext) {

        val cordova = this.cordova

        val runnable = object : Runnable() {
            fun run() {
                val promptInput = EditText(cordova.getActivity())

                /* CB-11677 - By default, prompt input text color is set according current theme.
                But for some android versions is not visible (for example 5.1.1).
                android.R.color.primary_text_light will make text visible on all versions. */
                val resources = cordova.getActivity().getResources()
                val promptInputTextColor = resources.getColor(android.R.color.primary_text_light)
                promptInput.setTextColor(promptInputTextColor)
                promptInput.setText(defaultText)
                val dlg = createDialog(cordova) // new AlertDialog.Builder(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
                dlg.setMessage(message)
                dlg.setTitle(title)
                dlg.setCancelable(true)

                dlg.setView(promptInput)

                val result = JSONObject()

                // First button
                if (buttonLabels.length() > 0) {
                    try {
                        dlg.setNegativeButton(buttonLabels.getString(0),
                                object : AlertDialog.OnClickListener() {
                                    fun onClick(dialog: DialogInterface, which: Int) {
                                        dialog.dismiss()
                                        try {
                                            result.put("buttonIndex", 1)
                                            result.put("input1", if (promptInput.getText().toString().trim().length() === 0) defaultText else promptInput.getText())
                                        } catch (e: JSONException) {
                                            LOG.d(LOG_TAG, "JSONException on first button.", e)
                                        }

                                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, result))
                                    }
                                })
                    } catch (e: JSONException) {
                        LOG.d(LOG_TAG, "JSONException on first button.")
                    }

                }

                // Second button
                if (buttonLabels.length() > 1) {
                    try {
                        dlg.setNeutralButton(buttonLabels.getString(1),
                                object : AlertDialog.OnClickListener() {
                                    fun onClick(dialog: DialogInterface, which: Int) {
                                        dialog.dismiss()
                                        try {
                                            result.put("buttonIndex", 2)
                                            result.put("input1", if (promptInput.getText().toString().trim().length() === 0) defaultText else promptInput.getText())
                                        } catch (e: JSONException) {
                                            LOG.d(LOG_TAG, "JSONException on second button.", e)
                                        }

                                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, result))
                                    }
                                })
                    } catch (e: JSONException) {
                        LOG.d(LOG_TAG, "JSONException on second button.")
                    }

                }

                // Third button
                if (buttonLabels.length() > 2) {
                    try {
                        dlg.setPositiveButton(buttonLabels.getString(2),
                                object : AlertDialog.OnClickListener() {
                                    fun onClick(dialog: DialogInterface, which: Int) {
                                        dialog.dismiss()
                                        try {
                                            result.put("buttonIndex", 3)
                                            result.put("input1", if (promptInput.getText().toString().trim().length() === 0) defaultText else promptInput.getText())
                                        } catch (e: JSONException) {
                                            LOG.d(LOG_TAG, "JSONException on third button.", e)
                                        }

                                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, result))
                                    }
                                })
                    } catch (e: JSONException) {
                        LOG.d(LOG_TAG, "JSONException on third button.")
                    }

                }
                dlg.setOnCancelListener(object : AlertDialog.OnCancelListener() {
                    fun onCancel(dialog: DialogInterface) {
                        dialog.dismiss()
                        try {
                            result.put("buttonIndex", 0)
                            result.put("input1", if (promptInput.getText().toString().trim().length() === 0) defaultText else promptInput.getText())
                        } catch (e: JSONException) {
                            e.printStackTrace()
                        }

                        callbackContext.sendPluginResult(PluginResult(PluginResult.Status.OK, result))
                    }
                })

                changeTextDirection(dlg)
            }
        }
        this.cordova.getActivity().runOnUiThread(runnable)
    }

    /**
     * Show the spinner.
     *
     * @param title     Title of the dialog
     * @param message   The message of the dialog
     */
    @Synchronized
    fun activityStart(title: String, message: String) {
        if (this.spinnerDialog != null) {
            this.spinnerDialog!!.dismiss()
            this.spinnerDialog = null
        }
        val notification = this
        val cordova = this.cordova
        val runnable = object : Runnable() {
            fun run() {
                notification.spinnerDialog = createProgressDialog(cordova) // new ProgressDialog(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
                notification.spinnerDialog!!.setTitle(title)
                notification.spinnerDialog!!.setMessage(message)
                notification.spinnerDialog!!.setCancelable(true)
                notification.spinnerDialog!!.setIndeterminate(true)
                notification.spinnerDialog!!.setOnCancelListener(
                        object : DialogInterface.OnCancelListener() {
                            fun onCancel(dialog: DialogInterface) {
                                notification.spinnerDialog = null
                            }
                        })
                notification.spinnerDialog!!.show()
            }
        }
        this.cordova.getActivity().runOnUiThread(runnable)
    }

    /**
     * Stop spinner.
     */
    @Synchronized
    fun activityStop() {
        if (this.spinnerDialog != null) {
            this.spinnerDialog!!.dismiss()
            this.spinnerDialog = null
        }
    }

    /**
     * Show the progress dialog.
     *
     * @param title     Title of the dialog
     * @param message   The message of the dialog
     */
    @Synchronized
    fun progressStart(title: String, message: String) {
        if (this.progressDialog != null) {
            this.progressDialog!!.dismiss()
            this.progressDialog = null
        }
        val notification = this
        val cordova = this.cordova
        val runnable = object : Runnable() {
            fun run() {
                notification.progressDialog = createProgressDialog(cordova) // new ProgressDialog(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
                notification.progressDialog!!.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                notification.progressDialog!!.setTitle(title)
                notification.progressDialog!!.setMessage(message)
                notification.progressDialog!!.setCancelable(true)
                notification.progressDialog!!.setMax(100)
                notification.progressDialog!!.setProgress(0)
                notification.progressDialog!!.setOnCancelListener(
                        object : DialogInterface.OnCancelListener() {
                            fun onCancel(dialog: DialogInterface) {
                                notification.progressDialog = null
                            }
                        })
                notification.progressDialog!!.show()
            }
        }
        this.cordova.getActivity().runOnUiThread(runnable)
    }

    /**
     * Set value of progress bar.
     *
     * @param value     0-100
     */
    @Synchronized
    fun progressValue(value: Int) {
        if (this.progressDialog != null) {
            this.progressDialog!!.setProgress(value)
        }
    }

    /**
     * Stop progress dialog.
     */
    @Synchronized
    fun progressStop() {
        if (this.progressDialog != null) {
            this.progressDialog!!.dismiss()
            this.progressDialog = null
        }
    }

    @SuppressLint("NewApi")
    private fun createDialog(cordova: CordovaInterface): Builder {
        val currentapiVersion = android.os.Build.VERSION.SDK_INT
        return if (currentapiVersion >= android.os.Build.VERSION_CODES.HONEYCOMB) {
            Builder(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
        } else {
            Builder(cordova.getActivity())
        }
    }

    @SuppressLint("InlinedApi")
    private fun createProgressDialog(cordova: CordovaInterface): ProgressDialog {
        val currentapiVersion = android.os.Build.VERSION.SDK_INT
        return if (currentapiVersion >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            ProgressDialog(cordova.getActivity(), AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
        } else {
            ProgressDialog(cordova.getActivity())
        }
    }

    @SuppressLint("NewApi")
    private fun changeTextDirection(dlg: Builder) {
        val currentapiVersion = android.os.Build.VERSION.SDK_INT
        dlg.create()
        val dialog = dlg.show()
        if (currentapiVersion >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val messageview = dialog.findViewById(android.R.id.message) as TextView
            messageview.setTextDirection(android.view.View.TEXT_DIRECTION_LOCALE)
        }
    }

    companion object {

        private val LOG_TAG = "Notification"

        private val ACTION_BEEP = "beep"
        private val ACTION_ALERT = "alert"
        private val ACTION_CONFIRM = "confirm"
        private val ACTION_PROMPT = "prompt"
        private val ACTION_ACTIVITY_START = "activityStart"
        private val ACTION_ACTIVITY_STOP = "activityStop"
        private val ACTION_PROGRESS_START = "progressStart"
        private val ACTION_PROGRESS_VALUE = "progressValue"
        private val ACTION_PROGRESS_STOP = "progressStop"

        private val BEEP_TIMEOUT: Long = 5000
        private val BEEP_WAIT_TINE: Long = 100
    }
}
