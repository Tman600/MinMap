# MinMap

A smart, minimal map for Android, built on Mapbox: 3D landmarks, turn-by-turn navigation, and a fully customizable style editor.

Download the latest APK from [Releases](https://github.com/Tman600/MinMap/releases/latest). What's new in each version is in the [changelog](CHANGELOG.md).

From version 1.3, MinMap keeps itself up to date: the **Updates** tab in its settings menu checks this page's releases and installs new ones (checked against their published checksum and MinMap's signing key). Earlier versions need 1.3 installed by hand once.

## Verify your download

Each release lists the SHA-256 checksum of its APK, also in [`SHA256SUMS`](SHA256SUMS). The latest:

```
d1fd2f50cb0dec9231cf922c85539e31bd0758dcb062cccf1ce6943a4e6c852d  MinMap-1.3.apk
```

Check it after downloading:

- Windows: `certutil -hashfile MinMap-1.3.apk SHA256`
- macOS: `shasum -a 256 MinMap-1.3.apk`
- Linux: `sha256sum MinMap-1.3.apk`

Official releases are signed with the MinMap key. The signing certificate's SHA-256 fingerprint is:

```
5e:d5:79:f2:53:c8:b0:a7:77:2c:c9:f5:ec:46:cb:c4:6c:93:3b:db:04:e3:8f:47:59:57:e6:61:ed:04:19:1f
```

Android checks this on every update: an update signed with any other key won't install over MinMap.

## Source code

This repository is the exact source of each release: tag `v1.3` is what MinMap 1.3 was built from (and `v1.0.1` what 1.0.1 was).
The build is reproducible, so you can confirm the APK contains this code and nothing else:

1. Install [Android Studio](https://developer.android.com/studio) (or JDK 17+ and the Android SDK).
2. Check out the release tag and build it: `git checkout v1.3`, then `./gradlew assembleRelease` (`gradlew.bat` on Windows).
3. Compare your build with the official APK:
   `python tools/verify-apk.py MinMap-1.3.apk app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`

It should report every file as identical. Only the signature differs, since only official builds are signed.

## License

Copyright (c) 2026 Tman600. All rights reserved. The source is published so it can be reviewed and
verified, not reused: you may read it and build it to check a release, but not copy, modify,
redistribute or use it in other projects. See [LICENSE](LICENSE).
