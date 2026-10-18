# Design: Woven day

<!-- impeccable:design-schema 1 -->

Little Nest 1.8 replaces the 1.4 card look with **Woven day**. A baby's day is a
set of threads: feeds, sleep, nappies, health, and the parents' own care. Each
thread has one colour. The past is drawn as solid thread; planned or suggested
times are dashed outlines, never solid, because they are estimates.

Mode: **Operate**. People use the app tired, one-handed and often in a dark
room. Scanning speed and calm come before decoration.

## Colour

All tokens live in `Ui.java`. The palette follows the system dark setting
(`res/values-night/styles.xml`).

| Role | Day | Night | Use |
|---|---|---|---|
| `BG` | `#F7F4EC` warm paper | `#111613` | Screen background |
| `SURFACE` | `#FFFDF8` | `#1A211D` | Panels and dialogs |
| `INK` | `#26352C` | `#E8E4D8` | Body text |
| `MUTED` | `#576357` | `#A8B1A4` | Secondary text, axis labels |
| `GREEN` | `#2B5B45` forest | `#96C9A7` | Brand, primary action, "now" line |
| `LINE` | `#DDE0D4` | `#303A34` | Hairlines and stitches |

Thread colours (marks and graphics only, never body text):

| Thread | Day | Night | Kinds |
|---|---|---|---|
| `FEED` | `#C46E34` | `#E7A06E` | Breast, bottle, solids |
| `SLEEP` | `#4A659E` | `#93AEDB` | Sleep, parent sleep |
| `PUMP` | `#AE5268` | `#E296A8` | Pumping |
| `DIAPER` | `#A68026` | `#D9BC6A` | Diapers, tummy time, bath, story, routines |
| `HEALTH` | `#2C7C6A` | `#7FC7B4` | Growth, medicine, vaccines, temperature, visits |
| `PARENT` | `#7A5CA6` | `#BBA2DE` | Parent care |
| `MEMORY` | `#96628C` | `#D6A0C8` | Milestones, journal, photos |

Soft tints (`PEACH`, `BLUE`, `SAND`, …) back activity tiles and timeline lanes.
Text on every surface stays at 4.5:1 or better; `IdentityTest` checks the day
values and the night palette was checked by hand (lowest 6.0:1).

## Type

- System sans (Roboto). Sizes in sp so the system font scale applies, including
  text drawn on canvases (`Ui.sp`).
- Times, counts and table numbers use tabular figures (`Ui.figures`) so columns
  line up and timers do not jitter.
- Screen titles are short and warm: "A day at a time", "The story so far",
  "Find your rhythm", "Your people. Your data."

## Shape and structure

- `Ui.panel`: rounded grouping surface, a hairline edge by day and a lifted
  tone by night. No drop shadows.
- `Ui.section`: a plain heading above a panel. `Ui.stitch`: a dashed divider.
- `Ui.rowButton`: a full-width list row with a chevron, at least 52dp tall.
- Primary actions are forest-green pills; secondary actions are quiet tinted
  pills. Every touch target is at least 48dp.
- Bottom navigation: Today, Plan, History, Trends, Family, with a tinted indicator
  behind the selected icon.

## Signature components

- **Day weave** (`DayWeaveView`): the last 18 hours and the next 6 on one
  strip, one lane per thread. Solid marks are logged care, dashed outlines are
  suggested windows, and a green line marks now. The key under it lists only the
  threads actually drawn. TalkBack reads a plain summary of the same items.
- **History timeline**: rows grouped by day, each with time, a thread dot, the
  title, a short summary and the caregiver.
- **Trends**: a week-on-week summary, a 7-day sleep pattern, small charts in
  thread colours, and a daily comparison table. WHO growth charts
  (`GrowthUi`, `GrowthChartView`) sit in the same panels.
- **Plan**: optional daily sleep/nap/milk-session goals alongside partial-day
  logged totals, expanded prediction evidence, a learned evening bedtime
  pattern and age-specific context with source links. No scores or catch-up
  amounts; goals and guidance never drive predictions or alerts.

## Motion

Quiet, brief and optional. Native ripples on press and a 180ms selected-tab
settle connect navigation to its destination. Saving care on Today earns one
700ms wing flutter and rising heart in the nest illustration, not confetti,
scores or a blocking celebration. Background refreshes do not replay it.
Both effects honor Android's Remove animations setting; illustration motion
stops when the window hides or the view detaches. There are no looping GIFs,
network image requests, sounds or new animation dependencies.

## Nursery illustrations

`NestScene` draws original little birds, woven twigs and leafy branches in the
existing thread palette. Today has one chick in its nest, Plan a resting chick
under a moon, and Family/first use two birds sheltering a chick. Today switches
to moonlight in dark mode. These are compact header companions, never a layer
over care data or actions. They are decorative and hidden from TalkBack.

`NestIcon` gives each activity a recognizable symbol rather than a generic
heart: a bowl for food, bubbles for bath, a star for milestones, and dedicated
medicine, vaccination and thermometer marks. Activity tiles use 44dp tinted
icon patches; timers and planning rows repeat their matching care symbols.

## Do not

- Use thread colours for body text or large fills.
- Draw an estimate as solid; estimates are dashed.
- Add shadows, gradients or glass effects.
- Show real family data in screenshots; use `DemoData` (see the wiki
  [Screenshots](wiki/Screenshots.md) page).
