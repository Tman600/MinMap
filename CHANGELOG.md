# Changelog

## 1.6

- **Voice:** tap the mic in the search bar and say where to, like "navigate to Starbucks" or "take
  me to the nearest gas station", and MinMap starts the drive. "Stop navigation" ends it. A place
  you've driven to before is matched by name without a search. A headset's voice button works too.
- **Android Auto: when MinMap doesn't show up in the car's app list.** Android Auto now only lists
  apps it believes came from Google Play, even with "Unknown sources" on, and MinMap installs its
  own updates. To make it show, install the APK from a computer with the Play Store named as the
  installer (your data is kept):
  `adb install -r -i com.android.vending MinMap-1.6.apk`. An update installed from inside MinMap
  undoes this, so repeat it after updating.
- **Tesla Fort Worth** (launched 2 November, about 143 sq mi from Saginaw to Benbrook) is in the
  built-in robotaxi areas, traced from Tesla's announcement map.

## 1.5

MinMap's biggest release yet.

- **HD roads:** close in, roads show their lane lines, turn arrows, crossings and stop lines, and
  raised roads, bridges and tunnels stand in 3D, in the cities Mapbox has mapped in that detail.
  On by default; switch it off under Show / Hide ("Lane detail").
- **Android Auto (preview)** has been added. To enable it: open Android Auto's settings on your
  phone, tap "Version" ten times to turn on developer mode, then in the ⋮ menu open Developer
  settings and turn on "Unknown sources". MinMap then shows up in the car's app list.
- **Starship** and **Super Heavy** have been added back and now stand off Highway 4 by the Mega
  Bays.
- Mapbox Maps SDK 11.33.
- **Newer robotaxi areas built in:** Tesla's larger Houston area and Circuit of the Americas in
  Austin, and Waymo's October expansions in Los Angeles, Orlando and San Diego. MinMap still checks
  Robotaxi Tracker daily and uses its data once it's newer.

## 1.4

- **When Mapbox pauses your account:** accounts made without a card are on Mapbox's free demo, and
  once its small monthly allowance is used up Mapbox stops sending maps and search results. MinMap
  now says so in a panel, explains that adding a card on Mapbox lifts the demo limit, and lists
  what the standard free tier includes each month (the map on 25,000 phones, 500 searches, 50,000
  nearby lists, 100,000 routes and 100,000 street lookups), with a link to Mapbox and a way to
  switch accounts. Signing in with an account in this state says the same.
- **Lighter on your Mapbox account:** search waits for three letters and remembers recent results,
  a place you've already tapped isn't looked up again, reroutes during a drive are spaced further
  apart, and drive times for suggested places refresh half-hourly.
- **See your use:** the settings menu shows MinMap's searches, routes and lookups on your account
  this month.
- **Starship launches removed:** the launch animation, countdown and replays added in 1.1 and 1.2
  are gone. They had grown overly complex and took away from the point of MinMap, which is being a
  map. Starbase's launch towers, chopsticks and mounts stay on the map in 3D, and MinMap no longer
  checks for launches.

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
