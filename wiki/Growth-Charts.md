# Growth charts

Open **Trends → Growing together** for weight, length/height and head
circumference. All recorded growth entries are included, including imported
history. Horizontal distance represents elapsed calendar time, not entry count.
Metric and imperial display preferences apply to both entries and references.
Use **View measurements** for dated values and approximate percentiles in text.

## What the lines mean

The grey curves are the WHO **3rd, 15th, 50th, 85th and 97th percentiles** for
children of the same age and sex. Solid green joins your recorded measurements.
The dashed green guide continues the **first recorded measurement's percentile**
across the displayed dates. If that entry was at birth, this is its birth
percentile; otherwise it is the percentile at the first recorded age.

The guide is not a forecast, a required path, or a study of children selected
for the same birth weight. Babies can change percentiles. A percentile is not
a grade, and the median is not a goal. Accurate repeated measurements and
clinical context matter more than one point. Discuss concerns with a clinician.
No diagnoses, alerts, target weights or catch-up amounts are generated.
The first-entry guide is omitted outside supported ages or z-scores -3 to +3,
rather than drawing an extreme extrapolation.

## Profile details and measurement positions

The corresponding WHO chart needs a birth date and recorded sex **Female** or
**Male**. Neither is guessed. Other/unrecorded sex or a missing birth date still
shows your measurements, without reference curves.

Actual age is the default. If an adjusted birth/due date later than the birth
date is saved, **Use adjusted age** explicitly switches the reference age to
that date. This is a temporary view choice, not a change to saved measurements
or profile data. Ask a clinician when to correct age and which preterm chart
to use. WHO charts here are not a premature-baby reference; there are no
percentiles before the selected birth/due date.

Length assumes measurement **lying down before 24 months**. From 24 months,
the WHO height table assumes **standing** measurement. The standards differ
by about 0.7 cm at this boundary. Reference lines are not joined across the
boundary; recorded measurements are never silently shifted. Record any
different measurement position in Notes and seek help interpreting it.

WHO references stop at 60 standard months. Older entries remain visible but
have no percentile. National guidance differs: for example, the US CDC
recommends WHO through age two and CDC charts after that. This app deliberately
labels its WHO standard and does not mix datasets.

## Research and reproducible data

The WHO Multicentre Growth Reference Study collected data from approximately
8,500 children in six countries and established the breastfed infant as the
normative model. It underpins the WHO birth-to-five standards, rather than
personalized predictions from birth weight.

Primary sources:

- [WHO study background and methods](https://www.who.int/tools/child-growth-standards/who-multicentre-growth-reference-study)
- [WHO weight-for-age tables](https://www.who.int/tools/child-growth-standards/standards/weight-for-age)
- [WHO length/height-for-age tables](https://www.who.int/tools/child-growth-standards/standards/length-height-for-age)
- [WHO head-circumference-for-age tables](https://www.who.int/tools/child-growth-standards/standards/head-circumference-for-age)
- [CDC interpretation and chart-transition guidance](https://www.cdc.gov/growth-chart-training/hcp/using-growth-charts/who-using.html)

Only factual age/L/M/S values are extracted from the WHO spreadsheets. WHO
artwork, logos and document text are not bundled; this independent app is not
endorsed by WHO. The data works offline and no measurements are sent to WHO.

`python3 scripts/update-growth-data.py` rebuilds the bundled CSV using Python's
standard library and curl. The [source manifest](../docs/who-growth-sources.txt)
records each official workbook URL and SHA-256. It includes weekly tables from
birth through 13 weeks, monthly tables through 60 months, and separate length
and standing-height tables. LMS values are linearly interpolated between
published ages, using 365.25/12 = 30.4375 days per standard month. Percentiles
are approximate, not a replacement for WHO Anthro's daily calculations.

For a z-score `z`, the curve value is `M * (1 + L*S*z)^(1/L)` or
`M * exp(S*z)` when `L = 0`. The inverse gives each entry's z-score; the standard
normal cumulative distribution gives its percentile. No birth-weight-specific
cohort, fitted growth forecast, BMI, weight-for-length, or growth-velocity
model is inferred. This release covers the three measurements recorded by the
Growth form; temperature, milk intake and parent vitals do not get child-growth
percentile curves.
