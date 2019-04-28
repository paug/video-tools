#! /usr/bin/env python

import requests
import os
import subprocess
import sys
import json
sys.path.append(os.path.relpath("resources/"))
from uploader import *
#os.system("python resources/uploader.py")

IMG_BASE_NAME = 'intro_'
IMG_EXTENSION = '.png'
IMG_DEST_FOLDER = 'intros/'

THUMB_BASE_NAME = "thumbnail_"
THUMB_EXTENSION = '.png'
THUMB_DEST_FOLDER = 'thumb/'

VIDEO_SRC_EXTENSION = '.mkv'
VIDEO_SRC_FOLDER = 'src/'

VIDEO_OUT_EXTENSION = '.mp4'
VIDEO_OUT_FOLDER = 'out/'

def get_img_dest(uid):
	return IMG_DEST_FOLDER + IMG_BASE_NAME + uid + IMG_EXTENSION

def get_thumb_dest(uid):
	return THUMB_DEST_FOLDER + THUMB_BASE_NAME + uid + THUMB_EXTENSION

def get_video_src(uid):
	return VIDEO_SRC_FOLDER + uid + VIDEO_SRC_EXTENSION

def get_video_out(uid):
	return VIDEO_OUT_FOLDER + uid + VIDEO_OUT_EXTENSION

def download_intro(uid):
	img_url_base = 'https://raw.githubusercontent.com/loutry/tmpAndroidMakersVisuals/2019/INTRO/'

	img_url = img_url_base + IMG_BASE_NAME + str(uid).replace("-", "_") + IMG_EXTENSION
	img_dest = get_img_dest(uid)
	with open(img_dest, 'wb') as f:
	    f.write(requests.get(img_url).content)
	    print('Downloaded intro for ' + img_url)

def download_thumbnail(uid):
	img_url_base = 'https://raw.githubusercontent.com/loutry/tmpAndroidMakersVisuals/2019/THUMBNAIL/'

	img_url = img_url_base + THUMB_BASE_NAME + str(uid).replace("-", "_") + THUMB_EXTENSION
	img_dest = get_thumb_dest(uid)
	with open(img_dest, 'wb') as f:
	    f.write(requests.get(img_url).content)
	    print('Downloaded thumbnail for ' + uid)

def get_trim(video_infos, start):
	key = "Video cut Begin" if start else "Video cut End"
	raw_trim = video_infos[key]
	if raw_trim:
		splitted = raw_trim.split(":")
		if len(splitted) == 2:
			minutes = int(splitted[0])
			seconds = int(splitted[1])
			prefix = "start=" if start else ":end="
			return prefix + str(minutes * 60 + seconds)
	else:
		if start:
			return "start=0"
		else:
			return ""

def get_audio_channel(video_infos):
	key = "Audio stereo side"
	side = video_infos[key]
	if not side:
		side = "right"
	return "c0=c1" if side == "right" else "c0=c1"

def generate_video(uid, video_infos):

	raw_cmd = '''ffmpeg -loop 1 -framerate 24 -t 5 -i {intro} -f lavfi -t 0.1 \
        -i anullsrc -i {video} -filter_complex \
        "[0:v]format=pix_fmts=yuva420p,fade=t=out:st=3:d=1:alpha=1[intro];\
        [2:v]trim={trim_start}{trim_end},setpts=PTS-STARTPTS[contentNotAligned];\
        [contentNotAligned]setpts=PTS+3/TB[content];\
        [content][intro]overlay[merged];\
        [2:a]atrim={trim_start}{trim_end},asetpts=PTS-STARTPTS[audioNotAligned];\
        [audioNotAligned]pan=mono|{audio_channel}[audioMono];\
        [audioMono]asetpts=PTS+3/TB[audio];\
        [merged][audio]concat=n=1:v=1:a=1" \
        -b:v 15M {output}'''

	intro = get_img_dest(uid)
	video_src = get_video_src(uid)
	video_out = get_video_out(uid)
	trim_start = get_trim(video_infos, start=True)
	trim_end = get_trim(video_infos, start=False)
	audio_channel = get_audio_channel(video_infos)

	cmd = raw_cmd.format(intro=intro, video=video_src, output=video_out,
		trim_start=trim_start, trim_end=trim_end, audio_channel=audio_channel)

	subprocess.call(cmd, shell=True)

def fetch_src_uids():
	uids = []
	files = [f for f in os.listdir(VIDEO_SRC_FOLDER)]
	print(files)
	for f in files:
		if VIDEO_SRC_EXTENSION in f:
			uid = f.replace(VIDEO_SRC_EXTENSION, "")
			uids.append(uid)

	return uids

def fetch_out_uids():
	uids = []
	files = [f for f in os.listdir(VIDEO_OUT_FOLDER)]
	print(files)
	for f in files:
		if VIDEO_OUT_EXTENSION in f:
			uid = f.replace(VIDEO_OUT_EXTENSION, "")
			uids.append(uid)

	return uids

def create_video(uid, video_infos):
	download_intro(uid)
	generate_video(uid, video_infos)

def config_generate():
	if not os.path.exists(IMG_DEST_FOLDER):
		os.makedirs(IMG_DEST_FOLDER)
	if not os.path.exists(VIDEO_OUT_FOLDER):
		os.makedirs(VIDEO_OUT_FOLDER)

def parse_json():
	with open('resources/input_data.json') as f:
		return json.load(f)

def get_video_infos(videos_infos, uid):
	for infos in videos_infos:
		if infos['da'] == uid:
			return infos
	print("video info not found for uid: " + uid)


def upload_video(uid, video_infos):
	video_id = ""
	download_thumbnail(uid)
	title = video_infos["Youtube title"]
	desc = video_infos["Desc"]
	video_id = upload(get_video_out(uid), get_thumb_dest(uid), title, desc, 
		keywords=video_infos["tags"],
		cat=28,
		privacy_status="private")

	return video_id

def config_upload():
	if not os.path.exists(THUMB_DEST_FOLDER):
		os.makedirs(THUMB_DEST_FOLDER)
	if not os.path.exists(VIDEO_OUT_FOLDER):
		os.makedirs(VIDEO_OUT_FOLDER)
	#os.makedirs(THUMB_DEST_FOLDER, exist_ok = True)
	#os.makedirs(VIDEO_OUT_FOLDER, exist_ok = True)

def main():
	if '--help' in sys.argv:
		print("--generate to generate the videos with their matching intros")
		print("--upload to upload generated videos to Youtube")
		return

	videos_infos = parse_json()
	if '--generate' in sys.argv:
		print('Generating videos')
		config_generate()
		for uid in fetch_src_uids():
			video_infos = get_video_infos(videos_infos, uid)
			if video_infos:
				create_video(uid, video_infos)
	if '--upload' in sys.argv:
		print('Uploading videos')
		#config_upload()
		video_ids = {}
		overwritten_video_ids = {}
		with open('uploadedVideos.json', 'r+') as f:
			try:
				video_ids = json.load(f)
			except:
				pass
			for uid in fetch_out_uids():
				video_infos = get_video_infos(videos_infos, uid)
				if video_infos:
					video_id = upload_video(uid, video_infos)
					if uid in video_ids:
						overwritten_video_ids[uid] = video_ids[uid]
					video_ids[uid] = video_id

			f.seek(0)
			f.write(json.dumps(video_ids))
			f.truncate()
		with open('replacedVideos.json', 'w') as f:
			f.write(json.dumps(overwritten_video_ids))

main()



