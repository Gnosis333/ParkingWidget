---
description: Build and install the Parking Widget on the emulator
---

// turbo
1. Set JAVA_HOME and run installDebug
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat installDebug
```

2. Capture a screenshot for verification
```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell screencap -p /sdcard/last_build.png; & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" pull /sdcard/last_build.png .
```
