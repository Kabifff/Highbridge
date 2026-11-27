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
4. Commit, then tag `v<versionName>` (for example `v0.2.0`) on that commit and push the tag.
   F-Droid builds from these tags automatically once the app is listed.
5. Make a GitHub release from the tag and attach the full APK (and the lite APK if you like).
   GitHub adds the source code archives that the GPL asks for.
6. Upload the `.aab` to Google Play.

Store screenshots and graphics are drawn from the real app:
```
./gradlew :app:testLiteDebugUnitTest --tests '*StoreScreenshots*' --tests '*StoreGraphics*' -Phb.shots
```

Files in this folder:
- `fdroid/com.stpaulmesh.highbridge.yml`: the F-Droid build recipe.
- Store listing text lives in `fastlane/metadata/android/en-US/`, which both F-Droid and Play tools read.
