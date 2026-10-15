Post-Update Analyzer for Windows
================================

1. Install ADB (Android SDK Platform-Tools):
   https://developer.android.com/tools/releases/platform-tools
   Unzip it, e.g. to C:\platform-tools. The app finds it there, in your PATH, or in the
   Android Studio SDK. Otherwise enter the path to adb.exe in the app's Settings page.

2. Double-click PostUpdateAnalyzer.exe. Java is included, nothing else to install.
   Windows may warn "Windows protected your PC" because the app is not signed:
   click "More info" -> "Run anyway".

3. Your browser opens the app, and a small window shows that it is running.
   Closing that window (or "Quit" in the app's Settings) stops the app.

Keep the folder together: PostUpdateAnalyzer.exe needs the "app" and "runtime" folders next to it.
Data (snapshots, history, logs) is stored in %LOCALAPPDATA%\PostUpdateAnalyzer.
