# GNDEC Timetable — Lecture Reminder App

Native Android app for Guru Nanak Dev Engineering College (GNDEC) students.
It fetches the official college timetable, parses it (deterministic HTML parsing first,
AI only for ambiguous cells), shows your **next lecture**, and fires **local,
offline-capable lecture reminders** via `AlarmManager` — no network needed at reminder time.

Around that core the app has grown into a full student companion: attendance with a
leaderboard, a syllabus assistant, previous-year papers with RAG-powered Q&A, ERP notices,
holidays, live vacant-room availability, and a broadcast announcement feed.

**Current version:** `versionName 1.0` (`versionCode 1`) · `RELEASE_MARKER "1.0"`

## Features

### Timetable & reminders (fully offline)

- Dynamic group discovery from the official timetable HTML — nothing hard-coded
- Offline-first: Room-cached timetable; the app works fully without internet
- "Last fetched" freshness indicator + manual **↻ Fetch again** (ETag / Last-Modified aware)
- Next-lecture card with live countdown, happening-now state, free-period detection
- Full day view with completed / happening / upcoming / free states
- Local lecture reminders (15 / 30 / 5 minutes before and at start, individually toggleable)
  with a bundled custom notification sound
- Exact alarms with graceful fallback + a reliability checklist (battery, permissions, channel state)
- Alarms are rescheduled after reboot, app update, and **timezone change**

### Student profile

- Official GNDEC 2026 student lookup by branch PDF, duplicate-name disambiguation,
  local cache, and manual fallback
- Academic-year aware, with mentor / class-coordinator details

### Satellite features (backend-backed, degrade gracefully)

- **Attendance** — server-side records, per-subject and overall percentage, with a configurable target
- **Attendance leaderboard** — ranked comparison against other students
- **Syllabus assistant** — bring-your-own Gemini API key, per-branch syllabus browsing with a
  rich markdown renderer, and a persistent multi-session chat
- **Previous-year papers (PYQ)** — browsable papers plus grouped, RAG-backed "frequently asked" questions
  and an ask-anything search
- **Notices** — official ERP notices, cached for offline reading
- **Holidays** — official holiday and half-day list, surfaced on Home
- **Vacant rooms** — live room availability across every department, parsed on-device from the
  published room timetables and cached to disk
- **Announcements** — broadcast feed for all users, with per-branch / section / subsection targeting

### Reliability

- Notification channel health is inspected directly, so a muted or blocked reminder channel is
  reported honestly instead of showing "notifications enabled"
- A "Reminders will alert" verdict that accounts for the channel, not just the app-level toggle
- Timezone changes and DST transitions trigger alarm recalculation
- Failed timetable fetches never advance the "last successful" freshness value

## Screens

`onboarding` · `notification_onboarding` · `home` · `today` · `syllabus` ·
`previous_year_papers` · `frequently_asked` · `frequently_asked_group/{groupId}` ·
`alerts` · `notice` · `detail` · `attendance` · `vacant_rooms` · `profile` · `settings`

## Architecture

```
GNDEC official site → fetch HTML (ETag/Last-Modified) → Jsoup parser →
raw timetable → (deterministic | AI for ambiguous cells) → validation →
Room → ┬ Home / Today screens (instant, offline)
       └ AlarmManager → BroadcastReceiver → Notification  (internet NOT needed)

GNDEC 2026 branch PDF → OkHttp → on-device PDF text extraction →
student directory cache → name search → profile + temporary subsection/group

GNDEC dept room-timetable pages → on-device parse → disk cache → vacant-room availability
```

Lecture reminders are entirely local. The backend is optional and serves only the
satellite features; losing it degrades those screens but never the timetable or reminders.

### Backend (`/backend`)

Two independent deployables:

- **`backend/` (Node)** — a restricted cell-normalization proxy holding the server-side
  AI key. `POST /normalize`, `GET /health`.
- **`backend/pyq-rag/` (Node + Supabase)** — the satellite API. Attendance
  (`/attendance`, `/attendance/session`, `/attendance/records`), leaderboard
  (`/attendance/leaderboard`), notices, holidays, timetable source hints, and the PYQ RAG
  service (`/pyq/frequently-asked`, `/pyq/ask`, `/pyq/retry-course`), plus admin seeding
  and retry endpoints. Persists to Supabase/PostgreSQL with a vector index for the RAG search.

