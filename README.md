# Android Input Forwarder

![Input Forwarder icon](icon-512.png)

Root-only Android app that forwards one Bluetooth Xbox controller, a keyboard, and a mouse to an [Apollo](https://github.com/ClassicOldSong/Apollo) PC. Another app, such as Steam Link VR, can stay in front.

**This app requires root.** Android does not let a normal app read those devices while another app owns the screen. There is no permission that changes that. An unrooted install cannot scan or forward input.

**It has been tested only on a rooted Samsung Galaxy XR (SM-I610).** The test device was firmware `I610UEU2AZF3` with [KernelSU GXR](https://github.com/BlueScorpioVR/KernelSU-GXR) loaded through [DFRoot](https://github.com/diabl0w/DFRoot). The code does not call Galaxy XR APIs, but no phone, tablet, or other headset has been tested.

The 0.1 alpha is arm64, Android 10 or newer, and debug-signed. It does not start on boot. Start and stop it yourself.

## Galaxy XR root

Rooting can leave the headset unable to boot. Follow the root project's own warnings. This app does not root the device.

- [KernelSU GXR](https://github.com/BlueScorpioVR/KernelSU-GXR) — tested root stack for the SM-I610
- [Risk notes and what was tested](https://github.com/BlueScorpioVR/KernelSU-GXR/blob/galaxyxr/README.md)
- [How the GXR module is built and loaded](https://github.com/BlueScorpioVR/KernelSU-GXR/blob/galaxyxr/GALAXYXR.md)
- [Tested firmware and builds](https://github.com/BlueScorpioVR/KernelSU-GXR/blob/galaxyxr/TESTED.md)
- [KernelSU GXR releases](https://github.com/BlueScorpioVR/KernelSU-GXR/releases)
- [DFRoot](https://github.com/diabl0w/DFRoot) — load the module with this. Do not flash the Manager APK as a boot image.

After root is working, open KernelSU and grant **Input Forwarder** in the superuser list before scanning. The tested KernelSU build does not ask first. It only accepts `/system/bin/su` from an app that is already allowed.

## Apollo on the PC

1. Install [Apollo](https://github.com/ClassicOldSong/Apollo/releases) and leave its video encoder on **NVENC**. This app does not encode video. Apollo's input-only mode may still create one dummy video frame during negotiation; the phone discards it.
2. In Apollo's **Input** tab, enable **Input Only mode**.
3. Confirm Apollo's existing Windows gamepad backend already works. This app installs no PC driver. If Xbox input does not appear, that is an Apollo/Windows setup problem, not something this APK fixes.
4. In the app, enter the PC's IP address or hostname and tap **Pair with PC**. Enter the PIN in Apollo. The paired client may be named **Legacy Moonlight Client**.
5. Open Apollo's **PIN** tab and grant that client:
   - **Launch Apps**
   - **Controller Input**
   - **Mouse Input**
   - **Keyboard Input**

   A newly paired client only receives View Streams and List Apps. Without Launch Apps, start returns **403 permission denied**. Without the input permissions, the session can connect while the PC ignores the controller, keyboard, or mouse. Details are in Apollo's [permission system](https://github.com/ClassicOldSong/Apollo/wiki/Permission-System).

## Use

1. Install the alpha APK from the [0.1 alpha release](https://github.com/BlueScorpioVR/androidInputForwarder/releases/tag/v0.1.0-alpha).
2. Grant the app root, then tap **Scan devices**.
3. Select the Xbox controller, keyboard, and mouse. Only selected devices are read. Android still receives their buttons, so headset gestures can still fire.
4. Tap **Start forwarding**. Leave the screen if you want; the notification stays until you stop it.
5. Start Steam Link VR, or whatever app should own the display.
6. Stop from the app or the **Stop forwarding** notification. A reboot does not start it again.

The event count in the app means the headset captured events. It does not prove the PC accepted them. Check the PC.

## Limits

- Root is required the whole time forwarding is on.
- One Xbox controller. Sticks, triggers, D-pad, and the usual face and menu buttons are mapped. Rumble is not.
- Keyboard and relative mouse, including wheel. The mapping is a standard PC layout, not every vendor key.
- No exclusive grab, so the headset and the PC both see the input.
- Devices are remembered by hardware identity. If firmware changes that identity, scan and select again.
- Custom Apollo HTTP ports are not supported. The app uses port `47989`.

## Build

The native protocol library is built from the Moonlight Android submodule.

```powershell
git clone --recursive https://github.com/BlueScorpioVR/androidInputForwarder.git
cd androidInputForwarder
# local.properties must set sdk.dir. JDK 17+, SDK platform 35,
# build-tools 35.0.0, and NDK 27.2.12479018.
.\gradlew.bat assembleDebug testDebugUnitTest
```

See `NOTICE.md` for the exact Moonlight revision.

## License

GPL-3.0-or-later, because this program is derived from Moonlight Android. Source for this alpha is this repository plus the pinned Moonlight submodule.
