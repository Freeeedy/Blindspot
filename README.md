# Blindspot (Android)

Offline camera-awareness app. It vibrates when your GPS position enters or leaves the
**estimated** viewing area of a CCTV camera stored in a local `cameras.json` database.

## Install

Download the signed APK from [Releases](https://github.com/Freeeedy/blindspot/releases), or build it yourself (below).

```
adb install -r Blindspot.apk
```

Or copy it to the phone and open it (allow "Install unknown apps" for your file manager).
Requires Android 8.0+.

## Build

```
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest     # geometry, detection state machine, JSON validation
```

Needs JDK 17+ and the Android SDK (platform 35, build-tools 35) at the path in `local.properties`.
Release signing reads `keystore.properties` → `keystore/blindspot-release.jks`.
**Back up both**: future updates must be signed with the same key or Android refuses to install them over the existing app.

## Architecture

```
app/src/main/java/app/blindspot/
├── data/        Camera model, cameras.json parsing/validation, repository (atomic writes), settings
├── geo/         Haversine/bearing/destination math, grid spatial index
├── detect/      CoverageDetector – geometry + stability state machine (pure Kotlin, unit-tested)
├── service/     MonitoringService (foreground, type=location), vibration patterns, notifications
├── importer/    Overpass (OpenStreetMap) import and tag conversion
└── ui/          Compose screens (map, import, settings), osmdroid map overlays
```

- **Location:** `LocationManager.GPS_PROVIDER` only, with no Google Play Services or network location.
  Readings stay in memory and are never stored or transmitted.
- **Database:** the bundled `assets/cameras.json` is copied to private storage on first run.
  Every change is written to a temp file, fsync'd and atomically renamed, so a failed import cannot corrupt it.
  The previous version is kept (`cameras.previous.json`) and can be restored in Settings.
  Camera IDs deduplicate entries during merging. Unknown fields (and all OSM tags under `osm_tags`) are preserved.
- **Geometry:** the user is inside a camera's area if `distance ≤ max distance` and the bearing from the camera
  is within `FOV/2` of one of its `directions`. If `directions` is empty, a 360° circle of the
  "unknown-direction radius" is used. The camera's immediate surroundings are not covered automatically; the cone geometry always applies.
  Defaults: 110° FOV, 40 m (taken from the previous Python version).
- **Stability:**
  - Readings worse than the accuracy threshold (35 m) are ignored.
  - GPS jumps faster than 70 m/s are rejected unless they persist for 3 readings.
  - Hysteresis: once inside, an area grows by an 8 m margin plus half the reading's accuracy.
  - Entering needs 2 consecutive readings inside, leaving needs 3 outside.
  - GPS loss keeps the last state and never triggers an alert.
  All of these thresholds are configurable under Settings → Detection stability.
- **Overlapping cameras:** alerts track one boolean, "inside at least one camera". Moving between overlapping
  cameras changes only the camera count, not the alert.
- **Vibration:** entering gives one strong 0.9 s pulse. Leaving gives two 140 ms pulses with a gap.
- **Battery:** the GPS interval is configurable (default 3 s). Battery saver drops to 12 s or 30 s while you are
  more than 600 m or 2 km from any camera's area, and switches back automatically as you approach.
- **Map:** osmdroid with OpenStreetMap tiles. Tiles you have viewed are cached for offline use. Settings can switch the map
  to cached tiles only or to no tiles (plain background). Cameras, cones and your position are drawn
  by the app itself, so they show without tiles. Monitoring never depends on the map.
- **OSM import:** the Overpass API runs one query per import with an identifying User-Agent, the area is capped at 5,000 km²,
  and a fallback server is tried only when the primary one is busy or down. Only the selected polygon is sent.

## More features

- **Add cameras:** long-press the map to place a camera. Tap the map to point its direction (tap "Add direction" for more,
  up to 4), then pick type, mount, zone and operator. User cameras get a negative id (like new OSM objects),
  so imports never collide with them. They are stored as `"source": "user"`, can be edited or deleted from the info sheet, and survive a database replace.
- **Delete imported cameras:** tap a camera, then Delete. Deleted imported cameras are kept in `cameras.deleted.json`, so later
  imports skip them, and Settings → "Restore deleted cameras" brings them back. "Reset to bundled database" also forgets deletions.
- **Quick Settings tile:** toggles monitoring. Settings → Quick Settings tile adds it on Android 13+. Starting from the tile
  briefly opens the app, because Android 14+ only grants location to a location service started in the foreground.
- **Filters:** filter by type and operator, with values shown exactly as in the data. Filters affect the map only, unless
  "Hidden cameras don't trigger alerts" is on.
- **Session list:** cameras whose estimated coverage included you since monitoring started. It's kept in memory only and
  cleared when monitoring starts again.
- **Offline vector map:** Settings → Offline vector map. `assets/map_catalog.json` lists all 416 Mapsforge maps with their
  bounding boxes, read from each file's header. The country is matched to your GPS position on the phone, so no location is sent.
  Downloads use Android's DownloadManager (resumable, Wi-Fi-only option) into app-specific storage and are checked
  before install. Rendering uses osmdroid-mapsforge.
- **`geo:` links:** "Open with Blindspot" centres the map on that place.

## Limitations

Blindspot estimates potential visibility from registered coordinates, directions and assumed distances.
It cannot know whether a camera works, records, has an unobstructed view, or points where it is registered.
