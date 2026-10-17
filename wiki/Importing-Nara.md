# Import your Nara Baby export

## On your phone

1. Install Little Nest **1.2.0 or newer** over the existing installation. Export
   a complete JSON backup first if you already have Little Nest data.
2. Copy the original Nara **CSV export** to the phone, for example into Downloads.
   Do not resave it through a spreadsheet: that can change timestamps and IDs.
3. Open **Family → Import Nara Baby CSV**, then choose the file in Android's
   document picker. Parsing and validation happen locally.
4. Review the activity counts, warnings and destination profiles:
   - Keep **Create imported profile** for a new baby.
   - If that baby already exists in Little Nest, select **Use [name]** instead
     of creating a duplicate. Baby/Parent types must agree.
   - Nara's family-level pumping rows may have **no profile name or profile
     key**. These go into a separate **Pumping** parent profile by default.
     You can instead choose an existing baby or parent as their destination.
5. If the baby is still sleeping, check **Continue ongoing sleep timer(s)**.
   The timer continues from the **original Nara start**, including time after
   export. Finish it on **Today** when the baby wakes. Recent unfinished sleeps
   (less than 24 hours old) default to checked; older/future entries do not.
   If you are importing an old export, leave this unchecked, then correct the
   end time in History. The phone clock must be correct.
6. Tap **Import**. The complete batch is saved in one database transaction.
   Invalid files report the failing CSV record and do not partially import.
7. Check **History** and the profile selector. Search **needs review** for
   unresolved entries, open **Edit**, correct their times / side durations and
   uncheck **Imported entry still needs review** when satisfied.
8. Import on **one phone only**, then sync that phone and your partner's phone
   using the same shared Drive folder. The initial upload of thousands of
   immutable records can take a long time; use **Sync now** again if interrupted.

All imported profiles and source details are shared with your partner when Drive
is connected, including parent pumping logs. The private CSV is not bundled in
the APK, and importing does not contact Nara or upload data to an app server.

## What is preserved

- Millisecond epoch timestamps, source time zone, exact notes (including commas,
  quotes, newlines and trailing spaces), original/last-updating caregivers.
- Sleep and pumping durations in seconds, converted to exact milliseconds.
  Zero-duration pump entries remain valid manual volume records.
- Breastfeeding beginning and ending sides, separate left/right seconds, and
  Nara's manual-entry marker. Side ordering within a timer is not exported by
  Nara, so the importer does not invent detailed segments.
- Bottle breast milk and formula components, their independent units, formula
  name, mixed feeds and legacy total-only feeds.
- Pump left/right quantities with independent ml/fl oz units, or total-only
  output. An explicit total overrides components in volume totals; otherwise
  components are summed after conversion. The editor explains this precedence.
- Wet, dirty, mixed and dry diapers, rash, blowout, multiple stool colors and
  multiple textures. Missing details are not assumed to mean "No".
- Weight, length and head size with independent units; milestone text; profile
  birth date, adjusted birth date and sex. Adjusted age is shown in Family when
  an adjusted date is present.
- Every nonempty source column is retained as `naraSource` in the complete
  JSON backup and the Details cell of Little Nest's CSV export, including
  source IDs and future/extra columns. Common fields are editable and displayed
  directly in the app.

Dates and charts use the viewing phone's time zone. Original time zones remain
visible in event details. A missing sleep category becomes **Not recorded**, not
an invented Nap/Night classification. An unfinished sleep is either continued
by the explicit option or retained for review; a breastfeeding entry with
neither side duration is retained for review, never silently discarded.

CSV exports do not contain photos or notification settings. The importer cannot
reconstruct information absent from the export.

## Repeat imports and safety

Source family/profile/activity keys produce stable record identities. Reimporting
the same export does **not duplicate activities**, restart finished timers,
overwrite Little Nest edits, or resurrect deletions. New source activity IDs
are added. Existing records are skipped **even if their contents changed in a
later Nara export**. This is a migration tool, not ongoing two-way Nara sync.
Blank profile metadata can be filled in, but existing names/dates/values stay.

Use the same destination choices if you import another export. Import once and
sync rather than independently mapping profiles on each phone. If you initially
imported a sleep for review, reimport does not turn it into a live timer; correct
its end time in History instead.

Limits: UTF-8 CSV (optional BOM), 25 MB file, 100,000 records, 300 columns and
2 MB per CSV record. Quoted multiline cells, escaped quotes, CRLF/LF and Nara's
short profile-footer row are supported. Unknown activity types, unsupported enum
values, malformed rows, missing required identities, invalid units and negative
amounts produce explicit errors. Conflicting duplicate activity IDs reject the
file; identical duplicate rows are counted once with a warning.

History displays **100 entries per page** with previous/next controls and searches
the full stored history, not just the displayed page. Filtering by date or
changing profiles resets pagination.

## Import coverage

A large private export was examined and tested locally. Its contents, profile
identities, per-category counts and personal activity details are intentionally
not published. Public automated tests use synthetic records.

The importer handles family-level pumping without an assigned profile,
unfinished sleep and breastfeeding without recorded duration. It preserves
records for review rather than discarding them or inventing missing care.

Features added from import testing: mixed bottle components and formula names,
total-only pumping and per-side volume units, breastfeeding beginning/ending
sides and manual duration editing, diaper blowouts and multi-select stool
details, profile sex/adjusted date, ongoing sleep restoration, provenance, and
large-history pagination/indexing.
