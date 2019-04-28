#extract H264 elementary stream
ffmpeg -i NTE-5380.mkv -vcodec copy -vbsf h264_mp4toannexb NTE-5380.h264

#volume detection, will output something like this
#[Parsed_volumedetect_0 @ 0x3a5f900] n_samples: 128613376
#[Parsed_volumedetect_0 @ 0x3a5f900] mean_volume: -48.0 dB
#[Parsed_volumedetect_0 @ 0x3a5f900] max_volume: -27.1 dB
#[Parsed_volumedetect_0 @ 0x3a5f900] histogram_27db: 157
#[Parsed_volumedetect_0 @ 0x3a5f900] histogram_28db: 5479
#[Parsed_volumedetect_0 @ 0x3a5f900] histogram_29db: 41345
#[Parsed_volumedetect_0 @ 0x3a5f900] histogram_30db: 72813
#[Parsed_volumedetect_0 @ 0x3a5f900] histogram_31db: 121176
ffmpeg -i NTE-5380.aac -af "volumedetect" -vn -sn -dn -f null /dev/null

#adjust volume 
ffmpeg -i NTE-5380.aac -filter:a volume=27dB  NTE-5380.normalized.aac

#find the 1st IFrame after the start and cut the Elementary stream there
#For this, we take pkt_pos
ffprobe -show_frames -show_packets NTE-5380.h264

dd bs=127575540 if=NTE-5380.h264 of=NTE-5380.body.h264 skip=1

#create the intro, use the mkv, not the h264 stream to get the timestamps
ffmpeg -y \
-loop 1 -framerate 30 -t 5 -i ../intros/intro_NTE_5380.png \
-i ../videos/NTE-5380.mkv \
 -filter_complex "[0:v]format=pix_fmts=yuva420p,fade=t=out:st=3:d=1:alpha=1[intro];      [1:v]trim=402:408.33,setpts=PTS-STARTPTS+3/TB[content];       [content][intro]overlay[merged];" -b:v 3M NTE-5380.intro.h264

#assemble intro and body
cat NTE-5380.intro.h264 NTE-5380.body.h264 > NTE-5380.merged.h264

#encode the audio stream, with the fade in and volume filter
 ffmpeg -y -i ../videos/NTE-5380.mkv -f lavfi -i anullsrc -filter_complex "[0:a]channelsplit=channel_layout=stereo:channels=FR,atrim=402,asetpts=PTS-STARTPTS,afade=t=in:st=0:d=1,volume=27dB,adelay=3s" NTE-5380.merged.aac

#merge audio and video streams
ffmpeg -i NTE-5380.merged.aac -r 30 -i NTE-5380.merged.h264 -vcodec copy -acodec copy NTE-5380.merged.mp4











#Old stuff
ffmpeg -y -loglevel verbose -noaccurate_seek -ss 409 -i ../videos/NTE-5380.mp4 -vcodec copy -an body.mp4
#ffmpeg -y -loglevel verbose -ss 408.33 -i ../videos/NTE-5380.mp4 -vcodec copy -acodec copy body.mp4

ffmpeg -y -f concat -i mylist.txt -c copy assembled.mp4

 #extract right channel
#ffmpeg -i NTE-5380.mkv -map_channel 0.1.1 NTE-5380.aac

#extract AAC elementary stream
#ffmpeg -i NTE-5380.mkv -acodec copy NTE-5380.aac
