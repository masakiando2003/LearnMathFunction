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

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import android.util.Base64

class CordovaArgs(private val baseArgs: JSONArray) {


    // Pass through the basics to the base args.
    @Throws(JSONException::class)
    operator fun get(index: Int): Object {
        return baseArgs.get(index)
    }

    @Throws(JSONException::class)
    fun getBoolean(index: Int): Boolean {
        return baseArgs.getBoolean(index)
    }

    @Throws(JSONException::class)
    fun getDouble(index: Int): Double {
        return baseArgs.getDouble(index)
    }

    @Throws(JSONException::class)
    fun getInt(index: Int): Int {
        return baseArgs.getInt(index)
    }

    @Throws(JSONException::class)
    fun getJSONArray(index: Int): JSONArray {
        return baseArgs.getJSONArray(index)
    }

    @Throws(JSONException::class)
    fun getJSONObject(index: Int): JSONObject {
        return baseArgs.getJSONObject(index)
    }

    @Throws(JSONException::class)
    fun getLong(index: Int): Long {
        return baseArgs.getLong(index)
    }

    @Throws(JSONException::class)
    fun getString(index: Int): String {
        return baseArgs.getString(index)
    }


    fun opt(index: Int): Object {
        return baseArgs.opt(index)
    }

    fun optBoolean(index: Int): Boolean {
        return baseArgs.optBoolean(index)
    }

    fun optDouble(index: Int): Double {
        return baseArgs.optDouble(index)
    }

    fun optInt(index: Int): Int {
        return baseArgs.optInt(index)
    }

    fun optJSONArray(index: Int): JSONArray {
        return baseArgs.optJSONArray(index)
    }

    fun optJSONObject(index: Int): JSONObject {
        return baseArgs.optJSONObject(index)
    }

    fun optLong(index: Int): Long {
        return baseArgs.optLong(index)
    }

    fun optString(index: Int): String {
        return baseArgs.optString(index)
    }

    fun isNull(index: Int): Boolean {
        return baseArgs.isNull(index)
    }


    // The interesting custom helpers.
    @Throws(JSONException::class)
    fun getArrayBuffer(index: Int): ByteArray {
        val encoded = baseArgs.getString(index)
        return Base64.decode(encoded, Base64.DEFAULT)
    }
}


