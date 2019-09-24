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

import java.util.HashMap
import java.util.Locale

import org.apache.cordova.LOG

import android.app.Activity
import android.os.Bundle

class CordovaPreferences {
    private val prefs = HashMap<String, String>(20)
    private var preferencesBundleExtras: Bundle? = null

    val all: Map<String, String>
        get() = prefs

    fun setPreferencesBundle(extras: Bundle) {
        preferencesBundleExtras = extras
    }

    operator fun set(name: String, value: String) {
        prefs.put(name.toLowerCase(Locale.ENGLISH), value)
    }

    operator fun set(name: String, value: Boolean) {
        set(name, "" + value)
    }

    operator fun set(name: String, value: Int) {
        set(name, "" + value)
    }

    operator fun set(name: String, value: Double) {
        set(name, "" + value)
    }

    fun getBoolean(name: String, defaultValue: Boolean): Boolean {
        var name = name
        name = name.toLowerCase(Locale.ENGLISH)
        val value = prefs.get(name)
        return if (value != null) {
            Boolean.parseBoolean(value)
        } else defaultValue
    }

    // Added in 4.0.0
    operator fun contains(name: String): Boolean {
        return getString(name, null) != null
    }

    fun getInteger(name: String, defaultValue: Int): Int {
        var name = name
        name = name.toLowerCase(Locale.ENGLISH)
        val value = prefs.get(name)
        return if (value != null) {
            // Use Integer.decode() can't handle it if the highest bit is set.
            (Long.decode(value) as Long).toInt()
        } else defaultValue
    }

    fun getDouble(name: String, defaultValue: Double): Double {
        var name = name
        name = name.toLowerCase(Locale.ENGLISH)
        val value = prefs.get(name)
        return if (value != null) {
            Double.valueOf(value)
        } else defaultValue
    }

    fun getString(name: String, defaultValue: String?): String? {
        var name = name
        name = name.toLowerCase(Locale.ENGLISH)
        val value = prefs.get(name)
        return if (value != null) {
            value
        } else defaultValue
    }

}
