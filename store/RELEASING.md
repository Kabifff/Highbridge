# Releasing Highbridge

One version number covers both editions. For each release:

1. In `app/build.gradle.kts`, raise `versionCode` by 1 and set `versionName`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters max).
3. Build with the release key set up (see README, "Release signing"):
   ```
   ./gradlew bundleLiteRelease     # Google Play: app/build/outputs/bundle/liteRelease/app-lite-release.aab
   ./gradlew assembleFullRelease   # GitHub: app/build/outputs/apk/full/release/app-full-release.apk
   ./gradlew assembleLiteRelease   # optional GitHub download of the lite edition
   ```
4. Commit and push. On GitHub, draft a new release with a new tag `v<versionName>` (for example
   `v0.2.0`) and publish it. The Release workflow builds the signed full and lite APKs and attaches
   them; GitHub adds the source code archives the GPL asks for. F-Droid builds from these tags.
5. For Google Play, run the Release workflow by hand (Actions › Release › Run workflow) and
   download the `.aab` from the run's artifact, or build it locally with `bundleLiteRelease`.

Store screenshots and graphics are drawn from the real app:
```
./gradlew :app:testLiteDebugUnitTest --tests '*StoreScreenshots*' --tests '*StoreGraphics*' -Phb.shots
```

Files in this folder:
- `fdroid/com.stpaulmesh.highbridge.yml`: the F-Droid build recipe.
- Store listing text lives in `fastlane/metadata/android/en-US/`, which both F-Droid and Play tools read.
