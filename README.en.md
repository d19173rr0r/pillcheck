# Pills (pillcheck)

[Русская версия](README.md)

An Android app that won't let you forget or shift your medication. It reminds you right on time and keeps reminding until you mark the dose as taken.

Built for medicines where regularity matters: a missed or shifted dose can cause withdrawal symptoms.

> ⚠️ This is not a medical device and does not replace a doctor. Your doctor sets the schedule; the app only helps you stick to it.

The app is available in English and Russian and follows your phone's language. On Android 13+ you can pick a separate language just for this app.

<p>
  <img src="docs/screenshots/today.png" width="240" alt="Today">
  <img src="docs/screenshots/meds.png" width="240" alt="Medicines">
  <img src="docs/screenshots/history.png" width="240" alt="History">
</p>
<p>
  <img src="docs/screenshots/edit.png" width="240" alt="Medicine editor">
  <img src="docs/screenshots/settings.png" width="240" alt="Settings">
  <img src="docs/screenshots/widget.png" width="240" alt="Widget">
</p>

*Screenshots show the Russian version with made-up data.*

## Download

Get the APK from [Releases](../../releases/latest). Requires Android 12 or newer.

## Features

**Reminders**
- A notification exactly on time — even when the phone is asleep.
- Haven't marked the dose? The reminder repeats every 10 minutes with sound and vibration. After 12 hours the dose counts as missed.
- Buttons right in the notification: "Taken ✅" and "In 15 min".
- Several medicines at the same time — one summary notification with a "Took all" button.
- No medicine names on the lock screen.
- Reminders survive a reboot and time zone changes.

**Medicines**
- Any number of medicines and doses per day.
- Schedule: every day, every other day, or on certain weekdays.
- A course for a set number of days — "day 5 of 14"; reminders stop by themselves when it ends.
- A note for each dose: "before meals", "with water".
- Stock tracking and a reminder to buy more when 5 days are left.

**History**
- When each dose was taken and how far it drifted from the schedule.
- A streak of days without misses and a monthly completion rate.
- A text report for your doctor — send it to a messenger or by email.
- Backup to a file and restore — to move to a new phone.

**Look**
- Themes: follow system, light, dark, black (AMOLED).
- Accent color: from your wallpaper (Material You), 12 presets, or your own.
- A home screen widget in four styles (ring and list, list, pills, ring only) that adapts to its size; you can mark a dose right from the widget.

## Keep reminders from getting lost

Android, and especially manufacturers' skins, put apps to sleep in the background. After installing:

1. On the "Today" tab, tap every red card — notifications, exact reminders, battery restriction.
2. **OnePlus, Realme, OPPO:** *Settings → Apps → Pills → Battery usage* — allow background activity (and auto-launch if present). Lock the app in recents.
3. **Xiaomi:** enable autostart and set "Battery saver" to "No restrictions".
4. **Samsung:** remove the app from "Sleeping apps" in battery settings.

## Building

You need Android Studio (or JDK 21+ and Android SDK 37).

```bash
./gradlew assembleRelease
```

The APK appears in `app/build/outputs/apk/release/`. The release build is signed with the debug key of the machine it was built on — that's enough to install it outside Google Play.

## How it works

Kotlin + Jetpack Compose, no third-party libraries besides AndroidX.

| File | Contents |
|---|---|
| `Model.kt` | medicines, schedule, marks, stats, report, backup |
| `Reminders.kt` | alarm and notifications |
| `Screens.kt`, `SettingsScreen.kt` | screens |
| `Theme.kt` | themes and a palette from one color |
| `Widget.kt` | widget |
| `res/values/strings.xml`, `res/values-ru/strings.xml` | English and Russian texts |

- One alarm for the whole app — `AlarmManager.setAlarmClock`, the same mechanism the Clock app uses. It fires exactly, even in deep sleep.
- Every firing recomputes everything from the schedule and marks (`Reminders.sync`), so there is no state that could drift from reality.
- Data lives in `SharedPreferences`: the medicine list as one JSON string plus the intake marks. Nothing is sent anywhere.

## License

[MIT](LICENSE)
