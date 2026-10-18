# Screenshots

Little Nest's "Woven day" design, with the 1.12 illustrated nursery additions
on Today, Plan, Family, first use and activity tiles. Other views retain their
existing screenshots. Every screenshot uses **synthetic demo data
only**: a baby called Ada, two caregivers called Sam and Alex, and two weeks of
made-up feeds, sleep, nappies and growth measurements. Select an image to see it
full size.

## Light (day)

| Today | Activity tiles | Running timer |
|:---:|:---:|:---:|
| [<img src="../docs/screenshots/today.png" alt="Today with At a glance, the day weave and current state" width="240">](../docs/screenshots/today.png) | [<img src="../docs/screenshots/today-tiles.png" alt="Feed activity tiles with time since last feed" width="240">](../docs/screenshots/today-tiles.png) | [<img src="../docs/screenshots/timer.png" alt="A running breastfeeding timer with pause, switch side and finish" width="240">](../docs/screenshots/timer.png) |
| A chick in a woven nest accompanies the day weave. Solid threads are logged care; dashed outlines are suggested times. | Larger, original activity symbols help distinguish each kind of care. Each tile shows how long ago it last happened. | Timers repeat their care symbol and keep large, steady numbers. |

| First use | Plan |
|:---:|:---:|
| [<img src="../docs/screenshots/welcome.png" alt="First use with two birds sheltering a chick and create or join actions" width="240">](../docs/screenshots/welcome.png) | [<img src="../docs/screenshots/plan.png" alt="Plan with a resting chick and moon illustration above timing estimates" width="240">](../docs/screenshots/plan.png) |
| A little family in a woven nest welcomes you. Create a profile or join your partner. | A quiet moonlit illustration leaves the estimates and safety guidance in focus. |

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

[<img src="../docs/screenshots/plan-dark.png" alt="Plan with its moonlit chick in the night palette" width="240">](../docs/screenshots/plan-dark.png)

## Motion

Logging care on Today gives the chick one brief wing flutter and a rising
heart. Switching tabs gives the selected destination a gentle settle. These
native animations do not loop, download images or play sound, and both honor
Android's **Remove animations** setting. The artwork remains visible when
motion is disabled.

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
