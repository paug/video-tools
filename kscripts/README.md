# kscripts

Kotlin scripts to help with generating/uploading videos.

You need [kscript](https://github.com/holgerbrandl/kscript) to run these scripts:

    curl -s "https://get.sdkman.io" | bash
    source ~/.sdkman/bin/sdkman-init.sh
    sdk install kotlin
    sdk install kscript

To run the scripts:

    ./am_youtube_tool.kts

To open the scripts in intelliJ and have nice autocomplete:

    sdk install gradle # if you don't have a gradle install on your machine yet
    kscript --idea am_youtube_tool.kts