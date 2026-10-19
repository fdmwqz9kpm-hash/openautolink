# Local macOS build for this fork

This fork retains the former Carlink Play identity `com.fdmwqz9kpmhash.carlink`, with version code 65. The previous private CarPlay release uses code 64. Future updates must remain above the highest uploaded Play version. The source namespace is still OpenAutoLink.

The upstream Docker builder is x86-64-only. The Android application can also be compiled locally on Apple Silicon using the upstream prebuilt Android native dependencies. This path was verified with JDK 21, Android SDK 36, build-tools 34.0.0, NDK 28.2.13676358, CMake 3.22.1, and Gradle 8.11.1.

## Native dependencies

Clone with submodules. Fetch the `native-deps` release metadata from https://api.github.com/repos/mossyhub/openautolink/releases/tags/native-deps. Check its aasdk commit matches the checked-out submodule, download both ABI archives, and verify each SHA-256 against the release asset's `digest` before extracting into `app/src/main/cpp/`. These are Android libraries; their host builder architecture does not prevent using them from macOS.

At the verified revision, the native release targets aasdk `7211802c7a2d390d2d178cfa873344e7b90c9357`:

- `native-deps-arm64-v8a.tar.gz`: `a105e287a80cce6e49e982b49503347a376cb82f8af62b1188ed5831139d4317`
- `native-deps-x86_64.tar.gz`: `bdf67884b3b3b3e2424d1afd9c034deb5592b9224e8e95fc8bee04c15f32149d`

This fork adds verification entries for Google's macOS AAPT2 and Maven Central's Apple Silicon protoc. Strict Gradle dependency verification remains enabled.

## Build

Set `JAVA_HOME` to JDK 17 or 21. Android Studio's current bundled JDK 25 does not work with this pinned Gradle version. Set the ignored `local.properties` SDK path or `ANDROID_HOME`, then run:

```bash
bash scripts/macos/build-bundle.sh
```

This produces an unsigned release bundle. For signing, set `OAL_SIGNING_DIR` to an external private directory containing `upload-key.jks`, `store-password`, `key-password`, and `alias`; each text file holds the corresponding value. Set `BUNDLETOOL_JAR` to a verified bundletool installation. The tested bundletool 1.18.3 SHA-256 is `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29`.

Set `OAL_OUTPUT_DIR` to choose a delivery folder. The script compiles unsigned, then signs the bundle separately using password-file arguments and validates the bundle structure and signature. Do not place signing inputs in this repository. Before updating the existing Play app, verify the resulting signer matches the prior upload certificate.

## CarPlay and installation

This build does not bundle CarPlay accessory authentication files. Those are distinct from the Android upload key. See `carplay-private-mfi-assets.md` for external private inputs. Building and signing successfully does not establish working iPhone projection.

Locked GM installation uses the existing Google Play app's testing track. Vehicle runtime and phone projection must be tested separately; this local build has not been installed in a car.
