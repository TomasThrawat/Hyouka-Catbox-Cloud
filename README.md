# Hyouka Catbox Cloud

Native Kotlin Android client for Catbox uploads and a configurable account-files API.

## Current features

- Multi-file upload to Catbox.
- Optional Catbox userhash for uploads.
- Save the userhash locally on the device.
- Real Download buttons using Android DownloadManager.
- My Files is now API-driven, not based on local upload history.
- Configurable API URL, API key, and authentication style.

## Account Files API

The app expects the API you provide to return JSON containing a file array.

Accepted examples:

[{"name":"photo.jpg","url":"https://example.com/photo.jpg"}]

or:

{"files":[{"name":"photo.jpg","url":"https://example.com/photo.jpg"}]}

Common fields are supported: url, link, download_url, filename, name, file_name, and size.

Authentication options:

- Authorization: Bearer <key>
- X-API-Key: <key>
- Query: ?api_key=<key>

Enter the API URL and key in the app when you receive them.

## Important

The official Catbox API does not document an account-file-list request by userhash. Therefore the API-driven My Files screen is intentionally separated from the Catbox upload API.

No Catbox credentials are bundled into the APK.
