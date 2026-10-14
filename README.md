# MinMap for the Tesla browser

A static web version of MinMap for the car's browser:
- the Mapbox Standard map with MinMap's looks, search, and a route with turn-by-turn;
- a panel that plays what your phone sends it.

There's no server. Every file here is static, so it's served straight from GitHub Pages.

## Use

1. Open the site in the car and paste a Mapbox public token (`pk.`). It is kept only in that browser.
   To use only the phone video and skip the map, open `#watch`.
2. Tap the screen button. Accept the passenger-only notice and scan the QR code with your phone.
3. On the phone, there are two ways to send video:
   - **Mirror the whole screen**, for YouTube or any app. This needs the MinMap Mirror Android
     app, because phone browsers can't capture the screen. The car's play, pause and seek buttons
     control YouTube once you allow car controls in the app.
   - **Choose a video file**, right in the phone's browser (Android or iPhone).

The video goes straight from the phone to the car over WebRTC. To find each other, the phone and
the car post two short notes through public Nostr relays: an offer and an answer. Those notes are
encrypted with the pairing code, so the relays can't read them, and the video never passes
through the relays. See [PROTOCOL.md](PROTOCOL.md).

Tips:
- The most reliable setup is the car on the phone's hotspot.
- The car's browser may stop video while the car is in Drive.

**Passenger use only.**

## Files

- `js/car.js`: the map, search, route, guidance and style menu.
- `js/watch.js`: the car side of phone video.
- `js/phone.js`: the phone side in a browser.
- `js/link.js`: the pairing protocol.
- `vendor/`: Mapbox GL JS 3.31.0, @noble/secp256k1 3.2.0 and qrcode-generator 1.5.2.
  Their licences are in `vendor/LICENSES.txt`.
