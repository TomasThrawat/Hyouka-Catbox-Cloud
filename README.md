# Hyouka Catbox Cloud

Native Kotlin Android client for the documented Catbox API.

## Current features

- Multi-file uploads with Catbox's `fileupload` request.
- URL uploads with Catbox's `urlupload` request.
- Optional Catbox userhash for account-linked requests.
- Local upload history on the device.
- Download buttons using Android DownloadManager.
- Delete buttons using Catbox's documented `deletefiles` request when a userhash is available.
- Official Catbox API endpoint is fixed to `https://catbox.moe/user/api.php`.

## My Uploads

The app stores a local history of successful uploads.

The Catbox API documentation provides upload, URL upload, delete-files, and album request types, but does not document a request for listing all files belonging to a userhash. Therefore the app does not pretend that a GET request to the Catbox API can synchronize a server-side account file list.

## Authentication

For anonymous uploads, no userhash is sent.

For account-linked requests, enter the Catbox userhash exactly as provided by Catbox. The app can save it locally if you enable the checkbox.

The documented Catbox API uses `userhash` for account requests. It does not use a generic `Authorization: Bearer`, `X-API-Key`, or `?api_key=` scheme.

## Build

The GitHub Actions workflow builds a debug APK with Java 17 and Gradle 8.7.
