# Android App Debug Steps

## 1. Check Current Configuration
The app should be configured to use:
- **ApiService.kt**: `192.168.1.151:3000`  
- **MainActivity.kt**: `192.168.1.151:3000`

## 2. Manual Network Test
On your Android device browser, go to:
```
http://192.168.1.151:3000/api/health
```

Expected response:
```json
{"status":"ok","time":"2025-09-24T..."}
```

## 3. Check Android Logs
Run this command on your PC while the app is running:
```bash
adb logcat | grep -i "pixelflow\|error\|connection"
```

## 4. Alternative: Use Your PC's Actual IP
If still not working, check your PC's current IP:
```bash
ipconfig | findstr IPv4
```

## 5. Temporary Localhost Solution
If you're testing on an Android emulator (not physical device):
- Change ApiService.kt back to: `private const val HOST_IP = "10.0.2.2"`
- Change MainActivity.kt back to: `private const val BASE_URL = "http://10.0.2.2:3000"`

## 6. Windows Firewall (Run as Administrator)
```cmd
netsh advfirewall firewall add rule name="Node.js Server Port 3000" dir=in action=allow protocol=TCP localport=3000
```