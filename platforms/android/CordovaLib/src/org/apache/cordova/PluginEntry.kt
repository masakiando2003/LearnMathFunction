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

import org.apache.cordova.CordovaPlugin

/**
 * This class represents a service entry object.
 */
class PluginEntry private constructor(
        /**
         * The name of the service that this plugin implements
         */
        val service: String,
        /**
         * The plugin class name that implements the service.
         */
        val pluginClass: String,
        /**
         * Flag that indicates the plugin object should be created when PluginManager is initialized.
         */
        val onload: Boolean,
        /**
         * The pre-instantiated plugin to use for this entry.
         */
        val plugin: CordovaPlugin?) {

    /**
     * Constructs with a CordovaPlugin already instantiated.
     */
    constructor(service: String, plugin: CordovaPlugin) : this(service, plugin.getClass().getName(), true, plugin) {}

    /**
     * @param service               The name of the service
     * @param pluginClass           The plugin class name
     * @param onload                Create plugin object when HTML page is loaded
     */
    constructor(service: String, pluginClass: String, onload: Boolean) : this(service, pluginClass, onload, null) {}
}
