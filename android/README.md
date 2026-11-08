# Ivy Dictation for Android

Offline voice dictation for Android. Hold the bubble, talk, and Ivy types into any app. Nothing leaves the phone: no cloud, no account, no tracking.

- **Download:** the APK is on the [Releases page](https://github.com/raj-7676/ivy-dictation/releases) (tag `android-v1.0.1`). It is not on the Play Store.
- **Size:** the APK is about 22 MB. On first start Ivy downloads its three model files once (about 1.5 GB, Wi-Fi recommended) from [Hugging Face](https://huggingface.co/raj7676/ivy-dictation/tree/main/android). After that it never needs the internet.
- **Needs:** Android 7+ on a 64-bit ARM phone (arm64-v8a), about 3 GB of free RAM while dictating.

## Install

**Easiest, with automatic updates:** [add Ivy to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.glowco.ivy%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fraj-7676%2Fivy-dictation%22%2C%22author%22%3A%22raj-7676%22%2C%22name%22%3A%22Ivy%20Dictation%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3A%20true%2C%20%5C%22fallbackToOlderReleases%5C%22%3A%20true%2C%20%5C%22autoApkFilterByArch%5C%22%3A%20true%7D%22%7D) (in Obtainium's app settings for Ivy, turn on "Include prereleases"). Or install the APK by hand:

1. Download the APK from the [Releases page](https://github.com/raj-7676/ivy-dictation/releases) and open it. When Android asks, allow "install unknown apps" for your browser.
2. If Google Play Protect shows "unrecognized app", tap **More details > Install anyway**. Ivy is new, so Google has not seen it before. Its source code is public in this folder.
3. Open Ivy and follow the setup. It asks for the microphone, "display over other apps" and the accessibility service (that is how the bubble types into other apps).
4. **Android 13 and later:** if the accessibility switch is greyed out, open Settings > Apps > Ivy Dictation, tap the three dots at the top right, and choose **Allow restricted settings**. Then switch the accessibility service on.

Ivy needs the accessibility service only to find the text box you tapped and type your words into it. It does not read or store anything else.

## Build

Kotlin + Jetpack Compose. JDK 21, Android SDK 36, Gradle 9.

```
gradle assembleDebug       # debug APK (needs a debug.keystore: keytool -genkey -v -keystore debug.keystore -alias androiddebugkey -storepass android -keypass android -keyalg RSA -validity 10000)
gradle assembleRelease     # needs KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD in the environment
```

`native/` holds Ivy's C++ engine (JNI bridge on top of llama.cpp). The prebuilt libraries are in `app/src/main/jniLibs/arm64-v8a`.

Licence: same as the rest of Ivy (see the repo root). The name "Ivy" and the logo are covered by [TRADEMARK.md](../TRADEMARK.md).
