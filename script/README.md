# Python script to generate and upload video on YouTube

## Purpose
The purpose of this script is to generate from raw videos of Android Makers or Paris Android User Group conferences a cleaned mp4 video and to upload it on YouTube.

### Data format
A video is related to a talk thanks to its id. This is why the video **must** be named **VIDEO_ID**.VIDEO_EXTENSION (such as MBP-1890.mkv).<br/>
The talk information is available in the `resources/input_data.json` file. This file contains, for each talk (identified by its id), its speaker(s), title, description, tags, and information about how to generate the video (such as audio channel to select, parts to cut at the beginning and at the end).

Here is an extract of this file for a given talk:

```
{
   "da": "XTB-2115",
   "website": "link",
   "title": "My awesome talk",
   "speakers": "Bugdroid, Google",
   "Video cut Begin": "10:33",
   "Video cut End": "47:24",
   "Audio stereo side": "right",
   "Youtube title": "My awesome talk by Bugdroid, Google EN",
   "Desc": "This session was given at Android Makers 2019 by Bugdroid, Google.\n\nMore info: http://androidmakers.fr/schedule/?sessionId=XTB-2115\n\nThis is the description of this awesome talk, lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.",
   "tags": "Android Makers, Android Makers 2019, Android Makers 19"
},
```

### Generation

To generate the video, the script will call a ffmpeg command that will add an intro image during 3 seconds, then cut the beginning and the end of the source video as indicated in the `resources/input_data.json` file and add that cut video after the intro.<br/>
For the sound, it will select the audio channel as indicated in the `resources/input_data.json` file and cut it like the video and then put it on both channels.

The output video will be stored in the `out/` folder.

### Upload

The upload is done using the YouTube Python API. Once the video is uploaded, it will get its YouTube id and stores it in a dictionary where the key is the talk id.

## Installation

### Tools
The script requires Python and ffmpeg installed.

It also needs the Google APIs Client Library for Python and the google-auth, google-auth-oauthlib, and google-auth-httplib2 for user authorization:

```
pip install --upgrade google-api-python-client
pip install --upgrade google-auth google-auth-oauthlib google-auth-httplib2
```


### OAuth key
Then you will need to go to the [Google API Console](https://console.developers.google.com/apis/) and create a project.<br/>
Enable the YouTube Data API v3 and create an OAuth client ID of the "Other" type. In the OAuth consent screen, enable the `../auth/youtube` scope. Then save.<br/>
Since you've added a sensitive scope, your consent screen requires verification by Google before it's published. *We did not use the verificated part. Instead, we used it without verification, this is maybe why we had a very limited quota.*

In the creadential part, download the json, rename it as `client_secrets.json` and move it in the `resources` folder.

## Usage

The script has multiple options:

- `--generate`: generates the videos with their matching intros, cut at the right moment, with selected audio channel.<br/>
The source of this part is the `resources/input_data.json`.<br/>
The output of this part is videos, ready to upload, in the `out/` folder.

- `--upload`: to upload generated videos to YouTube (with metadata)<br/>
The source of this part is the list of videos present in `out/` folder. To know the metadata, it will fetch infos from `resources/input_data.json`.<br/>
The output of this part is the file `uploadedVideos.json` filled with the YouTube video ids of the uploaded videos. If a `uploadedVideos.json` already contained a YouTube video id, this id will be overwritten in this file but it will be saved in `replacedVideos.json`.
- `--publish`: to change uploaded videos privacy status to public.<br/>
The source of this part is the `uploadedVideos.json` file.
- `--privatise`: to change uploaded videos privacy status to private.<br/>
The source of this part is the `uploadedVideos.json` file.
- `--unlist`: to change uploaded videos privacy status to unlisted.<br/>
The source of this part is the `uploadedVideos.json` file.
- `--update_yt_metadata`: to update the uploaded videos metadata.<br/>
The source of this part is the list of videos present in `out/` folder. To know the YouTube video id, it will fetch infos from `uploadedVideos.json`. To know the metadata, it will fetch infos from `resources/input_data.json`.<br/>