# RF Scout

Native Android app that scans the radio signals your phone can actually measure, estimates how far
away each one is, and shows where it probably is on a map with a direction arrow.

## What it scans

| Protocol | Source | Distance | Direction |
|---|---|---|---|
| Wi-Fi (2.4 / 5 / 6 GHz) | `WifiManager` scan results | RSSI path-loss model | walk-and-fit |
| Bluetooth LE | `BluetoothLeScanner` | RSSI path-loss model | walk-and-fit |
| Bluetooth Classic | discovery (`ACTION_FOUND`) | RSSI path-loss model | walk-and-fit |
| Cellular (LTE / 5G NR / UMTS / GSM) | `TelephonyManager` cell info | none (towers are km away) | none |

Not possible from a normal Android app: sub-GHz (433/868/915 MHz), ADS-B, FM/AM, Zigbee, LoRa,
and similar. Those need an external radio (RTL-SDR, ESP32, CC1101) talking to the phone over USB or
Bluetooth. NFC has no signal strength. UWB ranging needs a cooperating UWB peer.

## How distance and direction work

- **Distance** comes from one RSSI value and a log-distance path-loss model. Free space is
  about right, indoors it is often off by 2x or more. Treat it as near / medium / far.
- **Direction**: a single phone antenna cannot tell where a signal comes from. While you walk, the app
  records RSSI against GPS position and fits the transmitter location that best explains the
  readings. You need to move 10 m or more, and a curved path beats a straight line (a straight
  path leaves a left/right ambiguity, which the app flags). It works best outdoors where GPS is good.
- The arrow on the panel points at the fit relative to where the phone faces, using the
  rotation-vector sensor and magnetic declination.

## Build

The project is set up to build on GitHub Actions, no local Android SDK needed:

1. Push this folder to a GitHub repo (branch `main`).
2. Open the **Actions** tab, wait for **Build APK**, and download the `rfscout-debug-apk` artifact.
   Or tag a release (`git tag v0.1.0 && git push --tags`) to get a direct APK download link.
3. On the phone, open the APK and allow installs from this source when asked.

Local build (Android Studio, or a JDK 17 and the Android SDK): `./gradlew assembleDebug`.

## Using it

1. Tap **Start** and grant location, Bluetooth, and phone permissions. Location services must be on.
2. **Signals** tab: everything in range, strongest first. Tap one to track it.
3. Walk around. The bottom panel shows RSSI, model distance, and, once enough points are collected,
   a fitted distance and bearing with an arrow.
4. **Map** tab: your position and accuracy, your walked path, sample points coloured by signal
   strength, a model range ring, a flag for each estimated transmitter, and a line to the one you track.
5. **Track: One / Track: All** button: One shows only the selected signal on the map; All shows
   every signal's flag. With nothing selected all flags show either way.
6. **Flags** are labeled by type (Wi-Fi, BLE, BT, Cell). Tap one for the details page: RSSI graph,
   frequency, distance guess, position fit, and a Track button. Long-press a list row for the same page.
   Cell towers have no position, so their flags sit in a row beside your dot.
7. **Help** button: illustrated explanation of how the app works.
8. **ALPR layer** button: shows license-plate-reader cameras that volunteers have mapped in
   OpenStreetMap (the data projects like DeFlock collect), loaded through the Overpass API for the
   part of the map on screen (zoom in to street level). Red dots are cameras; the wedge shows the
   direction a camera faces when tagged. Tap a dot for its tags. Data is cached for a week.
   Needs internet. © OpenStreetMap contributors (ODbL).
9. **Watchlist**: Wi-Fi names (`Flock-…` and a few others) and the Bluetooth company ID `0x09C8`
   that people have reported on Flock cameras get an orange list row and a red `ALPR?` flag.
   These patterns are community-reported and unreliable: a hit is a lead, not proof, and many
   cameras broadcast nothing.
10. **Sightings** button: log of watchlist hits with the best position known, whether a mapped
    camera is within 150 m, share as CSV, and a link to the DeFlock report page.

11. **Persistent camera data**: downloaded ALPR camera areas are kept in the app's storage, load at
    startup (even offline) and refresh when older than a week. The layer on/off choice is remembered and
    the status line shows how old the data is.
12. **Tower lookup**: with a free [OpenCelliD](https://opencellid.org/register) API key (Settings), the cell
    you are connected to is looked up by its full ID (MCC, MNC, TAC/LAC, cell ID). It gets a flag, distance
    and arrow, plus a coverage circle. This is a crowd-sourced estimate. Neighbor cells only expose a
    physical ID and frequency, so they stay signal-only. 5G NR cells are shown but not looked up. Results
    are cached.
13. **Screen-off scanning**: the **Screen-off** button runs a foreground service (ongoing notification with a
    Stop button, wake lock). Android blocks unfiltered Bluetooth scans with the screen off, so only item
    trackers and the watchlist company ID are scanned then; Wi-Fi scans are throttled by Android in the background.
14. **Calibrate**: on a signal's details page, stand a known distance from the device and tap Calibrate.
    One distance tunes the level; two distances at least 2x apart also tune the path-loss exponent. Saved per
    protocol; reset in Settings.
15. **Export**: shares `readings.csv` (time-stamped readings with phone position), `signals.csv` (one row per
    signal with vendor, position and source) and `session.gpx` (your walk, estimated positions, sightings).
16. **Vendor names**: Bluetooth company IDs from a built-in table; Wi-Fi/Bluetooth addresses from the IEEE
    registry, which the build downloads into the app (`oui.csv`; if the download fails, only company IDs are
    named). Private/random addresses are labeled as such.
17. **Unknown tracker alert**: AirTag-style (Apple Find My), Samsung SmartTag, Tile and Google Find Hub
    advertisements are recognized. A tag that stays in range for 8 minutes while you move 300 m away from
    where you first saw it raises a notification and vibration. "This is mine" silences a tag. These formats are
    community-reported, tags that rotate their address can evade detection, and a missing alert is not a guarantee.

18. **New look**: dark panels with cyan accents. **Signals / Radar / Map** tabs. The **Radar** tab puts you in the
    centre (up = where the phone points) with a dot per signal; filled dots have a direction estimate, hollow dots
    on the lower arc have distance only. Signal cards show type, strength, vendor, a *category guess* (from name and
    company, can be wrong) and first/last seen; the list shows the strongest 50. Secondary actions (ALPR layer,
    Sightings, Export, Screen-off, Settings) moved into the **More** menu.

Without Screen-off scanning, the app stops scanning when it goes to the background and resumes when you return.

## Known limits

- Android throttles Wi-Fi scans (about 4 per 2 minutes in the foreground). For continuous Wi-Fi
  scanning, turn off **Wi-Fi scan throttling** in Developer options.
- Bluetooth Classic discovery competes with BLE scanning for the radio. Untick it if BLE gets sparse.
- BLE devices with randomized MAC addresses show up as new devices when the address rotates.
- Map tiles need internet (OpenStreetMap). The scanning itself works offline.

## Ideas for later

- Wi-Fi RTT (802.11mc) for real metre-level ranging to supporting access points
- GNSS satellite signal view
- External SDR / ESP32 bridge for sub-GHz and 2.4 GHz protocols
