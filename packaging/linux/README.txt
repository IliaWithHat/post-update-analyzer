Post-Update Analyzer for Linux
==============================

1. Install ADB, e.g. `sudo apt install adb` (Debian/Ubuntu) or `sudo dnf install android-tools` (Fedora),
   or download Android SDK Platform-Tools: https://developer.android.com/tools/releases/platform-tools
   If the phone shows "no permissions", install the android udev rules (`android-sdk-platform-tools-common`
   or `android-udev-rules`) and add yourself to the plugdev group.

2. Double-click "PostUpdateAnalyzer" in this folder (or run ./PostUpdateAnalyzer in a terminal).
   Java is included, nothing else to install.

3. Your browser opens the app, and a small window shows that it is running.
   Closing that window (or "Quit" in the app's Settings) stops the app.

Data (snapshots, history, logs) is stored in ~/.local/share/post-update-analyzer.
