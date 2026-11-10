# Blindspot — Offline Android Camera Awareness Application

## Project Overview

Develop **Blindspot**, a fully independent Android application that informs users when their current GPS location falls within the estimated field of view of a registered CCTV camera.

The application must operate entirely locally on the user's Android device. It must not depend on an external server, PC, API, cloud service, or continuous internet connection.

The application uses two primary data sources:

1. A locally stored JSON database containing CCTV camera information.
2. The user's current GPS location obtained directly from the Android device's built-in location hardware.

The only feature that requires an internet connection is importing camera information from OpenStreetMap or importing a user-provided camera database.

The application should prioritize reliability, low battery consumption, simplicity, and offline functionality.

---

## 1. Core Architecture

The application must:

- Run natively on Android.
- Operate independently without requiring a connection to another device.
- Store all camera information locally.
- Obtain the user's location directly from the phone.
- Perform all camera visibility calculations locally.
- Continue functioning without Wi-Fi, mobile data, or any other internet connection.
- Support background location monitoring, including when the application is minimized or the screen is locked, subject to Android permissions and background execution restrictions.

There must be no mandatory account, registration, authentication, or cloud synchronization.

The camera database should persist between application launches and be stored in a format that can be updated or replaced through the import functionality.

## 2. Camera Database

The application must use a local JSON file named `cameras.json`.

I will provide an initial camera database in the project working directory.

Each camera entry follows this structure:

```json
{
    "id": 2718303450,
    "lat": 49.9486249,
    "lon": 15.2650147,
    "directions": [],
    "type": [
      "dome"
    ],
    "operator": "Městská policie Kutná Hora",
    "mount": "building",
    "zone": "street",
    "check_date": "2025-02-06"
}
```

Field descriptions:

- `id`: Unique camera identifier, originating from OpenStreetMap.
- `lat`: Camera latitude.
- `lon`: Camera longitude.
- `directions`: Camera viewing direction(s), expressed in degrees. An empty array means the direction is unknown.
- `type`: Camera type, such as dome, fixed, or other available classifications.
- `operator`: Organization responsible for the camera, if known.
- `mount`: Physical mounting location.
- `zone`: Area monitored by the camera.
- `check_date`: Date when the camera information was last checked.

The application must validate imported JSON files and reject invalid entries without corrupting the existing database.

It should also handle missing optional metadata gracefully.

Camera identifiers should be used to prevent duplicate entries during database merging.

## 3. Camera Import Functionality

The application must include a clearly accessible **Import Cameras** button.

This should be the only functionality requiring an internet connection when retrieving OpenStreetMap data.

There should be two import options:

### A. Import from OpenStreetMap

Allow users to select a geographical perimeter directly on an interactive map.

The user should be able to:

1. Open the import map.
2. Navigate to their desired location.
3. Select a geographical area or draw a perimeter.
4. See the selected perimeter before confirming.
5. Start importing camera information from OpenStreetMap within that perimeter.

Retrieve available CCTV camera information and convert it into the application's standard JSON structure.

Preserve all available relevant metadata.

The import process should:

- Clearly display download progress.
- Handle network errors and unavailable data sources.
- Avoid importing duplicate camera IDs.
- Merge new information into the existing local database.
- Allow users to replace the existing database if desired.
- Never delete the existing database when an import fails.

Use an appropriate OpenStreetMap data retrieval method, respecting its usage policies and applicable API limitations.

### B. Import Custom Camera List

Allow users to select and import their own `.json` camera database from Android storage.

The imported file must follow the camera structure described in section 2.

Validate the file before importing it.

Provide clear feedback about:
- Successfully imported cameras.
- Duplicate entries.
- Invalid entries.
- Missing or unsupported fields.

Allow merging with the existing database or replacing it entirely.

After importing, all camera information must remain available offline.

## 4. GPS Location Monitoring

The application must use the Android device's own GPS/location hardware.

It must not require an external GPS receiver or another device.

Continuously monitor the user's location when monitoring is enabled.

The monitoring system must:

