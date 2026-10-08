"""
Checks that an official MinMap APK contains exactly the code in this repository.

Build the app from this source (see README), then run:

    python tools/verify-apk.py MinMap-1.0.1.apk app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk

Every file inside the two APKs is compared, except META-INF/ (the release signature, which only the
official build has). It also prints the official APK's SHA-256, to check against SHA256SUMS.
"""
import hashlib
import sys
import zipfile


def contents(path):
    with zipfile.ZipFile(path) as apk:
        return {name: hashlib.sha256(apk.read(name)).hexdigest()
                for name in apk.namelist() if not name.startswith("META-INF/")}


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    official, built = sys.argv[1], sys.argv[2]
    with open(official, "rb") as f:
        print("SHA-256 of", official + ":", hashlib.sha256(f.read()).hexdigest())
    a, b = contents(official), contents(built)
    different = sorted(n for n in a.keys() | b.keys() if a.get(n) != b.get(n))
    if different:
        print(f"MISMATCH: {len(different)} of {len(a)} files differ, for example:")
        for name in different[:10]:
            print("  ", name)
        sys.exit(1)
    print(f"MATCH: all {len(a)} files in the official APK are identical to your build of this source.")


if __name__ == "__main__":
    main()
