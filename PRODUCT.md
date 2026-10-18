# Product

<!-- impeccable:product-schema 1 -->

Facts below come from the README, wiki and source code. Items marked
_(inferred)_ were not confirmed in an interview; the maintainer asked for
autonomous work.

## Platform

android

## Users

Two caregivers of a newborn or young baby (often parents), each with their own
Android phone. They log feeds, sleep, nappies and their own wellbeing many
times a day, usually one-handed, tired and interrupted, often at night in a
dim room while holding or feeding the baby _(inferred from the feed/sleep
timers and night-sleep logging)_.

## Product Purpose

Little Nest is an offline-first baby and parent tracker. It makes the next
care decision easier ("when did she last feed, which side, how long has he
been awake?") and keeps both caregivers' records in one private shared nest.
Success: logging takes seconds, the current state of the baby is readable at a
glance, and both phones agree.

## Positioning

Private by construction: no account needed, no app backend, no ads,
analytics or subscriptions. Two phones sync directly over local Wi-Fi with a
QR pairing, or optionally through the caregivers' own OneDrive. Planning
estimates are explained from the family's own history, never presented as
medical advice.

## Operating Context

- Distributed as a signed APK through GitHub releases and Obtainium.
- Android 9+ (API 28), Java with programmatic Android Views, no Material
  Components library, no WebView.
- Activity CSV import from another tracker; JSON backup/restore; CSV export.
- Local reminders and opt-in advance alerts for suggested feed/nap windows.

## Capabilities and Constraints

- Activities: breastfeeding (left/right timer), bottle, pumping, solids,
  diapers, sleep, growth, medicine, vaccines, temperature, visits,
  milestones, routines, journal/photos, parent sleep/mood/vitals/meals,
  appointments and reminders.
- Multiple baby and parent profiles; caregiver attribution.
- Today, Plan, History, Trends and Family are the five destinations.
- Preserve storage keys, import identity seeds (`nara*`) and backup formats.
- Never show competitor branding in visible text.
- Planning text must keep its safety framing: estimates, not deadlines.

## Brand Commitments

- Name: Little Nest. Original woven-nest mark (`NestIcon` "nest").
- Forest green (`#2B5B45`) and warm paper (`#F7F4EC`) are the established
  brand colours from version 1.4.
- Calm, plain, kind voice. No hype, no gamification.

## Evidence on Hand

- Only synthetic demo data may appear in screenshots (`docs/screenshots/`).
- No testimonials, user counts or clinical validation exist; never claim any.

## Product Principles

1. State before history: what is happening now and what is next comes first.
2. One tap to log the common things; details are optional.
3. Private and honest: explain estimates and sync status plainly.
4. Built for tired hands and dark rooms.

## Accessibility & Inclusion

- Text contrast at least 4.5:1 on every surface (enforced by `IdentityTest`).
- 48 dp minimum touch targets; text sizes in sp so system font scale applies.