- Work while the application is in the background.
- Support Android foreground services where required.
- Request appropriate location permissions.
- Handle GPS unavailability.
- Handle inaccurate GPS readings.
- Avoid excessive battery consumption.
- Allow the user to start and stop monitoring.

Location updates should be processed locally and should never be transmitted anywhere.

## 5. Camera Visibility Detection

This is the most important part of the application.

Blindspot must calculate whether the user's current geographical position falls within the estimated viewing area of any registered camera.

Use the camera's coordinates, direction, and field-of-view geometry.

### Camera viewing geometry

For cameras with known directions:

- Calculate the camera's viewing cone.
- Use a default horizontal field of view of approximately 110 degrees.
- Interpret the direction value as the camera's viewing bearing in degrees.
- Calculate whether the user's location falls within the viewing cone.
- Account for geographical coordinates and distances accurately.

For cameras with unknown directions (`"directions": []`):

- Assume 360-degree coverage.
- Use a configurable detection radius to represent the estimated viewing distance.

For cameras with known directions, also use a configurable maximum detection distance.

These values should be adjustable in settings.

Avoid treating the camera's immediate surroundings as automatically covered without considering its viewing geometry.

### Multiple cameras

The application must support multiple overlapping camera viewing areas.

If the user enters the viewing area of multiple cameras, the application should recognize all of them without generating unnecessary repeated notifications.

The application should maintain a state representing whether the user is currently inside at least one camera's estimated viewing area.

## 6. Vibration Notifications

Notifications should be communicated primarily through vibration.

There are two distinct vibration patterns:

**Entering camera coverage:**
- One strong, relatively long vibration.
- Indicates that the user has entered an estimated camera viewing area.

**Leaving camera coverage:**
- Two short, quick vibrations.
- Indicates that the user is no longer within any currently detected camera viewing area.

Important behavior:

- Do not repeatedly vibrate while the user remains within camera coverage.
- Do not vibrate every time the GPS position updates.
- Account for minor GPS fluctuations around camera coverage boundaries.
- Implement a small configurable hysteresis or confirmation mechanism to prevent notification spam.
- Correctly handle transitions between overlapping camera coverage areas.

Vibrations should remain distinguishable even when the phone is in a pocket.

Allow users to enable or disable vibration notifications in settings.

## 7. Main Application Interface

The interface should be designed specifically for Android mobile screens.

Keep it clean, minimalistic, and functional.

Suggested main screen:

- Interactive map displaying the user's current location.
- Camera markers representing locally stored cameras.
- Visualization of camera viewing directions and estimated coverage areas.
- Clear indication of whether the user is currently within camera coverage.
- Camera count in the currently loaded database.
- GPS status and accuracy.
- Monitoring enabled/disabled indicator.
- Access to camera import functionality.
- Access to settings.

Selecting a camera marker should display its available metadata:

- Camera ID.
- Operator.
- Camera type.
- Mount location.
- Monitored zone.
- Last check date.
- Viewing direction.

The map must be usable offline to the extent possible. Do not make camera monitoring dependent on map tile availability. If online map tiles are used, provide an offline-compatible alternative or a simple local map representation.

## 8. Settings

Provide settings for:

- Maximum detection distance.
- Default field of view.
- Unknown-direction camera detection radius.
- GPS update frequency.
- Vibration notifications.
- Background monitoring.
- Camera database management.
- Database statistics.

Use sensible defaults and avoid exposing unnecessary technical complexity.

## 9. Privacy and Security

Privacy is a fundamental design requirement.

- Never transmit user GPS coordinates.
- Never upload user movement history.
- Never send camera information to external servers except when explicitly requesting an OpenStreetMap import.
- Do not implement analytics or telemetry.
- Do not require an internet connection for monitoring.
- Keep the camera database locally stored.
- Do not retain location history unless explicitly requested by the user.

The application should remain useful even in airplane mode, provided the local camera database and GPS hardware are available.

## 10. Development Instructions

I will provide two files in the current working directory:

- `cameras.json` — the initial camera database.
- `Blindspot.py` — the previous Python implementation of Blindspot.

Use these files as project resources.

Inspect both before starting development.

`cameras.json` is the authoritative starting dataset and must be incorporated into the new application.

