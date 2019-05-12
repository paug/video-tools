#! /usr/bin/env python

import httplib
import httplib2
import os
import random
import sys
import time
import argparse

from apiclient.discovery import build
from apiclient.errors import HttpError
from apiclient.http import MediaFileUpload
from oauth2client.client import flow_from_clientsecrets
from oauth2client.file import Storage
from oauth2client.tools import argparser, run_flow
from google_auth_oauthlib.flow import InstalledAppFlow


# Explicitly tell the underlying HTTP transport library not to retry, since
# we are handling retry logic ourselves.
httplib2.RETRIES = 1

# Maximum number of times to retry before giving up.
MAX_RETRIES = 10

# Always retry when these exceptions are raised.
RETRIABLE_EXCEPTIONS = (httplib2.HttpLib2Error, IOError, httplib.NotConnected,
  httplib.IncompleteRead, httplib.ImproperConnectionState,
  httplib.CannotSendRequest, httplib.CannotSendHeader,
  httplib.ResponseNotReady, httplib.BadStatusLine)

# Always retry when an apiclient.errors.HttpError with one of these status
# codes is raised.
RETRIABLE_STATUS_CODES = [500, 502, 503, 504]

# The CLIENT_SECRETS_FILE variable specifies the name of a file that contains
# the OAuth 2.0 information for this application, including its client_id and
# client_secret. You can acquire an OAuth 2.0 client ID and client secret from
# the Google Developers Console at
# https://console.developers.google.com/.
# Please ensure that you have enabled the YouTube Data API for your project.
# For more information about using OAuth2 to access the YouTube Data API, see:
#   https://developers.google.com/youtube/v3/guides/authentication
# For more information about the client_secrets.json file format, see:
#   https://developers.google.com/api-client-library/python/guide/aaa_client_secrets
CLIENT_SECRETS_FILE = "resources/client_secrets.json"

# This OAuth 2.0 access scope allows an application to upload files to the
# authenticated user's YouTube channel, but doesn't allow other types of access.
SCOPES = ['https://www.googleapis.com/auth/youtube']
API_SERVICE_NAME = "youtube"
API_VERSION = "v3"

# This variable defines a message to display if the CLIENT_SECRETS_FILE is
# missing.
MISSING_CLIENT_SECRETS_MESSAGE = """
WARNING: Please configure OAuth 2.0

To make this sample run you will need to populate the client_secrets.json file
found at:

   %s

with information from the Developers Console
https://console.developers.google.com/

For more information about the client_secrets.json file format, please visit:
https://developers.google.com/api-client-library/python/guide/aaa_client_secrets
""" % os.path.abspath(os.path.join(os.path.dirname(__file__),
                                   CLIENT_SECRETS_FILE))

VALID_PRIVACY_STATUSES = ("public", "private", "unlisted")

#def get_authenticated_service():
#  flow = InstalledAppFlow.from_client_secrets_file(CLIENT_SECRETS_FILE, SCOPES)
#  credentials = flow.run_console()
#  return build(API_SERVICE_NAME, API_VERSION, credentials = credentials)

def get_authenticated_service():
  flow = InstalledAppFlow.from_client_secrets_file(CLIENT_SECRETS_FILE, SCOPES)

  storage = Storage("%s-oauth2.json" % sys.argv[0])
  credentials = storage.get()

  if credentials is None or credentials.invalid:
    credentials = flow.run_console()

  print(str(credentials.refresh_token))
  print(str(credentials.token))

  return build(API_SERVICE_NAME, API_VERSION, credentials = credentials)

