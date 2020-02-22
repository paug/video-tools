# kscripts

Kotlin scripts to help with generating/uploading videos:

* Add an intro image with fade out
* Normalize the volume
* Skip the first seconds of a talk
* Upload to Youtube
* Set the metadata of Youtube videos

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