`Blindspot.py` is a reference for the previous implementation. It contains existing logic and design ideas that may be useful, but you are not required to preserve its architecture, UI, or implementation.

You are encouraged to redesign the application properly for Android rather than simply porting the old Python application.

Choose an appropriate Android technology stack that supports reliable background GPS monitoring, native vibration APIs, local storage, and offline operation.

Prefer native Android development with Kotlin if practical.

Do not build a web application, PWA, desktop application, or client-server architecture.

## 11. Core Reliability and GPS Stability

These requirements are mandatory for the initial version, not future enhancements.

### GPS Accuracy and Detection Stability

GPS readings can fluctuate, particularly in urban environments.

Implement a detection system that prevents unstable camera coverage transitions.

Requirements:

- Use GPS accuracy information when evaluating location readings.
- Implement hysteresis around camera coverage boundaries.
- Require multiple consistent location readings before confirming entry or exit.
- Prevent repeated notifications caused by small GPS position fluctuations.
- Avoid treating inaccurate GPS readings as definitive evidence of entering or leaving camera coverage.
- Handle sudden GPS jumps and temporarily unavailable location updates.
- Make confirmation thresholds configurable where appropriate.

The application should prioritize stable and reliable notifications over reacting to every individual GPS update.

### Background Monitoring Service

Background monitoring must be supported in the initial release.

Implement a native Android foreground service responsible for:

- Receiving GPS location updates.
- Processing camera visibility calculations.
- Maintaining camera coverage state.
- Triggering vibration notifications.
- Updating the monitoring status notification.

The service must continue operating when:

- The application is minimized.
- The screen is locked.
- The user switches to another application.

Use the appropriate Android location and foreground-service permissions.

The application must clearly communicate when background monitoring cannot operate due to missing permissions, GPS unavailability, or Android restrictions.

### Persistent Monitoring Notification

While monitoring is active, maintain a persistent Android notification.

Example:

**Blindspot**
Monitoring active
3 cameras currently nearby

The notification should:

- Clearly indicate that monitoring is running.
- Display the number of cameras currently nearby.
- Update when relevant camera information changes.
- Provide a way to return to the application.
- Clearly indicate when monitoring is stopped or unavailable.

Avoid excessive notification updates.

### Estimated Camera Coverage and Accuracy

Blindspot must never present estimated camera coverage as confirmed visual surveillance.

The application calculates potential visibility using available camera metadata, coordinates, viewing directions, and configurable distance assumptions.

It cannot reliably determine:

- Whether a camera is physically operational.
- Whether the camera is actively recording.
- Whether the camera has an unobstructed view.
- Whether buildings, trees, or other objects block its view.
- The actual zoom level or exact field of view.
- Whether its registered orientation is accurate.

All map visualizations, notifications, and status indicators must use terminology such as:

- Estimated camera coverage.
- Potentially within camera view.
- Estimated visibility area.

Never imply that entering a calculated viewing cone proves that the user is being recorded.

This distinction is fundamental to the application's design and must be reflected throughout its interface.

### Development priorities

Implement the project incrementally:

1. Inspect the existing files and understand the previous camera detection logic.
2. Design the Android application architecture.
3. Implement local JSON database loading and validation.
4. Implement GPS acquisition.
5. Implement camera visibility calculations.
6. Implement entering/leaving state detection and vibration patterns.
7. Implement the main map interface.
8. Implement OpenStreetMap perimeter-based importing.
9. Implement custom JSON importing.
10. Implement background monitoring and battery optimization.
11. Test offline functionality and edge cases.

Do not sacrifice core offline functionality for visual features.

Produce a working Android application, not just a prototype UI or a design mockup.

The final result should be installable as an Android APK.

Before implementing major features, explain the proposed architecture and any important technical decisions. Keep the implementation modular and maintainable.

---

**Overall objective:**

Blindspot should function as a self-contained mobile camera-awareness tool. A user imports a camera database once, puts their phone in their pocket, and Blindspot independently uses the phone's GPS to detect entry into and exit from estimated CCTV camera viewing areas, communicating these transitions through distinct vibration patterns.

No external infrastructure should be required.
