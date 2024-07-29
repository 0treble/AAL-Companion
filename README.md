# Temi Voice APK

Temi Voice is an application designed for the Temi robot. The app utilizes Temi's transcription capabilities to trigger various tasks such as navigating to a specific location, reminding users to take their pills, or engaging users in memory games and trivia.
This app is developed as part of the HTW Saar project VOICE.

## Features

- [x] **User Interface**: A user-friendly interface for easy interaction with the app.
- [x] **Extract Transcribed Text**: Capture and utilize the transcribed text from Temi's voice recognition.
- [x] **Voice Command Navigation**: Direct Temi to move to specified locations using voice commands.
- [x] **Surveys**: Conduct surveys using a sequence of voice interactions.
- [x] **Interactive Games**: Engage users with memory games and trivia questions using voice interactions.
- [ ] **Videocalls**: Make videocalls with contacts from the users contact list.

## Getting Started

### Prerequisites

- Temi Robot v2
- Android Debug Bridge (ADB) installed on your computer or directly in Android Studio

### Installation

Ensure your Temi robot is connected to the same network as your computer.

### Using ADB to Upload the APK to Temi

ADB (Android Debug Bridge) is a versatile command-line tool that lets you communicate with a device. You can use ADB to install the Temi Voice APK on your Temi robot over a network connection.

#### Steps to Install APK using ADB

1. **Install ADB on Windows**:
   - Download SDK Platform-Tools for Windows and extract the downloaded zip file.
   - Within the extracted folder, press SHIFT and right-click to display the context menu. Select "Open PowerShell window here" (or "Open command window here" on some computers) to open a command prompt.

2. **Enable ADB Port on Temi Robot**:
   - On the Temi robot, select `Settings > Developer Tools` and tap `Open Port`.
   - Take note of the robot’s IP address displayed on the screen.

3. **Test ADB**:
   - Connect your PC (with the installation of ADB) to the same network as the robot.
   - Type the following command into the command prompt:
     ```bash
     adb connect <robot-ip-address>
     ```
   - If everything goes well, you should see `connected to <robot-ip-address>`.

4. **Install the APK**:
    ```bash
    adb install path/to/temi-voice-apk.apk
    ```
   - You can also just build and run the app when the device `rockchip rk3288` is chosen in Android Studion after succesful connection to the device.

5. **Launch the App**:
   Once the APK is installed, you can launch it from the Temi interface or via ADB:
    ```bash
    adb shell am start -n com.yourpackage.temivoiceapk/.MainActivity
    ```

### Pulling Log Files from Temi

To pull the log files created by the app from Temi, use the following ADB command:
   ```bash
   adb pull /data/data/com.example.temi_voice/files/ 
   ```

### Deleting Log Files from Temi

To delete the log files from Temi, use the following ADB command:
   ```bash
   adb shell rm /data/data/com.example.temi_voice/files/*
   ```

To remove the entire directory and its contents, use the following command:
   ```bash
   adb shell rm -r /data/data/com.example.temi_voice/files/
   ```




