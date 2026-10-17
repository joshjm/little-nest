# Screenshots

Little Nest 1.8 "Woven day" design. Every screenshot uses **synthetic demo data
only**: a baby called Ada, two caregivers called Sam and Alex, and two weeks of
made-up feeds, sleep, nappies and growth measurements. Select an image to see it
full size.

## Light (day)

| Today | Activity tiles | Running timer |
|:---:|:---:|:---:|
| [<img src="../docs/screenshots/today.png" alt="Today with the day weave, current state and coming-up care" width="240">](../docs/screenshots/today.png) | [<img src="../docs/screenshots/today-tiles.png" alt="Feed activity tiles with time since last feed" width="240">](../docs/screenshots/today-tiles.png) | [<img src="../docs/screenshots/timer.png" alt="A running breastfeeding timer with pause, switch side and finish" width="240">](../docs/screenshots/timer.png) |
| The day weave shows the last 18 hours as solid threads and suggested times as dashed outlines. Below it: last feed, awake time and today's nappies. | One tap logs a feed. Each tile shows how long ago it last happened. | Timers sit at the top of Today with large, steady numbers. |

| Bottle logging | Nap planning details | History |
|:---:|:---:|:---:|
| [<img src="../docs/screenshots/bottle-form.png" alt="Bottle entry form with times, milk type and amounts" width="240">](../docs/screenshots/bottle-form.png) | [<img src="../docs/screenshots/care-details.png" alt="Nap sweet spot details explaining how the estimate was made" width="240">](../docs/screenshots/care-details.png) | [<img src="../docs/screenshots/history.png" alt="History grouped by day with date controls and search" width="240">](../docs/screenshots/history.png) |
| The form title uses the feed thread colour. Details are optional. | Every estimate explains where it came from, in plain words. | Entries are grouped by day, with a date picker and search. |

| Trends | Daily comparison | Growth |
|:---:|:---:|:---:|
| [<img src="../docs/screenshots/trends.png" alt="Trends with a weekly summary and 7-day sleep pattern" width="240">](../docs/screenshots/trends.png) | [<img src="../docs/screenshots/trends-table.png" alt="Daily comparison table of feeds, nappies and sleep" width="240">](../docs/screenshots/trends-table.png) | [<img src="../docs/screenshots/growth.png" alt="Weight chart against WHO percentile curves" width="240">](../docs/screenshots/growth.png) |
| This week against last week, then a 7-day sleep pattern. | One row per day for quick comparison. | Measurements against WHO reference curves (from version 1.7). |

| Family |
|:---:|
| [<img src="../docs/screenshots/family.png" alt="Family profiles and preferences" width="240">](../docs/screenshots/family.png) |
| Profiles, caregivers, preferences and sync settings. |

## Dark (night)

The app follows the phone's dark-mode setting. The night palette is dim and
low-glare for 3 a.m. feeds, and keeps text contrast above 6:1.

| Today | History | Trends | Family |
|:---:|:---:|:---:|:---:|
| [<img src="../docs/screenshots/today-dark.png" alt="Today in the night palette" width="200">](../docs/screenshots/today-dark.png) | [<img src="../docs/screenshots/history-dark.png" alt="History in the night palette" width="200">](../docs/screenshots/history-dark.png) | [<img src="../docs/screenshots/trends-dark.png" alt="Trends in the night palette" width="200">](../docs/screenshots/trends-dark.png) | [<img src="../docs/screenshots/family-dark.png" alt="Family in the night palette" width="200">](../docs/screenshots/family-dark.png) |

## Make new screenshots

Use a disposable test emulator, never a phone with real family data. The demo
data comes from `app/src/androidTest/java/app/littlenest/DemoData.java`. It only
runs when asked with `-e demo true`, so normal test runs skip it.

```bash
source scripts/env.sh
export ANDROID_SERIAL=emulator-5554   # your own test emulator
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell pm clear app.littlenest     # wipes the app's data on this emulator
adb shell am instrument -w -e class app.littlenest.DemoData -e demo true \
  app.littlenest.test/androidx.test.runner.AndroidJUnitRunner
adb shell am start -n app.littlenest/.MainActivity
adb exec-out screencap -p > docs/screenshots/today.png
```

For the night set, run `adb shell cmd uimode night yes` before capturing and
`adb shell cmd uimode night no` afterwards. Reload the demo data before the
night set if you started a timer, so it does not appear in other screenshots.
Keep the file names above so the README and this page stay correct.
