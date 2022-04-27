# Audio configuration

Audio configuration is hard 😅. The goal is to not clip the signal anywhere in the audio chain while at the same time maintaining a high dynamic range.

As a rule of thumb, check the chain from the producer to the OBS laptop making sure that each step doesn't clip.

## Synchronizing a Senheiser wireless XS transmiter

During synchronisation, the receiver sends the frequency to the transmitter. To sync automatically:

- Long press the "sync" button on the transmitter until "sync" blinks on the "receiver"
- Press the "sync" button on the receiver

## Audio levels

There are 2 kinds of audio levels:

- line is ~1V
- mic is ~1mV (very roughly as I don't think there is an absolute rule there)

The Senheiser wireless XS can output both levels (there's a small switch on the back next to the power input). It's important to match that on the Camera input. I find that "mic" is a bit better (but not sure).

The Moebius & Blin control room output line level.

## Receiver Gain

The Senheiser wireless XS have a gain button on the front as well as a level metter. Usually there's no need to increase the volume a lot on the receiver.

## Camera settings

Next is camera settings:

- Make sure to select "ext" and not "int" to use the external microphone and not the built-in one
- Make sure to select "line" (for Moebius or Blin) or "mic" (for senheiser XS) according to the input level
- Then there's GAIN
  - Auto gain is the "easy" choice as the gain will adapt automatically. At the price of bumping the noise when no one is talking.
  - Manual gain requires adjusting so that the levels do not clip on the camera level meter.
- Check the levels on the camera and check the audio using a headset on the Camera
- If there's only one input, you can configure the camera to duplicate it on both channels. I'm not sure how that's done any more... Double check the camera manual before going to the event.

# OBS mixer

OBS has a nice documentation about the mixer https://obsproject.com/wiki/Understanding-the-Mixer.

Make sure the peak meter never goes in the red zone.

OBS monitoring can be capricious sometimes if it doesn't work, click on "advanced audio properties", disable monitoring and re-enable it.