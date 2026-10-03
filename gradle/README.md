# Gradle wrapper

The wrapper jar isn't checked in. Open the project in Android Studio and it
will fetch the wrapper for you on first sync.

If you want to build from the command line:

```bash
# One-time, from the project root (requires Gradle 8.7+ on PATH)
gradle wrapper --gradle-version 8.7
git add gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
git commit -m "Add gradle wrapper"
```

Then `./gradlew assembleDebug` will build the APK.

## Versions in use

| Tool         | Version |
| ------------ | ------- |
| Android Gradle Plugin | 8.7.3 |
| Gradle wrapper (target) | 8.9 |
| Kotlin       | 2.0.21  |
| KSP          | 2.0.21-1.0.27 |
| Hilt         | 2.52    |
| Compose BOM  | 2024.12.01 |
| Compose Compiler Plugin | 2.0.21 (built-in) |
| Java target  | 17      |
| minSdk       | 26      |
| targetSdk    | 36      |
| compileSdk   | 36      |
