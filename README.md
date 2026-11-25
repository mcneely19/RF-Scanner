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
| ADS-B aircraft (1090 MHz) | external RTL-SDR via an rtl_tcp server | from the aircraft's own position report | from the position report |

Not possible from a normal Android app: sub-GHz (433/868/915 MHz), FM/AM, Zigbee, LoRa, and similar.
Those need an external radio (RTL-SDR, ESP32, CC1101) talking to the phone over USB or Bluetooth.
ADS-B aircraft are supported through an RTL-SDR (see item 24). NFC has no signal strength. UWB ranging needs a cooperating UWB peer.

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

19. **Fix sanity guard**: no position is shown when the signal changed less than 6 dB over the walk, when the fit
    is more than 5x off the strength-based distance (and over 15 m), when readings are too noisy, or when GPS is
    worse than 30 m. The target panel and details page say why.

20. **Indoor mode** (More menu): position from step detection plus the gyroscope-based orientation (not the
    magnetic compass) instead of GPS, for buildings. Fit, arrow and radar work as before in a frame relative to
    your start point. The map, tower lookup, sightings log and GPX path are off in this mode. Needs the Physical
    activity permission. Step length is adjustable (0.6 / 0.7 / 0.8 m or a 10 m walk calibration). It drifts, so
    reset the start point often. Accuracy is untested.

21. **Radar: located only** (switch under the radar, on by default): the radar draws only signals that have a
    distance and direction. The count of hidden ones is shown in the radar caption, and the list below still shows
    every signal. The signal you are tracking always stays on the radar. Turn the switch off to see distance-only
    dots again.

22. **Tags view** (More menu > Tags (item trackers)): lists every item tracker heard (AirTag, Tile, SmartTag and
    similar) with signal, last heard, how long it has been near you, and how far you have moved from where you first
    met it. Tags that stayed with you are marked and sorted first. Tap one for its details page. Distance travelled
    needs a GPS fix and is the farthest you got from the first meeting point, not the total walked. The list is a
    snapshot taken when you open it.

23. **Themed menus**: the More menu and Settings now use the app's dark/cyan panels. Switchable items show
    a filled dot (● ON, cyan outline) or a hollow dot (○ OFF); rows that open something show an arrow. Settings
    has status lines (● / ○) for the OpenCelliD key, Wi-Fi throttling, the vendor registry and tags marked as yours.
    Other pop-up dialogs (sightings, indoor options, calibrate) still use the standard Android style.

24. **ADS-B aircraft (RTL-SDR)**: tick **ADS-B** (off by default) to show aircraft heard on 1090 MHz. The app
    connects to an **rtl_tcp** server, the standard way to share an RTL-SDR. On the phone, run a free SDR driver
    app that serves rtl_tcp on `127.0.0.1:1234` (plug the dongle in with a USB-OTG adapter); or run `rtl_tcp` on a
    PC or Raspberry Pi and enter its address under **Settings > SDR**. Use a 1090 MHz antenna.
    Each aircraft is a teal **ADS-B** flag placed from its own broadcast position, so no walking is needed. Name is
    the callsign when heard, otherwise the ICAO hex code; details show altitude, speed and heading. Positions come
    from pairs of broadcasts, or from a single broadcast near your own position; anything farther than 500 km from
    you is dropped. The signal level is relative to full scale, not dBm, and is not calibrated. Only plain ADS-B
    (DF17/18) is decoded; the tuner gain is fixed at 49.6 dB (`GAIN_TENTHS_DB` in `Sdr.kt`).
    The decoder (`Adsb.kt`) is plain Kotlin and was checked on a desktop against published example messages
    and synthetic IQ; the Android side (`Sdr.kt`) has not yet been tested with a real dongle.

25. **Your own watchlist** (More > My watchlist, or **Watch** on a signal's details page): add a name fragment, an
    address or address prefix (`aa:bb:cc`), or an aircraft callsign / ICAO code. A matching signal gets an amber
    **WATCH** flag (and a ★ in the list) and a notification, repeated at most every 10 minutes per signal. **Watchlist
    alert sound** in the More menu switches the notification sound on or off. Bluetooth devices that rotate their
    address will not keep matching an address entry.
26. **Mute list** (**Mute** on the details page, or More > Muted signals): a muted signal raises no watchlist, ALPR or
    tracker alerts and gets no flag. Tap an entry in the list to unmute it.
27. **Session debrief** (More > Session debrief): a plain summary built on the phone: what needs a look, aircraft,
    strongest signals and what the category guesses add up to. **Share** sends it as text. Optional: paste your own
    Claude API key in Settings to get **Ask Claude**; the exact text is shown to you before it is sent, it has no GPS
    coordinates, and Wi-Fi/Bluetooth addresses are shortened. The key is kept in a separate preferences file that is
    excluded from cloud backups. Not tested against the live API yet.
28. **More category guesses**: vehicle makes in a Wi-Fi/Bluetooth name, fleet or telematics gear and public
    infrastructure are now guessed from names. They are only hints.
29. **Drone Remote ID** (Settings > Signals shown > Drone ID): decodes ASTM F3411 / ASD-STAN broadcasts over Bluetooth (service data
    0xFFFA) and Wi-Fi beacons (vendor element FA:0B:BC type 0x0D, needs Android 11+). Shows the drone's own reported
    position, serial/ID, type, speed, altitude, operator ID and the pilot position (in the detail text). No walk-and-fit
    is used. Decoder checked against messages made by the reference opendroneid-core-c library. Not receivable on a
    phone: Wi-Fi NAN action frames (some drones use them). Wi-Fi scans are throttled by Android (about every 10 s or
    slower), so Bluetooth is the better path. Not field-tested with a real drone yet.

30. **Busy-air protection**: very chatty emitters are thinned to about 4 readings a second each. If more than 300
    readings a second arrive, a **safe mode** thins everything to 1 per signal per second until it calms down. Over
    1500 tracked signals, the oldest unimportant ones are forgotten (your watchlist hits, tags, ALPR leads and the
    selected signal are never dropped). **More > Scan health** shows the rates, thinning and errors per protocol.
31. **Hints with their limits**: low-confidence guesses (Meshtastic radios, Flipper Zero names, MeshCore, generic
    HC-05/06/03 serial-module names) are shown with a confidence level, where the pattern comes from and what a match
    does not prove. They are not alarms. Alerts for the same signal now keep a cooldown, and a stale or replayed
    reading never triggers an alert. Not field-tested.

32. **Signals shown** (Settings): an on/off row for each signal type (Wi-Fi, BLE, BT Classic, Cell, ADS-B, Drone ID).
    Off stops that scanner and hides its signals from the list, radar and map. The choice is remembered. The tick-box
    row under the buttons is gone, which also fixes labels wrapping. "Cellular" is now "Cell".

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
- Marine AIS (161.975 / 162.025 MHz) on the same SDR link
- Sub-GHz decoding (433/868/915 MHz) on the same SDR link
- Direct USB access to the RTL-SDR (no driver app), which needs native librtlsdr code
- ESP32 bridge for 2.4 GHz protocols such as Zigbee
