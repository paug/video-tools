# kscripts

Kotlin scripts to help with generating/uploading videos.

## Prerequisites

You need kotlin in your PATH:

    curl -s "https://get.sdkman.io" | bash
    source ~/.sdkman/bin/sdkman-init.sh
    sdk install kotlin
 
You also need [ImageMagick](https://imagemagick.org/index.php):

	brew install imagemagick

And ffmpeg:

	brew install ffmpeg

## Generating the videos

Test the script on a single video with `./generate_video.main.kts generate`. 

Once everything is ok, download a CSV file containing the start and end time (this might require some tweaking of the script) and run in bactch mode

```
./generate_video.main.kts batch --in-dir ~/Downloads/Captation2022/ --intro-dir ~/git/tmpAndroidMakersVisuals/INTRO --sponsor-path ~/Downloads/Captation2022/sponsors.png --infos-csv ~/Downloads/Captation2022/data.csv --out-dir out --skip-existing
```

## Uploading the videos

There is no script to do this at the moment. It requires manually uploading and adding the youtube url to the spreadsheet

## Editing the metadata 

(TBD) To run the scripts:

    ./am_youtube_tool.kts
