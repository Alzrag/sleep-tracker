# Sleep Score

A passive sleep tracker for Android. Put the phone on your mattress or nightstand, tap Start, and in the morning tap Stop to get:

- a 0-100 sleep score (half of it is how much of your 8.5 h goal you slept, the rest is quality),
- a light / deep / REM / awake timeline for the night (estimated),
- 60 days of history, with a date-range stats page (average sleep per night and per day including naps, average score, per-weekday breakdown, bedtime, consistency, efficiency, sleep debt, and a few plain-language insights).

Audio is never recorded. The microphone is turned into a loudness number in memory and discarded. Each minute the app saves only a few numbers (movement, loudness, light, screen-on seconds).

## Get the APK (no Android Studio needed)

1. Create a new empty repository on GitHub and push this folder to it (`git init`, `git add .`, `git commit`, `git remote add origin ...`, `git push -u origin main`).
2. Open the repo's **Actions** tab. The "Build Android APK" workflow runs on every push (about 5 minutes).
3. When it finishes, either open the repo's **Releases** page on your phone and download `app-debug.apk` from "Latest build", or download the `SleepScore-apk` artifact from the workflow run on a computer.
4. Open the APK on your phone and allow "install unknown apps" when asked.

## Or build locally

Install Android Studio, open this folder, press Run. Or from a terminal with the Android SDK installed: `./gradlew assembleDebug` (APK ends up in `app/build/outputs/apk/debug/`).

## Tips for best results

- Plug the phone in. Tracking runs all night and uses a steady amount of battery.
- Phone face down on the mattress near your torso gives the best movement signal. The nightstand works but only picks up big movements.
- On some phones (Xiaomi, Samsung, OnePlus, Huawei) set battery to "Unrestricted" for this app, otherwise the system may stop it overnight.
- Sleep stages are estimates from movement, noise, light and typical ~90 minute sleep cycles. Treat them as a guide.

## Where things are

- `app/src/main/java/app/sleepscore/engine/SleepAnalyzer.kt` is the sleep/wake, staging and scoring logic. All tunable numbers are at the top.
- `engine-test/` has a simulated-night test of that logic: `KOTLINC=/path/to/kotlinc ./engine-test/run-engine-test.sh`.
