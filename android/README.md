# Ivy Dictation for Android

Offline voice dictation for Android. Hold the bubble, talk, and Ivy types into any app. Nothing leaves the phone: no cloud, no account, no tracking.

- **Download:** the APK is on the [Releases page](https://github.com/raj-7676/ivy-dictation/releases) (tag `android-v1.0.0`). It is not on the Play Store.
- **Size:** the APK is about 25 MB. On first start Ivy downloads its three model files once (about 1.5 GB, Wi-Fi recommended) from [Hugging Face](https://huggingface.co/raj7676/ivy-dictation/tree/main/android). After that it never needs the internet.
- **Needs:** Android 7+ on a 64-bit ARM phone (arm64-v8a), about 3 GB of free RAM while dictating.

## Build

Kotlin + Jetpack Compose. JDK 21, Android SDK 36, Gradle 9.

```
gradle assembleDebug       # debug APK (needs a debug.keystore: keytool -genkey -v -keystore debug.keystore -alias androiddebugkey -storepass android -keypass android -keyalg RSA -validity 10000)
gradle assembleRelease     # needs KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD in the environment
```

`native/` holds Ivy's C++ engine (JNI bridge on top of llama.cpp). The prebuilt libraries are in `app/src/main/jniLibs/arm64-v8a`.

Licence: same as the rest of Ivy (see the repo root). The name "Ivy" and the logo are covered by [TRADEMARK.md](../TRADEMARK.md).
