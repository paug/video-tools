# kscripts

Kotlin script to help with generating/uploading videos:

* Add an intro image with fade out
* Normalize the volume
* Skip the first seconds of a talk
* Upload to Youtube
* Set the metadata of Youtube videos

It does so without re-encoding to save time and keep quality. To do that, the script works with elementary h264 streams and therefore looses timestamp information. This can be a problem if your source is not 30fps or for some container formats. As long as input is 30fps and output is .avi, it's been working well so far.

You need [kscript](https://github.com/holgerbrandl/kscript) to run these scripts:

    curl -s "https://get.sdkman.io" | bash
    source ~/.sdkman/bin/sdkman-init.sh
    sdk install kotlin
    sdk install gradle 
    sdk install kscript

To run the scripts:

    ./videotool.kts

To open the scripts in intelliJ and have nice autocomplete:

    kscript --idea am_youtube_tool.kts