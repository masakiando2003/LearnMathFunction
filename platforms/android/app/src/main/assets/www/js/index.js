/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
var app = {
    // Application Constructor
    initialize: function() {
        document.addEventListener('deviceready', this.onDeviceReady.bind(this), false);
    },

    // deviceready Event Handler
    //
    // Bind any cordova events here. Common events are:
    // 'pause', 'resume', etc.
    onDeviceReady: function() {
        this.receivedEvent('deviceready');
        //navigator.notification.alert('PhoneGap Alert', null, 'Title', 'Button');
        this.readDataFile();
    },

    // Update DOM on a Received Event
    receivedEvent: function(id) {
        var parentElement = document.getElementById(id);
        /*
        var listeningElement = parentElement.querySelector('.listening');
        var receivedElement = parentElement.querySelector('.received');

        listeningElement.setAttribute('style', 'display:none;');
        receivedElement.setAttribute('style', 'display:block;');
        */

        console.log('Received Event: ' + id);
    },

    readDataFile: function () {

        var absPath = cordova.file.externalRootDirectory;
        var fileDir = cordova.file.externalDataDirectory.replace(cordova.file.externalRootDirectory, '');
        var fileName = "last_read_file.txt";
        var filePath = fileDir + fileName;

        //window.resolveLocalFileSystemURL(filePath, this.gotFile, this.readDataFileFailed);
        window.requestFileSystem(LocalFileSystem.PERSISTENT, 0, function (fs) {
            fs.root.getFile(filePath, { create: false, exclusive: false },
                function (fileEntry) {
                    fileEntry.file(function(file) {
                    var reader = new FileReader();

                    reader.onloadend = function(e) {
                        console.log("Custom File Text is: "+this.result);
                        //document.querySelector("#textArea").innerHTML = this.result;

                        var titles = new Array();
                        var contents = new Array();
                        var contentSeparators = new Array();
                        var titleIndex = 0;
                        var contentIndex = 0;
                        var contentSeparatorIndex = 0;

                        var lines = this.result.split('\n');
                        for(var line = 0; line < lines.length; line++){
                          //console.log(lines[line]);
                          if(lines[line].match(/(^＃|[^&]＃)/gi)){
                             if(titleIndex > 0){
                                //console.log("Indexes: "+titleIndex+", "+contentIndex);
                                contentSeparators[contentSeparatorIndex++] = titleIndex.toString()+", "+contentIndex.toString();
                                var contentSeparator = document.createElement("input");
                                contentSeparator.setAttribute("type", "hidden");
                                contentSeparator.setAttribute("name", "contentSeparator"+contentSeparatorIndex);
                                contentSeparator.setAttribute("id", "contentSeparator"+contentSeparatorIndex);
                                contentSeparator.setAttribute("value", contentSeparators[contentSeparatorIndex-1]);
                                document.getElementById('mathFunctionContainer').appendChild(contentSeparator);
                             }
                             titles[titleIndex++] = lines[line].replace(/(\r\n|\n|\r|＃)/gm, "");
                             var title = document.createElement("div");
                             title.setAttribute("id", "title"+titleIndex);
                             title.setAttribute("style", "display:none");
                             title.innerHTML = titles[titleIndex-1];
                             document.getElementById('mathFunctionContainer').appendChild(title);
                          }
                          else if(lines[line].trim() != ''){
                            contents[contentIndex++] = lines[line].replace(/(\r\n|\n|\r)/gm, "");
                            var content = document.createElement("div");
                            content.setAttribute("id", "content"+contentIndex);
                            content.setAttribute("style", "display:none");
                            content.innerHTML = contents[contentIndex-1];
                            document.getElementById('mathFunctionContent').appendChild(content);
                          }
                        }

                        console.log("Titles: "+JSON.stringify(titles));
                        console.log("Contents: "+JSON.stringify(contents));
                        console.log("Content Separators: "+JSON.stringify(contentSeparators));

                        if(contentSeparators.length > 0){
                            var previous_index_arr = contentSeparators[0].split(",", 2);
                            document.getElementById('previous_title_index').value = previous_index_arr[0];
                            document.getElementById('previous_content_index').value = previous_index_arr[1];

                            var current_index_arr = previous_index_arr;
                            document.getElementById('current_title_index').value = current_index_arr[0];
                            document.getElementById('current_content_index').value = current_index_arr[1];

                            var next_index_arr = contentSeparators[1].split(",", 2);
                            document.getElementById('next_title_index').value = next_index_arr[0];
                            document.getElementById('next_content_index').value = next_index_arr[1];
                        }
                        else{
                            document.getElementById('btnPrevious').style.display = "none";
                            document.getElementById('btnNext').style.display = "none";

                            document.getElementById('current_title_index').value = 1;
                            document.getElementById('current_content_index').value = contents.length;
                        }

                        document.getElementById('mathFunctionTitle').innerHTML = document.getElementById('title1').innerHTML;
                        document.getElementById('content_index').value = 1;
                        document.getElementById('ori_content_index').value = 1;

                        document.getElementById('btnPrevious').style.visibility = 'hidden';

                        document.getElementById('totalTitleCount').value = titleIndex - 1;
                        document.getElementById('totalContentCount').value = contentIndex - 1;
                    }

                    reader.onerror = this.readDataFileFailed;

                    reader.readAsText(file);
                    });

                MathJax.Hub.Config({
                  tex2jax: {
                    inlineMath: [ ["$", "$"], ["\\(","\\)"] ],
                    processEscapes: true,
                    ignoreClass: "tex2jax_ignore|dno"
                  }
                });

                MathJax.Hub.Configured();
            },
            function (e){
                //var defaultFilePath = cordova.file.applicationDirectory + "www/math_function.txt";
                var defaultFilePath = cordova.file.applicationDirectory + "www/resource/math_function.txt";
                window.resolveLocalFileSystemURL(defaultFilePath,
                    function (fileEntry) {
                        fileEntry.file(function(file) {
                            var reader = new FileReader();

                            reader.onloadend = function(e) {
                                console.log("Default File Text is: "+this.result);
                                //document.querySelector("#textArea").innerHTML = this.result;

                                var titles = new Array();
                                var contents = new Array();
                                var contentSeparators = new Array();
                                var titleIndex = 0;
                                var contentIndex = 0;
                                var contentSeparatorIndex = 0;

                                var lines = this.result.split('\n');
                                for(var line = 0; line < lines.length; line++){
                                  //console.log(lines[line]);
                                  if(lines[line].match(/(^＃|[^&]＃)/gi)){
                                     if(titleIndex > 0){
                                        //console.log("Indexes: "+titleIndex+", "+contentIndex);
                                        contentSeparators[contentSeparatorIndex++] = titleIndex.toString()+", "+contentIndex.toString();
                                        var contentSeparator = document.createElement("input");
                                        contentSeparator.setAttribute("type", "hidden");
                                        contentSeparator.setAttribute("name", "contentSeparator"+contentSeparatorIndex);
                                        contentSeparator.setAttribute("id", "contentSeparator"+contentSeparatorIndex);
                                        contentSeparator.setAttribute("value", contentSeparators[contentSeparatorIndex-1]);
                                        document.getElementById('mathFunctionContainer').appendChild(contentSeparator);
                                     }
                                     titles[titleIndex++] = lines[line].replace(/(\r\n|\n|\r|＃)/gm, "");
                                     var title = document.createElement("div");
                                     title.setAttribute("id", "title"+titleIndex);
                                     title.setAttribute("style", "display:none");
                                     title.innerHTML = titles[titleIndex-1];
                                     document.getElementById('mathFunctionContainer').appendChild(title);
                                  }
                                  else if(lines[line].trim() != ''){
                                    contents[contentIndex++] = lines[line].replace(/(\r\n|\n|\r)/gm, "");
                                    var content = document.createElement("div");
                                    content.setAttribute("id", "content"+contentIndex);
                                    content.setAttribute("style", "display:none");
                                    content.innerHTML = contents[contentIndex-1];
                                    document.getElementById('mathFunctionContent').appendChild(content);
                                  }
                                }

                                console.log("Titles: "+JSON.stringify(titles));
                                console.log("Contents: "+JSON.stringify(contents));
                                console.log("Content Separators: "+JSON.stringify(contentSeparators));

                                var previous_index_arr = contentSeparators[0].split(",", 2);
                                document.getElementById('previous_title_index').value = previous_index_arr[0];
                                document.getElementById('previous_content_index').value = previous_index_arr[1];

                                var current_index_arr = previous_index_arr;
                                document.getElementById('current_title_index').value = current_index_arr[0];
                                document.getElementById('current_content_index').value = current_index_arr[1];

                                var next_index_arr = contentSeparators[1].split(",", 2);
                                document.getElementById('next_title_index').value = next_index_arr[0];
                                document.getElementById('next_content_index').value = next_index_arr[1];

                                document.getElementById('mathFunctionTitle').innerHTML = document.getElementById('title1').innerHTML;
                                document.getElementById('content_index').value = 1;
                                document.getElementById('ori_content_index').value = 1;

                                document.getElementById('btnPrevious').style.visibility = 'hidden';

                                document.getElementById('totalTitleCount').value = titleIndex - 1;
                                document.getElementById('totalContentCount').value = contentIndex - 1;
                            }

                            reader.readAsText(file);
                        });


                        MathJax.Hub.Config({
                          tex2jax: {
                            inlineMath: [ ["$", "$"], ["\\(","\\)"] ],
                            processEscapes: true,
                            ignoreClass: "tex2jax_ignore|dno"
                          }
                        });

                        MathJax.Hub.Configured();
                    },
                    function (e) { alert('readDataFileFailed'); }
                );
            });
        }, function(err) {});
    },
};

app.initialize();