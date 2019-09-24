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

import android.app.Activity

@Deprecated // Use Whitelist, CordovaPrefences, etc. directly.
object Config {
    private val TAG = "Config"

    internal var parser: ConfigXmlParser? = null

    val startUrl: String
        get() = if (parser == null) {
            "file:///android_asset/www/index.html"
        } else parser!!.getLaunchUrl()

    val errorUrl: String
        get() = parser!!.getPreferences().getString("errorurl", null)

    val pluginEntries: List<PluginEntry>
        get() = parser!!.getPluginEntries()

    val preferences: CordovaPreferences
        get() = parser!!.getPreferences()

    val isInitialized: Boolean
        get() = parser != null

    fun init(action: Activity) {
        parser = ConfigXmlParser()
        parser!!.parse(action)
        //TODO: Add feature to bring this back.  Some preferences should be overridden by intents, but not all
        parser!!.getPreferences().setPreferencesBundle(action.getIntent().getExtras())
    }

    // Intended to be used for testing only; creates an empty configuration.
    fun init() {
        if (parser == null) {
            parser = ConfigXmlParser()
        }
    }
}
