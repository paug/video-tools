# Before AndroidMakers

## Prepare your laptop

* Make sure to have several ~10s of GB of storage available, ideally 50GB
* Take hardwired headphones, they typically have lower latency than bluetooth + they can help troubleshoot the camera if needed

## OBS configuration

* Install [OBS](https://obsproject.com/fr/download)
* Configure the scene
* Go to the Scene Collection menu -> Import
* Select video-tools/obs/scene/AndroidMakers.json
* Connect the two USB-C cables (BlackMagic + AverMedia or AverMedia + AverMedia)
* If the camera picture is not displayed, right click on Camera USB -> Properties and select the "BlackMagic..." device (or AverMedia for the lower rooms). Repeat this step for all scenes containing the camera.
* If the slides picture is not displayed, right click on Slides USB -> Properties and select the "AverMedia.." device.
* Repeat this step for all scenes containing slides.

In **Output**, mkv, 5000kbps,

![](https://storage.googleapis.com/androidmakers-static/obs_output.png)

In **Video**, choose 1920x1080 for the 2 resolutions. Choose 30fps.

![](https://storage.googleapis.com/androidmakers-static/obs_video.png)

## (If necessary) BlackMagic 
* (If NecessaryInstall [BlackMagic Desktop Video](https://www.blackmagicdesign.com/support/download/e68b93bcae004ec19404defbea9f0b07/Mac%20OS%20X)
  * Connect the BlackMagic to the computer with a **Thunderbolt** cable (⚠️you need a thunderbolt cable and not USB-C)
  * Open BlackMagic Desktop Video
  * Click on the small button in the center
  * In Video Input, select HDMI if the BlackMagic input is HDMI, otherwise, select SDI.

# During The Event

## Before a talk

* Start OBS
* Check that both videos (camera and slides) are displayed on the software
* Check that the sound is correctly displayed (green bar at the bottom)
* Click on “record video”
* When starting the Talk, note the start of the video (at the bottom) in a text file named with the text id (see template)

## During a talk
* Listen to the talk via headphones to check the sound recording. If there is a sound problem, notify the control room (lower room), Alex or others
* Look at the frame rate of the video (bottom right), if below 25fps, try to change scene, tell someone
* Check that the speaker is in the video frame. Adjust camera if this happens/will happen too often. If only temporary, don't hesitate to go to the "full screen slides" scene by clicking on the FullScreen slides scene

## After a talk
* Stop recording
* Rename recording with Talk ID (automatic processing after)

# Troubleshooting

## Audio monitor doesn't work in OBS

If that happens, go to advanced audio properties and click "Monitor Off" and then back "Monitor and Output" again.

![](https://storage.googleapis.com/androidmakers-static/obs_audio_monitor.png)
