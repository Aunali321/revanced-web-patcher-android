# ReVanced Web Patcher - Android

Android app version of the ReVanced Web Patcher backend server.

## Features

- Runs Ktor backend server locally on your Android device
- No desktop/laptop required
- Simple UI: Start/Stop server button
- Shows server URL to enter in web frontend
- Background service keeps server running

## Usage

1. Install the APK
2. Open the app
3. Tap "Start Server"
4. Open [https://rv.aun.rest](https://rv.aun.rest) in your browser
5. Enter server URL (shown in app): `http://localhost:3000`
6. Patch your APKs!

## Requirements

- Android 7.0 (API 24) or higher
- At least 2GB RAM recommended
- Storage space for APKs and patches

## Building

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## License

AGPL-3.0 - See LICENSE file
