# PixelFlow Player - Complete Rebuild Guide

## Issues Found:
1. **IP Configuration**: App was configured for emulator, needed physical device IP
2. **Caching Issues**: Old app data may persist after IP changes
3. **Download Logic**: May get stuck in infinite loops
4. **Network Communication**: Connection failures causing black screen

## Fixed Configuration:
- **ApiService.kt**: Set to `192.168.1.151:3000` (your PC's IP)
- **MainActivity.kt**: Set to `192.168.1.151:3000` (consistent)
- **Added Debug Logging**: Better error tracking

## Complete Rebuild Steps:

### 1. Clean Everything
```bash
# In Android Studio:
Build → Clean Project
Build → Rebuild Project

# Or from terminal in PixelFlowPlayer directory:
./gradlew clean
./gradlew build
```

### 2. Clear App Data Completely
- Uninstall the existing app from your device
- Or: Settings → Apps → PixelFlow Player → Storage → Clear All Data

### 3. Test Network Connection First
On your Android device browser, test:
```
http://192.168.1.151:3000/api/health
```
Should return: `{"status":"ok","time":"..."}`

### 4. Install Fresh App
```bash
# Install fresh APK
./gradlew installDebug
# Or use Android Studio Run button
```

### 5. Reset Device Pairing
- Open app → Tap screen 10 times → Reset Device
- Get new PIN and pair in CMS web interface

## Debugging Commands:
```bash
# Watch Android logs in real-time
adb logcat | grep -E "PixelFlow|MainActivity|ApiClient"

# Check network connectivity
adb shell ping 192.168.1.151

# Check app storage
adb shell run-as com.example.pixelflowplayer ls files/
```

## Expected Behavior:
1. **Launch**: Shows PIN screen
2. **After Pairing**: Shows "Waiting for content..."
3. **After Assigning Playlist**: Shows download progress
4. **After Download**: Plays media files

## If Still Black Screen:
1. Check Windows Firewall (port 3000)
2. Check WiFi - both devices on same network
3. Try USB debugging and check logs
4. Test with single small image first