def initialize_upload(youtubeService, options):
  tags = None
  if options.keywords:
    tags = options.keywords.split(",")

  body=dict(
    snippet=dict(
      title=options.title,
      description=options.description,
      tags=tags,
      categoryId=options.category
    ),
    status=dict(
      privacyStatus=options.privacyStatus
    )
  )

  # Call the API's videos.insert method to create and upload the video.
  insert_request = youtubeService.videos().insert(
    part=",".join(body.keys()),
    body=body,
    # The chunksize parameter specifies the size of each chunk of data, in
    # bytes, that will be uploaded at a time. Set a higher value for
    # reliable connections as fewer chunks lead to faster uploads. Set a lower
    # value for better recovery on less reliable connections.
    #
    # Setting "chunksize" equal to -1 in the code below means that the entire
    # file will be uploaded in a single HTTP request. (If the upload fails,
    # it will still be retried where it left off.) This is usually a best
    # practice, but if you're using Python older than 2.6 or if you're
    # running on App Engine, you should set the chunksize to something like
    # 1024 * 1024 (1 megabyte).
    media_body=MediaFileUpload(options.file, chunksize=-1, resumable=True)
  )

  return resumable_upload(insert_request)

# This method implements an exponential backoff strategy to resume a
# failed upload.
def resumable_upload(insert_request):
  response = None
  error = None
  retry = 0
  while response is None:
    try:
      print("Uploading file...")
      status, response = insert_request.next_chunk()
      if 'id' in response:
        print("Video id '%s' was successfully uploaded." % response['id'])
      else:
        exit("The upload failed with an unexpected response: %s" % response)
    except HttpError as e:
      if e.resp.status in RETRIABLE_STATUS_CODES:
        error = "A retriable HTTP error %d occurred:\n%s" % (e.resp.status,
                                                             e.content)
      else:
        raise
    except RETRIABLE_EXCEPTIONS as e:
      error = "A retriable error occurred: %s" % e

    if error is not None:
      print(error)
      retry += 1
      if retry > MAX_RETRIES:
        exit("No longer attempting to retry.")

      max_sleep = 2 ** retry
      sleep_seconds = random.random() * max_sleep
      print("Sleeping %f seconds and then retrying..." % sleep_seconds)
      time.sleep(sleep_seconds)
  return response['id']

def upload_thumbnail(youtubeService, video_id, file):
  youtubeService.thumbnails().set(
    videoId=video_id,
    media_body=file
  ).execute()


def upload(youtubeService, video_file, thumbnail_file, title, desc, keywords="", cat=22, privacy_status="private"):
  argparser = argparse.ArgumentParser(conflict_handler='resolve')
  argparser.add_argument("--file", help="Video file to upload", default=video_file)
  argparser.add_argument("--title", help="Video title", default=title)
  argparser.add_argument("--description", help="Video description", default=desc)
  argparser.add_argument("--category", default=cat,
    help="Numeric video category. " +
      "See https://developers.google.com/youtube/v3/docs/videoCategories/list")
  argparser.add_argument("--keywords", help="Video keywords, comma separated",
    default=keywords)
  argparser.add_argument("--privacyStatus", choices=VALID_PRIVACY_STATUSES,
    default=privacy_status, help="Video privacy status.")
  argparser.add_argument("--upload", action='store_true')
  argparser.add_argument("--generate", action='store_true')
  args = argparser.parse_args()

  video_id = ""
  try:
      video_id = initialize_upload(youtubeService, args)
      if video_id:
        upload_thumbnail(youtubeService, video_id, thumbnail_file)
  except HttpError as e:
    print "An HTTP error %d occurred:\n%s" % (e.resp.status, e.content)

  return video_id

def update_snippet(youtubeService, video_id, thumbnail_file, title, desc, keywords, cat=22):

  print("Updating snippet for " + video_id + " (" + title + ")")

  snippet=dict(
      title=title,
      description=desc,
      tags=keywords,
      categoryId=cat
    )

  videos_update_response = youtubeService.videos().update(
    part='snippet',
    body=dict(
      snippet=snippet,
      id=video_id
    )).execute()

  upload_thumbnail(youtubeService, video_id, thumbnail_file)

def update_privacy(youtubeService, video_id, privacy_status):

  print("Updating privacy for " + video_id + " to " + privacy_status)

  status=dict(
      privacyStatus=privacy_status
  )

  videos_update_response = youtubeService.videos().update(
    part='status',
    body=dict(
      status=status,
      id=video_id
    )).execute()

  print(str(videos_update_response))
