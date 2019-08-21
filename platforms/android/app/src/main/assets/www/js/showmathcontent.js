function readDataFile(){
    //This alias is a read-only pointer to the app itself
    //window.resolveLocalFileSystemURL(cordova.file.applicationDirectory + "www/math_function.txt", gotFile, fail);
}

function fail(e) {
    console.log("FileSystem Error");
    console.dir(e);
}

function gotFile(fileEntry) {

    alert('test');

    fileEntry.file(function(file) {
        var reader = new FileReader();

        reader.onloadend = function(e) {
            //console.log("Text is: "+this.result);
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
                    contentSeparator.setAttribute("style", "display:none");
                    contentSeparator.setAttribute("value", contentSeparators[contentSeparatorIndex]);
                    document.getElementById('mathFunctionContainer').appendChild(contentSeparator);
                 }
                 titles[titleIndex++] = lines[line].replace(/(\r\n|\n|\r|＃)/gm, "");
                 var title = document.createElement("input");
                 title.setAttribute("type", "hidden");
                 title.setAttribute("id", "title"+titleIndex);
                 title.setAttribute("value", titles[titleIndex-1]);
                 document.getElementById('mathFunctionTitle').appendChild(title);
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

            var next_index_arr = contentSeparators[0].split(",", 2);
            document.getElementById('next_title_index').value = next_index_arr[0];
            document.getElementById('next_content_index').value = next_index_arr[1];

            document.getElementById('mathFunctionTitle').innerHTML = document.getElementById('title1').value;
            document.getElementById('content_index').value = 1;
            document.getElementById('ori_content_index').value = 1;
        }

        reader.readAsText(file);
    });
}