# Changelog

## 1.3

**Search for places**
- Search now finds businesses, landmarks and other places, not just addresses and towns.
- Results near where the map is looking come first, and each shows how far it is from you.
- Categories such as "coffee" or "gas station" open into a list of the nearest places of that kind.

**In-app updates**
- New **Updates** tab in the settings menu: your version, the latest release and its notes, and a
  **Download and install** button.
- Checks for updates on its own (at most twice a day, when MinMap opens) and can install them by
  itself over Wi-Fi. A red dot on the settings button means an update is waiting. Both can be
  switched off.
- An update is installed only if it matches the SHA-256 checksum published with its release and is
  signed with MinMap's key. A dropped download picks up where it stopped.
- Android asks you to confirm the first update; after that, updates can install without asking.

## 1.2 – Starbase launches in stages

- **Stacking:** the booster and ship are rolled out beside the launch mount, and the tower's
  chopsticks come down, lift each one and set it on the mount, ship on top of booster. The
  chopsticks carriage now rides up and down the tower. Stacking isn't published anywhere, so this
  runs at typical times (about a day before launch).
- **Fuelling:** the steel frosts over as propellant goes in and both stages vent, more so once
  the engines start chilling.
- **Launch:** the deluge throws up a cloud of steam, the engines light, and the rocket rolls and
  leans east over the Gulf as it climbs.
- **Flight:** the ship flies on after stage separation with its own engine flame; the booster
  flips for its boostback burn and comes down on its landing burn into the tower's arms, or out
  to its splashdown in the Gulf.
- Each flight follows its own published timeline (propellant load, ignition, stage separation,
  boostback and landing burns, splashdown distance) where Launch Library lists one.
- A pill at the top of the map, when you're looking at Starbase, shows the countdown around a
  real launch, or offers to **replay the last flight** with a mission clock and an End button.

## 1.1 – Starbase in 3D

- Starbase's two orbital launch pads are on the map in 3D: launch towers, chopstick arms and
  launch mounts.
- Around a Starship flight, the full stack stands on the pad it's launching from. At liftoff the
  ship's quick-disconnect arm swings clear, the rocket climbs and heads east over the Gulf, and the
  booster then either comes back to be caught by the tower's arms or splashes down, depending on
  the flight plan.
- Launch times, status and plans come from Launch Library 2 by The Space Devs, checked only while
  the map is looking at Starbase and only as often as a launch is near.
- Mapbox's plain building blocks are hidden at the launch site so the detailed towers show, and
  the pads glow as if floodlit on Dusk and Night maps.
- The rocket's path is an average of recent flights fitted to each flight's timings, not live
  tracking.

## 1.0.1

- First public release.
- Sign in with your own Mapbox account (a free one is enough); no key is built into the app.
- 3D maps with landmarks and live lighting (Dawn, Day, Dusk, Night).
- Turn-by-turn driving navigation with Google Maps' guidance on MinMap's own map.
- Style editor: presets, per-feature colours, show/hide layers, and style JSON import/export.

---

Starbase and Starship 3D models: munim-maps-vehicles by Munim, Inc. (Apache License 2.0), split into
moving parts for MinMap. Launch data: Launch Library 2 by The Space Devs.
