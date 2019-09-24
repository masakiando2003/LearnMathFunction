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

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.view.KeyEvent
import android.widget.EditText

/**
 * Helper class for WebViews to implement prompt(), alert(), confirm() dialogs.
 */
class CordovaDialogsHelper(private val context: Context) {
    private var lastHandledDialog: AlertDialog? = null

    fun showAlert(message: String, result: Result) {
        val dlg = AlertDialog.Builder(context)
        dlg.setMessage(message)
        dlg.setTitle("Alert")
        //Don't let alerts break the back button
        dlg.setCancelable(true)
        dlg.setPositiveButton(android.R.string.ok,
                object : AlertDialog.OnClickListener() {
                    fun onClick(dialog: DialogInterface, which: Int) {
                        result.gotResult(true, null)
                    }
                })
        dlg.setOnCancelListener(
                object : DialogInterface.OnCancelListener() {
                    fun onCancel(dialog: DialogInterface) {
                        result.gotResult(false, null)
                    }
                })
        dlg.setOnKeyListener(object : DialogInterface.OnKeyListener() {
            //DO NOTHING
            fun onKey(dialog: DialogInterface, keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    result.gotResult(true, null)
                    return false
                } else
                    return true
            }
        })
        lastHandledDialog = dlg.show()
    }

    fun showConfirm(message: String, result: Result) {
        val dlg = AlertDialog.Builder(context)
        dlg.setMessage(message)
        dlg.setTitle("Confirm")
        dlg.setCancelable(true)
        dlg.setPositiveButton(android.R.string.ok,
                object : DialogInterface.OnClickListener() {
                    fun onClick(dialog: DialogInterface, which: Int) {
                        result.gotResult(true, null)
                    }
                })
        dlg.setNegativeButton(android.R.string.cancel,
                object : DialogInterface.OnClickListener() {
                    fun onClick(dialog: DialogInterface, which: Int) {
                        result.gotResult(false, null)
                    }
                })
        dlg.setOnCancelListener(
                object : DialogInterface.OnCancelListener() {
                    fun onCancel(dialog: DialogInterface) {
                        result.gotResult(false, null)
                    }
                })
        dlg.setOnKeyListener(object : DialogInterface.OnKeyListener() {
            //DO NOTHING
            fun onKey(dialog: DialogInterface, keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    result.gotResult(false, null)
                    return false
                } else
                    return true
            }
        })
        lastHandledDialog = dlg.show()
    }

    /**
     * Tell the client to display a prompt dialog to the user.
     * If the client returns true, WebView will assume that the client will
     * handle the prompt dialog and call the appropriate JsPromptResult method.
     *
     * Since we are hacking prompts for our own purposes, we should not be using them for
     * this purpose, perhaps we should hack console.log to do this instead!
     */
    fun showPrompt(message: String, defaultValue: String?, result: Result) {
        // Returning false would also show a dialog, but the default one shows the origin (ugly).
        val dlg = AlertDialog.Builder(context)
        dlg.setMessage(message)
        val input = EditText(context)
        if (defaultValue != null) {
            input.setText(defaultValue)
        }
        dlg.setView(input)
        dlg.setCancelable(false)
        dlg.setPositiveButton(android.R.string.ok,
                object : DialogInterface.OnClickListener() {
                    fun onClick(dialog: DialogInterface, which: Int) {
                        val userText = input.getText().toString()
                        result.gotResult(true, userText)
                    }
                })
        dlg.setNegativeButton(android.R.string.cancel,
                object : DialogInterface.OnClickListener() {
                    fun onClick(dialog: DialogInterface, which: Int) {
                        result.gotResult(false, null)
                    }
                })
        lastHandledDialog = dlg.show()
    }

    fun destroyLastDialog() {
        if (lastHandledDialog != null) {
            lastHandledDialog!!.cancel()
        }
    }

    interface Result {
        fun gotResult(success: Boolean, value: String?)
    }
}