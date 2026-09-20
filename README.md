# Hyouka Catbox Cloud

Native Kotlin Android client for Catbox.

## Features

- Multi-file upload to Catbox.
- Optional Catbox userhash for uploads.
- Local upload history for previously returned Catbox links.
- Scrollable, selectable result links.
- GitHub Actions debug APK builds.

## My files behavior

The app no longer calls an unsupported `getaccount` request.

The current Catbox API documentation lists file upload, URL upload, file deletion, and album operations. It does not document an account-file-list request. Therefore, **My files** in this app shows the links returned by successful uploads and saved locally on the device.

No Catbox credentials are bundled in the APK.
