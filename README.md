# PixelFlow Player — Architecture and Heartbeat API

## Overview
PixelFlow Player is an offline‑first Android digital signage app:
- Pairs with the CMS using a 6‑char PIN.
- Polls the CMS every 15s via a heartbeat.
- Downloads all assigned media to local storage, then plays from disk.
- If some items fail to download, it plays the successfully cached items and skips failed ones (no HTTP streaming).

## Installation and First‑Run Flow
1) Prerequisites
   - Android 8.0+ device or emulator with internet access.
   - Backend reachable from the device.
     - **Emulator**: Set `BASE_URL = "http://10.0.2.2:3000"` in `MainActivity.kt` (line ~216) and `HOST_IP = "10.0.2.2"` in `ApiService.kt` (line ~126).
     - **Physical device**: Set `BASE_URL = "http://192.168.1.104:3000"` in `MainActivity.kt` and `HOST_IP = "192.168.1.104"` in `ApiService.kt` to your backend's LAN IP.
     - **IMPORTANT**: Must rebuild APK (`./gradlew clean assembleDebug`) and reinstall after changing IPs.
2) Install
   - Build from Android Studio (Run) or generate APK: Gradle task `assembleDebug`/`assembleRelease` and sideload.
3) First launch sequence (logic)
   - Splash shows briefly, then the Pairing screen appears with a 6‑character PIN.
   - App starts the 15s Heartbeat loop with `{ deviceId, pairingCode, deviceInfo }`.
   - CMS shows the device as Unpaired. Pair it in the web UI by entering the PIN.
   - While paired but no content is assigned, the server returns `paired_waiting` → the app clears local content and shows “Waiting for content…”.

   - Assign a playlist (or single media) to the device in CMS.
   - On next heartbeat (`playing`), the app:
     1. Applies orientation/rotation from the payload.
     2. Shows the blocking Download Splash.
     3. Queues downloads for all missing media; cached files count as completed.
     4. When all items are done (completed + failed), it starts playback with the successfully cached items only and skips failed ones.
4) Subsequent updates
   - Every 15s the app checks for changes. If content changed, it repeats the Download Splash and switches when the set is ready.
5) Removal / Unpair
   - Playlist removed → server returns `paired_waiting` → app stops playback, clears playlist/cache, shows Pairing screen (paired, waiting for content).
   - Device deleted/unpaired → server returns `unpaired` (or HTTP 404/410) → app clears cache, resets pairing, shows a new PIN.
6) Offline behavior
   - If the device loses network, it keeps playing locally cached media. When network returns, the heartbeat resumes and updates apply automatically.
7) Optional kiosk mode
   - For production dedicated devices, provision as Device Owner and whitelist the app for Lock Task. The app will enter kiosk without the system education dialog.

## Pairing and Heartbeat
- Interval: every 15 seconds (configurable).
- Endpoint: `POST /api/devices/heartbeat`
- Request body:
```json
{
  "deviceId": "<uuid>",
  "pairingCode": "<PIN or empty string>",
  "deviceInfo": {
    "model": "<android model>",
    "androidVersion": "<version>",
    "appVersion": "1.0.0"
  }
}
```
- Response statuses:
  - `unpaired` — Device is not paired. App shows PIN and keeps sending heartbeats with that PIN.
  - `paired_waiting` — Paired but no content. App clears local playlist/cache and shows “Waiting for content…”. 
  - `playing` — Content assigned. Response contains `playlist` and optional `rotation`.

- Sample response (playing):
```json
{
  "status": "playing",
  "rotation": 0,
  "playlist": {
    "orientation": "Landscape",
    "transitionType": "Cut",
    "items": [
      {"type": "image", "url": "/uploads/a.jpg", "duration": 10, "displayMode": "contain"},
      {"type": "video", "url": "/uploads/b.mp4", "duration": 0,  "displayMode": "cover"}
    ]
  }
}
```

## Download‑First Playback
1) On `playing`, the app collects all item URLs and checks cache.
2) Shows a blocking download splash and queues only missing files.
3) Progress overlay displays percent, item name, and any failures.
4) Completion rule: when all items finish (completed + failed), the overlay hides and playback starts with cached items only. Failed items are skipped.
5) No HTTP streaming during playback.

## UI Layers
- Pairing screen — shows PIN or “Waiting for content…”. 
- Download splash — bottom‑center overlay with progress and errors.
- Player — ExoPlayer for videos, Glide for images. Media fit: contain/cover/fill per item.

## Orientation and Rotation
- Orientation from playlist: Landscape/Portrait (case‑insensitive).
- Optional content rotation: 0/90/180/270° applied to media views.

## Cache and Cleanup
- Media cached in app internal storage `filesDir/media_cache`.
- On `paired_waiting`: playback stops, playlist cleared, cache wiped.
- On new playlist: after download completes, cache is pruned to keep only current items.

## Failure Handling
- Network/API errors: retry on next heartbeat (15s).
- Download errors: reported on overlay; app proceeds with cached items and skips failed ones.
- Device deleted in CMS: app treats HTTP 404/410 or `unpaired` as reset → clears cache, shows new PIN.

## Kiosk Notes
- App supports Android Lock Task (kiosk). It starts lock task only when permitted to avoid the system education dialog during development.

## Troubleshooting quick checks
- Stuck on “paired_waiting”: ensure content is assigned to the device in CMS, and heartbeat reaches the server.
- Download overlay never finishes: verify server file URLs are reachable; check logcat for per‑item failures; app will start with cached items when all attempts finish.
- Black screen after reassigning same playlist: app now forces re‑download/start if cache was cleared during waiting.

---
This document replaces previous scattered docs (networking, splash, offline system) with a concise reference for the player flow and the heartbeat contract.