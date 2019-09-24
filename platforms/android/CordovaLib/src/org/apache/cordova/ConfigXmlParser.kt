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

import java.io.IOException
import java.util.ArrayList
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

import android.content.Context

class ConfigXmlParser {

    var launchUrl = "file:///android_asset/www/index.html"
        private set
    val preferences = CordovaPreferences()
    val pluginEntries = ArrayList<PluginEntry>(20)

    internal var insideFeature = false
    internal var service = ""
    internal var pluginClass = ""
    internal var paramType = ""
    internal var onload = false

    fun parse(action: Context) {
        // First checking the class namespace for config.xml
        var id = action.getResources().getIdentifier("config", "xml", action.getClass().getPackage().getName())
        if (id == 0) {
            // If we couldn't find config.xml there, we'll look in the namespace from AndroidManifest.xml
            id = action.getResources().getIdentifier("config", "xml", action.getPackageName())
            if (id == 0) {
                LOG.e(TAG, "res/xml/config.xml is missing!")
                return
            }
        }
        parse(action.getResources().getXml(id))
    }

    fun parse(xml: XmlPullParser) {
        var eventType = -1

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                handleStartTag(xml)
            } else if (eventType == XmlPullParser.END_TAG) {
                handleEndTag(xml)
            }
            try {
                eventType = xml.next()
            } catch (e: XmlPullParserException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            }

        }
    }

    fun handleStartTag(xml: XmlPullParser) {
        val strNode = xml.getName()
        if (strNode.equals("feature")) {
            //Check for supported feature sets  aka. plugins (Accelerometer, Geolocation, etc)
            //Set the bit for reading params
            insideFeature = true
            service = xml.getAttributeValue(null, "name")
        } else if (insideFeature && strNode.equals("param")) {
            paramType = xml.getAttributeValue(null, "name")
            if (paramType.equals("service"))
            // check if it is using the older service param
                service = xml.getAttributeValue(null, "value")
            else if (paramType.equals("package") || paramType.equals("android-package"))
                pluginClass = xml.getAttributeValue(null, "value")
            else if (paramType.equals("onload"))
                onload = "true".equals(xml.getAttributeValue(null, "value"))
        } else if (strNode.equals("preference")) {
            val name = xml.getAttributeValue(null, "name").toLowerCase(Locale.ENGLISH)
            val value = xml.getAttributeValue(null, "value")
            preferences.set(name, value)
        } else if (strNode.equals("content")) {
            val src = xml.getAttributeValue(null, "src")
            if (src != null) {
                setStartUrl(src)
            }
        }
    }

    fun handleEndTag(xml: XmlPullParser) {
        val strNode = xml.getName()
        if (strNode.equals("feature")) {
            pluginEntries.add(PluginEntry(service, pluginClass, onload))

            service = ""
            pluginClass = ""
            insideFeature = false
            onload = false
        }
    }

    private fun setStartUrl(src: String) {
        var src = src
        val schemeRegex = Pattern.compile("^[a-z-]+://")
        val matcher = schemeRegex.matcher(src)
        if (matcher.find()) {
            launchUrl = src
        } else {
            if (src.charAt(0) === '/') {
                src = src.substring(1)
            }
            launchUrl = "file:///android_asset/www/$src"
        }
    }

    companion object {
        private val TAG = "ConfigXmlParser"
    }
}
