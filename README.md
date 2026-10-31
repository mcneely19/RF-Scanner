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

Keep the screen on while scanning. The app stops scanning when it goes to the background and resumes when you return.

## Known limits

- Android throttles Wi-Fi scans (about 4 per 2 minutes in the foreground). For continuous Wi-Fi
  scanning, turn off **Wi-Fi scan throttling** in Developer options.
- Bluetooth Classic discovery competes with BLE scanning for the radio. Untick it if BLE gets sparse.
- BLE devices with randomized MAC addresses show up as new devices when the address rotates.
- Map tiles need internet (OpenStreetMap). The scanning itself works offline.

## Ideas for later

- Wi-Fi RTT (802.11mc) for real metre-level ranging to supporting access points
- GNSS satellite signal view
- Foreground service so scanning continues with the screen off
- CSV / GPX export of the sweep
- External SDR / ESP32 bridge for sub-GHz and 2.4 GHz protocols
