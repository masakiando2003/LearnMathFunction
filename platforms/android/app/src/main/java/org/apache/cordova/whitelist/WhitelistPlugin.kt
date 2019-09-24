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

package org.apache.cordova.whitelist

import org.apache.cordova.CordovaPlugin
import org.apache.cordova.ConfigXmlParser
import org.apache.cordova.LOG
import org.apache.cordova.Whitelist
import org.xmlpull.v1.XmlPullParser

import android.content.Context

class WhitelistPlugin : CordovaPlugin {
    var allowedNavigations: Whitelist? = null
    var allowedIntents: Whitelist? = null
    var allowedRequests: Whitelist? = null

    // Used when instantiated via reflection by PluginManager
    constructor() {}

    // These can be used by embedders to allow Java-configuration of whitelists.
    constructor(context: Context) : this(Whitelist(), Whitelist(), null) {
        CustomConfigXmlParser().parse(context)
    }

    constructor(xmlParser: XmlPullParser) : this(Whitelist(), Whitelist(), null) {
        CustomConfigXmlParser().parse(xmlParser)
    }

    constructor(allowedNavigations: Whitelist, allowedIntents: Whitelist, allowedRequests: Whitelist?) {
        var allowedRequests = allowedRequests
        if (allowedRequests == null) {
            allowedRequests = Whitelist()
            allowedRequests!!.addWhiteListEntry("file:///*", false)
            allowedRequests!!.addWhiteListEntry("data:*", false)
        }
        this.allowedNavigations = allowedNavigations
        this.allowedIntents = allowedIntents
        this.allowedRequests = allowedRequests
    }

    @Override
    fun pluginInitialize() {
        if (allowedNavigations == null) {
            allowedNavigations = Whitelist()
            allowedIntents = Whitelist()
            allowedRequests = Whitelist()
            CustomConfigXmlParser().parse(webView.getContext())
        }
    }

    private inner class CustomConfigXmlParser : ConfigXmlParser() {
        @Override
        fun handleStartTag(xml: XmlPullParser) {
            val strNode = xml.getName()
            if (strNode.equals("content")) {
                val startPage = xml.getAttributeValue(null, "src")
                allowedNavigations!!.addWhiteListEntry(startPage, false)
            } else if (strNode.equals("allow-navigation")) {
                val origin = xml.getAttributeValue(null, "href")
                if ("*".equals(origin)) {
                    allowedNavigations!!.addWhiteListEntry("http://*/*", false)
                    allowedNavigations!!.addWhiteListEntry("https://*/*", false)
                    allowedNavigations!!.addWhiteListEntry("data:*", false)
                } else {
                    allowedNavigations!!.addWhiteListEntry(origin, false)
                }
            } else if (strNode.equals("allow-intent")) {
                val origin = xml.getAttributeValue(null, "href")
                allowedIntents!!.addWhiteListEntry(origin, false)
            } else if (strNode.equals("access")) {
                val origin = xml.getAttributeValue(null, "origin")
                val subdomains = xml.getAttributeValue(null, "subdomains")
                val external = xml.getAttributeValue(null, "launch-external") != null
                if (origin != null) {
                    if (external) {
                        LOG.w(LOG_TAG, "Found <access launch-external> within config.xml. Please use <allow-intent> instead.")
                        allowedIntents!!.addWhiteListEntry(origin, subdomains != null && subdomains!!.compareToIgnoreCase("true") === 0)
                    } else {
                        if ("*".equals(origin)) {
                            allowedRequests!!.addWhiteListEntry("http://*/*", false)
                            allowedRequests!!.addWhiteListEntry("https://*/*", false)
                        } else {
                            allowedRequests!!.addWhiteListEntry(origin, subdomains != null && subdomains!!.compareToIgnoreCase("true") === 0)
                        }
                    }
                }
            }
        }

        @Override
        fun handleEndTag(xml: XmlPullParser) {
        }
    }

    @Override
    fun shouldAllowNavigation(url: String): Boolean? {
        return if (allowedNavigations!!.isUrlWhiteListed(url)) {
            true
        } else null
// Default policy
    }

    @Override
    fun shouldAllowRequest(url: String): Boolean? {
        if (Boolean.TRUE === shouldAllowNavigation(url)) {
            return true
        }
        return if (allowedRequests!!.isUrlWhiteListed(url)) {
            true
        } else null
// Default policy
    }

    @Override
    fun shouldOpenExternalUrl(url: String): Boolean? {
        return if (allowedIntents!!.isUrlWhiteListed(url)) {
            true
        } else null
// Default policy
    }

    companion object {
        private val LOG_TAG = "WhitelistPlugin"
    }
}