Firebase Cloud Messaging is present for announcement delivery, but **FCM is not required
for lecture reminders** — those fire locally regardless.

## Official 2026 student profile lookup

During first-time setup, the app lets the student choose CE, CS, EC, EE, IT, ME, or RAI. It downloads the corresponding **2026 Batch Temporary Section details** PDF from GNDEC’s official timetable page, extracts the student directory on-device, and keeps the parsed directory locally for offline reuse. Typing a name filters the branch list; if the same name appears more than once, the choices include the registration number so the student can identify the correct record.

After a match is selected, the app saves the candidate name, registration number, Sr. No. as the roll-number field, branch, temporary section, temporary subsection/timetable group, and mentor name. The Profile screen provides the same branch refresh and search workflow later, including a manual-entry fallback if the official PDF is unavailable or a correction is needed. The source PDFs are the seven branch links under the **2026 Batch Temporary Section details** section at [GNDEC’s timetable page](https://appsc.gndec.ac.in/time_tables).

## Local database

Room, `gndec_timetable.db`, **schema version 4**. Seven entities: `lectures`, `timetable_meta`,
`ai_cache`, `scheduled_alarms`, `syllabus_chat_sessions`, `syllabus_chat_messages`,
`timetable_snapshots`.

Migrations are chained `1 → 2 → 3 → 4` and are **mandatory** —
`fallbackToDestructiveMigration()` is deliberately not enabled, so a missing migration fails
loudly at startup instead of silently wiping a student's cached timetable, attendance snapshots
and scheduled reminders. Any future version bump must ship an explicit `Migration`, and each
one should be additive and idempotent (`CREATE TABLE IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS`)
so a replay is a no-op. `exportSchema` is on and schemas are written to `app/schemas/`.

Indexed columns: `lectures(groupName)`, `timetable_snapshots(groupName, attendanceDate)`,
`timetable_snapshots(attendanceDate)`, `syllabus_chat_messages(sessionId, timestamp)`.

## Build

Requires JDK 17+ and the Android SDK. AGP 8.5.2, Kotlin 2.0.20, KSP, `minSdk 26`,
`targetSdk 34`.

```bash
./gradlew testDebugUnitTest   # run unit tests
./gradlew assembleDebug       # build APK → app/build/outputs/apk/debug/app-debug.apk
```

### Release signing

Release builds require a keystore, supplied through the **gitignored** `local.properties`:

```properties
gndec.keystore.path=/absolute/path/to/release.jks
gndec.keystore.password=…
gndec.keystore.alias=…
gndec.keystore.keyPassword=…
```

If any release task is requested without a keystore, the build fails with a `GradleException`
rather than falling back to the shared AOSP debug key — a debug-signed release cannot be
installed over an existing release install, which would silently break the update path for
every user. Debug builds, unit tests and lint work with no keystore configured.

## GitHub Releases

Published APKs are distributed through the repository’s GitHub Releases page. The app’s **Download update** action opens `https://nextlecture-app.vercel.app/download`.

## Security

- No AI key is embedded in the APK or committed to the repo.
- User-provided keys are stored with Android Keystore-backed encrypted preferences.
- Keys are never logged, never sent to the backend, never included in analytics/crash logs.
- Student directory and profile data remain local to the device after download.
- Attendance is stored server-side; only the derived summary is kept on device.

## Reliability disclaimer

The app maximizes practical reliability (exact alarms, boot and timezone recovery, reliability
checklist) but cannot *guarantee* notifications: OEM battery restrictions, a blocked or muted
notification channel, disabled notifications, Do Not Disturb or a powered-off device can affect
delivery. The app detects and surfaces these conditions in Settings → Notification Reliability.

## Announcements for all users

The app reads [`announcements.json`](announcements.json) from the public `main` branch through GitHub’s raw-content endpoint. This is a lightweight broadcast feed rather than a real-time push service: devices check it when the app opens and during the existing network-constrained background refresh, then show each new announcement once as a local notification and on the **Home** screen card.

To publish an announcement from a phone, open the repository on GitHub, open `announcements.json`, choose **Edit**, and add an object inside the `announcements` array. Commit the change to `main`; installed apps will discover it on their next feed check. The in-app **Settings → App updates → Manage on GitHub** shortcut opens the mobile edit page for this file.

### Field reference

| Field | Required | Description |
|-------|----------|-------------|
| `id` | yes | Stable unique string. Changing `id` is treated as a **new** announcement (notification can fire again). |
| `title` | yes | Short headline shown on the Home card and in the notification. |
| `message` | yes | Body text. Supports lightweight **markdown** (see below). |
| `publishedAt` | recommended | ISO-8601 timestamp (e.g. `2026-09-16T10:00:00Z`). Among announcements that **match the student**, the app shows the one with the greatest `publishedAt`. |
| `type` | no | Visual style of the Home card. Default: `info`. |
| `link` | no | Optional URL. When set, **tapping anywhere on the card** opens it. When omitted or `""`, the card is not clickable. |
| `branch` | no | Audience filter. Empty / `"all"` → every branch. Single value (`"IT"`) or list (`"CS, IT"`) — student matches if their branch is **any** listed value. |
| `section` | no | Audience filter within the branch. Empty / `"all"` → every section. Single or list (`"ITA, ITB"`). |
| `subsection` | no | Audience filter within the section. Empty / `"all"` → every subgroup. Single or list (`"ITB1, ITB2"`). Matched against the student's saved group / subsection. |
| `for` | no | Which surface the announcement is for. Default: `both`. See **Surface targeting** below. |
| `active` | no | Set `false` to hide without deleting. Default: `true`. |

Only **active** announcements with non-blank `id`, `title`, and `message` that **match the student's profile** and are **intended for the Android app** are considered. **Every** matching announcement is shown on Home, one card under another, newest (`publishedAt`) first. The newest one is the one that may notify.

### Surface targeting (`for`)

The same `announcements.json` feeds both the Android app and the web app. Use `for`
to decide which surface an announcement belongs on:

| `for` | Android app | Web app |
|-------|-------------|---------|
| `both` (default) | Shown | Shown |
| `android-app` | Shown | Hidden |
| `webapp` | Hidden | Shown |

Matching is case-insensitive and ignores spaces, hyphens and underscores, so
`"Android App"` and `"android_app"` are the same as `"android-app"`.

`for` is applied **before** audience targeting, and an announcement must satisfy
both to appear in the app. A value the app does not recognise — or a missing
`for` key entirely — is treated as `both`, so an announcement with no stated
surface keeps reaching both clients rather than silently disappearing.

### Clearing the announcement

An announcement disappears once it is inactive, web-only, aimed at someone else,
or removed from the feed. The app re-checks on every refresh, and when a
successful fetch leaves nothing to show it also clears its local cache, so the
Home cards stay hidden on the next cold start instead of being restored from a
stale copy. A **failed** fetch keeps the cached cards — a network error should
not take valid announcements away.

### Audience targeting (personalised notices)

Targeting is hierarchical. Leave a field empty (or set `"all"`) to mean “everyone at that level”.

| `branch` | `section` | `subsection` | Who sees it |
|----------|-----------|--------------|-------------|
| *(empty)* / `all` | *(empty)* | *(empty)* | All students |
| `IT` | *(empty)* | *(empty)* | All IT students |
| `CS, IT` | *(empty)* | *(empty)* | All CS **or** IT students |
| `IT` | `ITB` | *(empty)* | All ITB section students |
| `IT` | `ITA, ITB` | *(empty)* | ITA or ITB students in IT |
| `IT` | `ITB` | `ITB2` | Only ITB2 subgroup |
| `IT` | `ITB` | `ITB1, ITB2` | ITB1 or ITB2 |

Lists use commas (or `;` / `|`). Within a field, values are **OR**’d; across fields, conditions are **AND**’d (branch must match **and** section must match, etc.).

Matching is case-insensitive and ignores spaces/hyphens (`itb2` = `ITB2` = `ITB-2`).
`subsection` is compared to the student’s timetable group / directory subsection (whichever is saved on the device).
If the student has no branch/section stored yet, only announcements with no targeting (or `all`) apply.

### Types (Home card style)

| `type` | Emoji / icon | Use for |
|--------|--------------|---------|
| `info` (default) | Campaign icon | Neutral updates |
| `notice` | 📌 | Official notices, date sheets, formal info |
| `warn` / `warning` | ⚠️ | Amber card — closures, schedule changes, caution |
| `happy` / `celebrate` | 🎉 | Green festive card — good news, results, events |
| `urgent` / `critical` / `alert` | 🚨 | Red card — time-sensitive or critical alerts |
| `update` / `feature` | ✨ | Sky/cyan card — app or feature updates |

### Markdown in `message`

The Home card renders a small markdown subset in the body:

- `**bold**`
- `*italic*` or `_italic_`
- `~~strikethrough~~`
- `[label](https://example.com)` — tappable link
- Bare `https://…` URLs — also tappable

Inline links stay independently tappable even when the card has a top-level `link`. Newlines in the JSON string become line breaks on the card.

> Known limitation: markup does not nest. `*~~text~~*` renders the `~~` literally, and a link
> inside `~~…~~` is not tappable.

### Whole-card link

- **With** `"link": "https://…"` → the entire announcement block is clickable and opens that URL; a small “Tap card to open” hint is shown.
- **Without** `link` (or empty string) → the block is not clickable as a whole; only markdown links inside `message` work.

### Example

```json
{
  "version": 2,
  "announcements": [
    {
      "id": "college-wide-holiday-note",
      "title": "Campus closed Friday",
      "message": "College will remain closed on **Friday** for maintenance.",
      "publishedAt": "2026-09-16T08:00:00Z",
      "type": "notice",
      "branch": "all",
      "section": "",
      "subsection": "",
      "link": "",
      "active": true
    },
    {
      "id": "it-lab-closed-2026-09-16",
      "title": "OS1 Lab closed today",
      "message": "The **OS1 Lab** is closed for maintenance.\nDetails on the [notice board](https://example.com/notice).",
      "publishedAt": "2026-09-16T10:00:00Z",
      "type": "warn",
      "branch": "IT",
      "section": "",
      "subsection": "",
      "link": "https://example.com/full-notice",
      "active": true
    },
    {
      "id": "itb2-mentor-meet",
      "title": "ITB2 mentor meeting",
      "message": "Mentor meeting at **3 PM** in F108.",
      "publishedAt": "2026-09-16T11:00:00Z",
      "type": "info",
      "branch": "IT",
      "section": "ITB",
      "subsection": "ITB2",
      "link": "",
      "for": "android-app",
      "active": true
    },
    {
      "id": "web-only-notice",
      "title": "Library portal migration",
      "message": "The library portal moves to a new address on **Monday**.",
      "publishedAt": "2026-09-16T12:00:00Z",
      "type": "info",
      "branch": "all",
      "section": "",
      "subsection": "",
      "link": "",
      "for": "webapp",
      "active": true
    }
  ]
}
```

- First entry: every student
- Second: all **IT** students (card opens `link`; body link still works alone)
- Third: only **ITB2** students, and never on the web app (`"for": "android-app"`)
- Fourth: web app only — the Android app never shows it

### Delivery notes

This feed is intentionally separate from lecture reminders. Lecture reminders remain local and offline-capable; announcement delivery depends on Android allowing the periodic check and on the device having connectivity at check time. The full eligible list is also cached on-device (including `type`, `link`, and `for`) so the Home cards can still render offline after the first successful fetch, and the cache is cleared as soon as a fetch returns nothing to show.

## GitHub release updates

The app checks the latest stable GitHub release from `https://api.github.com/repos/lsgzt/nextlecture-android/releases/latest` on startup when the cached result is older than six hours, and during the 12-hour background refresh. Settings also provides **Check for updates**.

Android `versionName` is currently `1.0`, and `BuildConfig.RELEASE_MARKER` is `1.0`. The two are compared by their numeric components (a leading `v` is ignored, non-numeric runs split the marker), so `1.1` is treated as newer and `1.0` as the same version. The installed marker is the greater of the two. Update both values in `app/build.gradle.kts` whenever publishing a new release, and keep them in sync.

When a newer stable release is found, Home and Settings show an update card and the app may send one update notification per release marker. **Download update** opens `https://nextlecture-app.vercel.app/download`. Android still requires the user to confirm the APK installation.
