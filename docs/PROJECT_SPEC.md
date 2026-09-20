# Revision Tracker — Implementation Spec

**Status:** Built. Phases 0–6 are all done and in daily use; this document
remains the reference for *why* each part works the way it does. Where the code
has since diverged, `NOTES.md` records it.
**Audience:** AI agents (and humans) picking this project up cold.
**Written:** 2026-09-17

---

## 1. What we are building

A revision-logging app for a UK GCSE student. The user revises across several
subjects, and wants to:

- **Log revision sessions** — which topic(s), for how long.
- **See time spent per topic / per subject**, accumulated over time.
- **Be told what to revise next**, rather than deciding it themselves.
- Eventually **use the same app on their Android phone**, with the two devices
  **staying in sync**.

The user already has a machine-readable index of their revision guides at
`gcse_revision_guides_contents.md` in this directory. That file is the seed data
for the topic tree (see §6).

### Success criteria for v1 (desktop)

1. User opens the app, picks a subject, picks one or more topics, hits start.
2. A clock runs. They can pause, and can switch which topic is currently active
   within the same session.
3. On stop, they rate each topic they covered 1–5 and can add a note.
4. The home screen shows a ranked "revise next" list that visibly reacts to
   what they have and have not done.
5. Closing and reopening the app loses nothing.

---

## 2. Decisions already made

These were chosen by the user. **Do not relitigate them** without asking.

| Decision | Choice | Reasoning |
|---|---|---|
| App type | **Native, not web** | User explicitly stated: "i would much prefer a native app on both platforms over a web app". A browser-based or Electron/Tauri-webview solution is off the table. |
| Stack | **Kotlin Multiplatform + Compose Multiplatform** | One Kotlin codebase produces a real Windows desktop app and a native Android app. Critically, **every tool it needs is already installed on this machine** (see §3) — Flutter would have required ~13GB of new downloads including Visual Studio 2022 with the C++ workload, which is not installed. |
| Mobile target | **Android only** | User has an Android phone. The Android SDK is already configured, so the app can be installed over USB with no developer account and no fee. iOS is explicitly out of scope; do not add iOS targets. |
| "What next?" logic | **Spaced repetition + coverage gaps + exam countdown weighting** | All three selected. Confidence rating was *not* selected — see §7.5 before adding it. |
| Session logging | **Live timer, plus manual backfill** | Timer is the primary path. Manual entry exists for sessions done away from the PC. |
| Multi-topic sessions | **Required** | User asked for "option to add multiple topics for the same subject under one session". This shapes the schema — see §5. |
| Sync transport | **Microsoft OneDrive** | User already pays for OneDrive and wants to use it. No server, no host, no auth to build. **Must follow §9 exactly — putting the database itself in the OneDrive folder will corrupt or silently destroy it.** |

### User profile

- GCSE student, so the app's users are a single person, non-technical usage.
- **Programming experience: "some Python".** Kotlin will be unfamiliar.
  - Explain Kotlin-specific concepts when they come up (coroutines, `Flow`,
    null safety, Gradle) — briefly, not as a tutorial.
  - Do not assume familiarity with Gradle, JVM tooling, or Android.
  - Keep the code readable over clever. Prefer obvious to concise.
- **The user has asked for planning to be written down for agents rather than
  executed immediately.** Respect that preference: when facing a large chunk of
  work, confirm the approach before writing thousands of lines.

---

## 3. Machine state (verified 2026-09-17)

Already installed — **use these, do not install alternatives**:

| Tool | Version | Notes |
|---|---|---|
| Android Studio | present | `C:\Program Files\Android\Android Studio` |
| Android SDK | API 37 | `%LOCALAPPDATA%\Android\Sdk` |
| Gradle | present | user caches at `%USERPROFILE%\.gradle` |
| Java | **25.0.2 LTS** | ⚠️ see warning below |
| Node.js | 24.19.0 | not needed for this project |
| Python | 3.10.6 | useful for one-off seed-data scripts |
| git | 2.42.0 | **this directory is not yet a git repo — initialise it** |

Explicitly **absent**: Visual Studio (any edition), Flutter, Dart, a modern
.NET SDK (only EOL 3.1 is present).

### JDK note

This was written expecting trouble: system Java was **25**, and the Android
Gradle Plugin was expected to reject a JDK that new, so early builds pointed
Gradle at Android Studio's bundled JetBrains Runtime via
`org.gradle.java.home` in `gradle.properties`.

**Resolved (2026-09-20).** AGP 9.3.1 and Kotlin 2.4.20 build fine on Java 25,
and that machine-specific line has been removed — it would have broken the
build for anyone else. Any **JDK 17 or newer** works; CI uses Temurin 21.

---

## 4. Project structure

Two Gradle modules. The split exists so the scheduling algorithm can be
unit-tested without any UI or Android dependency.

```
revision/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties              <- app.version lives here, used by both platforms
  gradle/libs.versions.toml      <- version catalog; all versions in one place
  local.properties               <- sdk.dir; MUST be gitignored
  .github/workflows/             <- tests on every push; installers on a version tag
  scripts/                       <- Windows build-and-install / run-from-source

  core/                          Kotlin Multiplatform library. No Compose.
    src/commonMain/kotlin/       models, SQLDelight queries, repositories,
                                 scheduler, sync engine, time formatting
    src/commonMain/sqldelight/   .sq schema + query files
    src/androidMain/kotlin/      Android SQLite driver factory
    src/desktopMain/kotlin/      JVM SQLite driver factory
    src/commonTest/kotlin/       scheduler + repository tests

  composeApp/                    Compose Multiplatform UI (package revision.app).
    src/commonMain/kotlin/       every screen, shared by both platforms
    src/desktopMain/kotlin/      fun main() + Window
    src/desktopTest/kotlin/      off-screen renders of every screen
    icons/app.ico                <- Windows installer / taskbar icon

  androidApp/                    Android entry point only (see the note below).

  docs/PROJECT_SPEC.md               <- this file
  docs/NOTES.md                      <- what was decided while building
  docs/gcse_revision_guides_contents.md   <- source data, do not delete
```

> **Implementation note (Phase 5):** Compose 1.12 requires AGP 9.1+, and AGP 9 no longer lets one
> module be both the Android app and the shared Kotlin Multiplatform module. So the layout is:
> `core` and `composeApp` are shared libraries (`com.android.kotlin.multiplatform.library` +
> `jvm("desktop")`), and a thin `androidApp` module holds only `MainActivity`, the manifest and
> `AndroidFileAccess`. Shared UI lives in package `revision.app` (Kotlin can't share the unnamed
> default package across modules). Versions: AGP 9.3.1, Kotlin 2.4.20.

**Rule: all UI goes in `composeApp/src/commonMain`.** Platform-specific source
sets should contain only the entry point and the database driver. If a screen is
being written twice, something has gone wrong.

### Libraries

Use the version catalog (`gradle/libs.versions.toml`) for everything.

| Purpose | Library | Note |
|---|---|---|
| UI | Compose Multiplatform | JetBrains, not AndroidX Compose |
| Database | **SQLDelight 2.x** | Generates typesafe Kotlin from SQL. Works identically on Android and JVM desktop. Chosen over Room for maturity on the desktop target. |
| Dates/times | `kotlinx-datetime` | Do **not** use `java.time` in `commonMain`. |
| Async | `kotlinx-coroutines` | Repositories expose `Flow` so the UI updates reactively. |
| Serialization | `kotlinx-serialization-json` | Needed for export/import and later sync. |
| Navigation | Compose Navigation (multiplatform) or a hand-rolled sealed-class screen state | For ~6 screens, a hand-rolled `sealed interface Screen` is perfectly acceptable and avoids a dependency. Prefer it unless nested navigation appears. |
| DI | **None** | Do not add Koin/Hilt. Construct repositories once at app start and pass them down. The app is too small to justify it. |

**Version numbers:** this document deliberately does not pin them — they will be
stale. Resolve current stable versions at implementation time (the JetBrains
Kotlin Multiplatform wizard at the official KMP site emits a known-good
combination of Kotlin / AGP / Compose Multiplatform versions; use it as the
reference for a compatible set rather than picking each independently, since
Kotlin and Compose Multiplatform versions are tightly coupled).

---

## 5. Data model

SQLDelight `.sq` files in `core/src/commonMain/sqldelight/`.

### 5.1 Two rules that must not be broken

These exist so that **sync in Phase 6 is easy rather than a rewrite**. They cost
almost nothing now and are extremely expensive to retrofit.

1. **Every primary key is a UUID string, never an autoincrementing integer.**
   Two devices offline both creating "session 42" is an unresolvable collision.
   UUIDs never collide.
2. **Every table has `updated_at` (epoch millis) and `deleted` (0/1).**
   Deletes are *soft* — flip the flag, never `DELETE FROM`. A row that vanishes
   cannot be told apart from a row the other device has not seen yet. All
   queries must filter `WHERE deleted = 0`.

### 5.2 Schema

```sql
-- A subject: Biology, Maths, French...
CREATE TABLE subject (
    id          TEXT NOT NULL PRIMARY KEY,   -- UUID
    name        TEXT NOT NULL,
    colour      TEXT NOT NULL,               -- hex, for charts & chips
    exam_board  TEXT,                        -- 'AQA', 'OCR', nullable
    exam_date   INTEGER,                     -- epoch millis, nullable
    sort_order  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0
);

-- A topic. Self-referencing so guides with sections/sub-topics nest.
-- Geography and Computer Science need 2 levels; the sciences need 1.
CREATE TABLE topic (
    id          TEXT NOT NULL PRIMARY KEY,   -- UUID
    subject_id  TEXT NOT NULL,
    parent_id   TEXT,                        -- NULL = top level
    code        TEXT,                        -- 'B1', '2.3', nullable
    title       TEXT NOT NULL,
    page_start  INTEGER,                     -- nullable
    sort_order  INTEGER NOT NULL,
    notes       TEXT,
    updated_at  INTEGER NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (subject_id) REFERENCES subject(id),
    FOREIGN KEY (parent_id)  REFERENCES topic(id)
);

-- Spaced-repetition state. One row per topic, created lazily on first revision.
CREATE TABLE topic_state (
    topic_id        TEXT NOT NULL PRIMARY KEY,
    last_revised_at INTEGER,                 -- epoch millis, NULL = never
    due_at          INTEGER,                 -- epoch millis, NULL = never
    interval_days   REAL NOT NULL DEFAULT 0,
    ease_factor     REAL NOT NULL DEFAULT 2.5,
    repetitions     INTEGER NOT NULL DEFAULT 0,
    confidence      INTEGER,                 -- 1-5, reserved, see 7.5
    updated_at      INTEGER NOT NULL,
    deleted         INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (topic_id) REFERENCES topic(id)
);

-- One sitting. Belongs to exactly one subject.
CREATE TABLE session (
    id          TEXT NOT NULL PRIMARY KEY,
    subject_id  TEXT NOT NULL,
    started_at  INTEGER NOT NULL,
    ended_at    INTEGER,                     -- NULL = still running
    notes       TEXT,
    is_manual   INTEGER NOT NULL DEFAULT 0,  -- 1 = typed in after the fact
    updated_at  INTEGER NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (subject_id) REFERENCES subject(id)
);

-- Join row: one per topic covered in a session. THIS is what enables
-- multiple topics under one session, which the user explicitly asked for.
CREATE TABLE session_topic (
    id          TEXT NOT NULL PRIMARY KEY,
    session_id  TEXT NOT NULL,
    topic_id    TEXT NOT NULL,
    rating      INTEGER,                     -- 1-5, set on stop
    notes       TEXT,
    updated_at  INTEGER NOT NULL,
    deleted     INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (session_id) REFERENCES session(id),
    FOREIGN KEY (topic_id)   REFERENCES topic(id)
);

-- A contiguous run of the clock against ONE topic.
-- Pausing closes a segment; resuming opens a new one; switching the active
-- topic closes one and opens another under a different session_topic.
CREATE TABLE segment (
    id               TEXT NOT NULL PRIMARY KEY,
    session_topic_id TEXT NOT NULL,
    started_at       INTEGER NOT NULL,
    ended_at         INTEGER,                -- NULL = currently running
    updated_at       INTEGER NOT NULL,
    deleted          INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (session_topic_id) REFERENCES session_topic(id)
);

CREATE TABLE setting (
    key        TEXT NOT NULL PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);
```

### 5.3 Why segments

Storing raw start/stop instants rather than a running `seconds` integer gives
three things for free:

- **Accurate pause** — a pause is just closing a segment.
- **Crash recovery** — on launch, `SELECT * FROM segment WHERE ended_at IS NULL`.
  If a row comes back, the app died mid-session. Offer the user *"You had a
  session running since 14:20. Resume it, save it as-is, or discard it?"*
  **Never silently discard, and never silently count the hours the PC was
  asleep.** If the dangling segment is implausibly long (say > 4 hours),
  default the dialog to discarding.
- **Honest time-per-topic** — `SUM(ended_at - started_at)` per `session_topic`.

Time totals should always be derived by query, never cached in a column that can
drift out of date.

---

## 6. Seed data

On first launch the database must be populated. Put the seed in
`core/src/commonMain/` as Kotlin data (a simple list of data classes is fine and
is easier to review than parsing markdown at runtime).

**Do not parse `gcse_revision_guides_contents.md` at runtime.** Read it once
during implementation and transcribe it into Kotlin. It is a reference document,
not a data file.

### 6.1 From the existing document

`gcse_revision_guides_contents.md` covers, with page numbers:

| Subject | Structure | Confidence per source doc |
|---|---|---|
| Biology | B1–B20, flat | HIGH |
| Chemistry | C1–C20, flat | HIGH |
| Physics | P1–P19, flat | HIGH |
| Computer Science | 2 components → 7 sections → sub-topics | titles HIGH, **pages unreliable** |
| English Literature | 3 texts, no contents pages | text names only |
| Geography | units → sections → chapters → sub-topics | pages 6–78 HIGH, ch.12+ approximate |
| History | sections → 11 chapters → sub-topics | HIGH |

Notes when transcribing:

- **Skip the "reference" rows** (Periodic Table p.222, Physics equations p.224,
  Practice Papers, Answers, Glossary & Index). They are not revisable topics.
- **Exam focus / Glossary rows inside History chapters** — keep "Exam focus" as a
  topic, drop "Glossary".
- **English Literature has no topic breakdown.** See §6.2 — it needs generated
  topics *and* a poetry anthology the guides do not cover at all.
- **History**: the book covers 11 chapters but the student takes only 4.
  **Confirmed with the user (2026-09-17) — seed only these four:**

  | Section | Chapter | Doc reference |
  |---|---|---|
  | 1A World History Period Studies | **2. Germany, 1890–1945** | §5, pp.30–51 |
  | 1B Wider World Depth Studies | **4. Conflict and tension, 1894–1918** | §5, pp.76–97 |
  | 2A British Thematic Studies | **8. Health and the people, c1000–present** | §5, pp.166–186 |
  | 2B British Depth Studies | **11. Elizabethan England, c1568–1603** | §5, pp.234–253 |

  Do **not** seed chapters 1, 3, 5, 6, 7, 9 or 10. They are not on this
  student's course and would be pure noise in the "revise next" queue.
  Keep the "Spelling, Punctuation and Grammar" entry (p.253) as a topic under
  Elizabethan England, since it sits within that chapter in the book.
- **Geography**: the guide prints every optional chapter, but AQA Geography
  requires the student to *choose* within three sections. **Confirmed with the
  user (2026-09-17):**

  | Section | Choice | Seed | Do NOT seed |
  |---|---|---|---|
  | Paper 1 §B — The living world | one biome | **Ch.8 Cold environments** | Ch.7 Hot deserts |
  | Paper 1 §C — UK physical landscapes | two of three | **Ch.10 Coastal** + **Ch.11 River** | Ch.12 Glacial |
  | Paper 2 §C — Resource management | one resource | **Ch.22 Energy management** | Ch.20 Food, Ch.21 Water |

  Everything else in Geography is compulsory and should be seeded: natural
  hazards, ecosystems, tropical rainforests, UK landscapes overview, urban
  issues, the changing economic world, Nigeria, the changing UK economy,
  resource management overview, issue evaluation and fieldwork.
  **Note:** the source document's page numbers for chapters 12 onward are
  flagged approximate. Since ch.12 is now excluded anyway, the remaining
  uncertainty mostly affects the Paper 2 chapters — leave `page_start` NULL
  wherever the document hedges rather than recording a number that may mislead.
- **Computer Science page numbers are explicitly flagged LOW confidence** in the
  source. Seed the titles, leave `page_start` NULL rather than recording numbers
  that may be wrong.

### 6.2 English Literature — partly missing from the source document

The source document lists only three CGP text guides and no contents pages. The
user confirmed (2026-09-17) they also study the **AQA "Power and Conflict"
poetry anthology**, for which they own no guide. Poetry is therefore *absent
from the source file entirely* and must be generated.

Seed English Literature as **one subject with four children**:

- **Macbeth** — Plot & structure; Characters; Themes; Context; Language &
  dramatic techniques; Key quotations; Exam practice
- **A Christmas Carol** — same seven sub-topics
- **An Inspector Calls** — same seven sub-topics
- **Poetry: Power and Conflict** — **each of the 15 anthology poems is its own
  topic**, plus *Comparing poems* and *Unseen poetry*:
  Ozymandias (Shelley); London (Blake); The Prelude: stealing the boat
  (Wordsworth); My Last Duchess (Browning); The Charge of the Light Brigade
  (Tennyson); Exposure (Owen); Storm on the Island (Heaney); Bayonet Charge
  (Hughes); Remains (Armitage); Poppies (Weir); War Photographer (Duffy);
  Tissue (Dharker); The Emigrée (Rumens); Checking Out Me History (Agard);
  Kamikaze (Garland).

Why individual poems rather than one "poetry" topic: 15 poems behave completely
differently under spaced repetition — the student will know four of them cold
and keep forgetting the rest. One lumped topic hides exactly the information the
scheduler exists to surface. *Comparing poems* and *Unseen poetry* are separate
because they are distinct exam skills, not content.

The seven sub-topics per text are a **reasonable guess, not transcription**.
Flag them in the UI (or a first-run note) as editable suggestions.

### 6.3 Maths and French — NOT in the source document

The user raised this unprompted:

> "maths and french are not mentioned in this document as i do not have topic
> books but still would like to revise"

So these two subjects have **no book and no page numbers**. Generate a sensible
standard GCSE topic list for each, seed it with `page_start = NULL`, and make
clear in the UI (or in a first-run note) that these lists are editable
suggestions rather than transcriptions.

**Confirmed with the user (2026-09-17): both are AQA, and Maths is HIGHER
tier.** Seed accordingly — no need to re-ask.

**Maths — Higher tier, AQA.** Six standard strands. Seed the full list below;
it is already the Higher set. (Topic content is near-identical across boards, so
the board matters little here; tier matters a lot, and Higher is confirmed.)

- *Number* — place value & rounding; factors, multiples & primes; fractions;
  decimals; percentages; ratio & proportion; indices; standard form; surds;
  bounds & error intervals
- *Algebra* — notation & simplifying; expanding & factorising; linear equations;
  rearranging formulae; sequences; coordinates & straight-line graphs;
  quadratics (graphs & solving); simultaneous equations; inequalities;
  functions; graph transformations; iteration; algebraic fractions; proof
- *Ratio, proportion & rates of change* — ratio problems; direct & inverse
  proportion; compound measures (speed, density, pressure); growth & decay;
  gradients as rates
- *Geometry & measures* — angles & parallel lines; polygons; triangles &
  congruence; similarity; transformations; constructions & loci; Pythagoras;
  trigonometry (SOHCAHTOA); exact trig values; sine & cosine rules; circle
  theorems; area & perimeter; volume & surface area; vectors
- *Probability* — basic probability; relative frequency & expected outcomes;
  sample space & two-way tables; tree diagrams; Venn diagrams & set notation;
  conditional probability
- *Statistics* — sampling; averages & range; averages from frequency tables;
  cumulative frequency & box plots; histograms; scatter graphs & correlation;
  time series; misleading graphs

**French — AQA (confirmed).** The current AQA GCSE French specification is
organised into three themes. The board is settled; still sanity-check the theme
titles against the live AQA specification at implementation time, since the
French spec was reformed recently and this list is written from memory rather
than from a document the user owns.

- *Theme 1: People and lifestyle* — identity & relationships; healthy living &
  lifestyle; education & work
- *Theme 2: Popular culture* — free-time activities; media & technology;
  celebrations & festivals
- *Theme 3: Communication and the world around us* — travel & tourism; the
  environment; where people live
- *Grammar* (add as its own top-level group — grammar is revised separately from
  vocabulary) — present tense; perfect tense; imperfect tense; near future &
  future; conditional; subjunctive; reflexive verbs; negatives; questions;
  adjectives & agreement; pronouns (direct, indirect, y, en); comparatives &
  superlatives; prepositions; connectives & opinions
- *Exam skills* — listening; reading; translation into English; translation into
  French; speaking role-play; speaking photo card; speaking general
  conversation; writing

### 6.4 Subject list for seeding

**Confirmed correct and complete by the user (2026-09-17). Seed exactly these
nine, no more, no fewer:**

Biology, Chemistry, Physics, Computer Science, English Literature, Geography,
History, Maths, French.

Give each a distinct `colour` — they are used for chips and charts throughout
the UI, and nine subjects need nine visually separable hues. Check they remain
distinguishable in both light and dark themes.

---

## 7. The "revise next" algorithm

Lives in `core/src/commonMain/kotlin/.../scheduling/`. Must be a **pure
function** — takes a snapshot of topic state plus `now`, returns a sorted list.
No database access inside it, no clock access inside it. This makes it trivially
testable, and it **must have unit tests** (§7.6).

### 7.1 Spaced repetition (SM-2 variant)

Runs when a session is stopped and each covered topic has been rated 1–5.

```
Given rating q (1..5) for a topic:

if q < 3:                                  // struggled
    repetitions   = 0
    interval_days = 1.0
else:
    interval_days = when (repetitions) {
        0    -> 1.0
        1    -> 3.0
        else -> interval_days * ease_factor
    }
    repetitions  += 1

// ease factor drifts with performance, floor of 1.3
ease_factor += 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)
ease_factor  = max(1.3, ease_factor)

interval_days   = min(interval_days, 120.0)   // cap; GCSE horizon is ~1 year
last_revised_at = now
due_at          = now + interval_days.days
```

Deviation from textbook SM-2: the second interval is **3 days, not 6**. With
~150 seeded topics and a fixed exam date, the standard curve spaces things out
faster than a GCSE timetable can absorb. Put this and every other tunable in a
single `SchedulerConfig` object so it can be adjusted without hunting through
the codebase.

If a session covered a topic but the user skipped the rating, **do not touch the
SM-2 state** — update `last_revised_at` only. A missing rating is not a bad one.

### 7.2 Priority score

The queue is sorted by a single score. Three of the four signals the user chose
feed into it.

```
priority =
      100 * overdue        // spaced repetition
    +  60 * neverRevised   // coverage gaps
    +  40 * staleness      // coverage gaps
    +  50 * examPressure   // exam countdown
    -  30 * justRevised    // anti-repetition damping
```

Where, with all terms clamped to `0.0..1.0`:

| Term | Definition |
|---|---|
| `overdue` | `0.0` if `due_at` is in the future or null. Otherwise `daysOverdue / max(interval_days, 1)`, clamped to 1.0. A topic one full interval late scores the maximum. |
| `neverRevised` | `1.0` if `last_revised_at` is null, else `0.0`. |
| `staleness` | `0.0` if never revised (already covered by the term above, do not double-count). Otherwise `daysSinceLastRevised / 30`, clamped to 1.0. |
| `examPressure` | If the subject has an `exam_date`: `(180 - daysUntilExam) / 180`, clamped. Exam within a week ≈ 0.96; exam six months out ≈ 0.0. If the exam has **passed**, return `0.0` and consider auto-archiving the subject. If no date is set: `0.3` (neutral). |
| `justRevised` | `1.0` if revised in the last 24h, else `0.0`. Stops a topic just finished from reappearing at the top. |

Weights live in `SchedulerConfig`. The user should be able to see *why* a topic
is at the top — surface a one-line reason in the UI ("not revised yet",
"9 days overdue", "Physics exam in 12 days"), derived from whichever term
contributed most.

### 7.3 Exam dates

`subject.exam_date` is nullable and starts null. Prompt for exam dates in
Settings, not on first run — the user said exam-countdown weighting is
*"only worth including once you have your actual exam timetable"*, which they may
not have yet. The neutral 0.3 fallback means the feature degrades gracefully.

### 7.4 Which topics enter the queue

- Only **leaf** topics (no children). A parent like "Section Three — Networks" is
  a grouping, not something you revise. Roll child stats up to parents for
  display, but never queue a parent.
- Exclude `deleted = 1` and any archived subject.
- Cap the visible queue at ~10 items. A list of 150 topics sorted by score is
  not actionable, and seeing it is demoralising.
- Show at most 2–3 topics per subject in the top 10, so one subject with a near
  exam cannot monopolise the whole list.

### 7.5 Confidence rating — deliberately deferred

The `topic_state.confidence` column exists but **is not used**. The user was
offered confidence-based prioritisation and did not select it. Leave the column
(free to have, expensive to add later) but do not build UI for it or add it to
the score without asking first.

### 7.6 Required tests

`core/src/commonTest/`. These are the tests that actually matter — the UI can be
checked by eye, the algorithm cannot.

- A never-revised topic outranks a topic revised yesterday.
- Rating 5 pushes `due_at` further out than rating 3; rating 2 resets the
  interval to 1 day.
- `ease_factor` never falls below 1.3 across 50 consecutive rating-1 reviews.
- A topic revised 10 minutes ago does not appear in the top 10.
- With two subjects identical in every way except exam date, the one with the
  nearer exam ranks higher.
- A subject with no exam date is not crowded out entirely by one with a date.
- An overdue topic in a subject whose exam has passed does not top the queue.
- The queue is stable: calling it twice with the same inputs gives the same
  order (no ties broken randomly — break ties deterministically, e.g. by topic
  id, or the list will visibly shuffle on every recomposition).

---

## 8. Screens

All in `composeApp/src/commonMain`. Use Material 3. Support light and dark.

### 8.1 Today (home)

- **Active session banner** if one is running — elapsed time, current topic,
  tap to return to the timer. Must be visible from every screen.
- **"Revise next"** — the top ~10 from §7, each showing subject colour, topic
  title, the one-line reason, and last-revised ("6 days ago" / "never").
  Tapping one starts a session on it immediately — this is the primary action
  of the entire app, so it must be one tap, not three.
- **Today's total**, and a small 7-day bar chart.

**No time target.** The user chose (2026-09-17) to have the Today screen simply
report time done — "40 minutes today, 3 hours this week" — rather than show
progress toward a daily or weekly goal. Do not add a target, a streak pressure
mechanic, or "you're behind" messaging without asking. Reporting is the brief.

### 8.2 Timer / session

The core screen. Flow:

1. Pick a subject (or arrive pre-filled from the Today screen).
2. Pick one or more topics from that subject. **A session is locked to one
   subject** — this was the user's explicit framing ("multiple topics for the
   same subject under one session").
3. Start. A large, readable clock.
4. **The active topic is switchable mid-session** without stopping the clock.
   Topics appear as a row of chips; tapping a different chip closes the current
   `segment` and opens a new one. Each chip shows its own accumulated time.
5. Topics can be **added mid-session** — the user may wander into something they
   did not plan.
6. Pause / resume.
7. Stop → rating sheet listing every topic covered with its time, a 1–5 rating
   each, optional per-topic note, and an optional session note. Ratings are
   skippable (see §7.1).

Keep the clock driven by a coroutine ticking once per second, but **compute
elapsed time from stored timestamps, not by incrementing a counter** — a counter
drifts, and silently breaks if the app is backgrounded or the machine sleeps.

### 8.3 Subjects & topics

- Tree view, expand/collapse, subject colour, per-topic total time and
  last-revised.
- Add / rename / reorder / archive at both subject and topic level. Archiving
  rather than deleting matters because several seeded lists are educated
  guesses: English Literature's per-text sub-topics, the whole Maths and French
  trees, and any Geography chapter the student's course skips.
- Bulk archive (select several, archive together), and bulk add — the user will
  need to add topics for anything the guides missed.
- Set exam date and exam board per subject.

### 8.4 History

- Sessions in reverse-chronological order, grouped by day, with subject, topics,
  duration and rating.
- Edit or delete a past session; fix a wrong duration.
- **"Log a past session"** — the manual-entry path: subject, topics, date, total
  minutes, ratings. Writes `is_manual = 1` and synthesises a single segment per
  topic so the totals queries need no special case.

### 8.5 Stats

- Total time per subject (the obvious headline number).
- Time per topic within a subject.
- Coverage: how many topics in each subject have never been revised. This is the
  number most likely to change behaviour, so give it prominence.
- Streak / days active.
- Keep this screen simple. It is the easiest place to over-build and the least
  important to v1.

### 8.6 Settings

- Exam dates and boards.
- Export database to JSON; import it back. **Build this early — it is the
  stand-in for sync** and the only backup that exists before Phase 6.
- Scheduler weight tuning (optional, behind an "advanced" disclosure).
- Reseed / reset, with a confirmation dialog.

---

## 9. Sync via OneDrive (Phase 6 — design now, build later)

> **Change (2026-09-18): the user chose Google Drive instead of OneDrive.** Nothing in this design is
> OneDrive-specific — it only needs a folder both devices can read and write — so everything below
> applies to Google Drive unchanged (Google Drive for desktop on Windows; the Drive app's document
> provider on Android). Read "OneDrive" below as "the shared cloud folder".

**The user already pays for Microsoft OneDrive and wants to use it as the sync
transport** (confirmed 2026-09-17). This is a good call: it removes the need for
a server, a host, a domain and an auth system entirely. But it must be done in a
specific way, described below.

Nothing in Phases 0–5 should make this harder. Follow §5.1 — UUID primary keys,
`updated_at` on every row, soft deletes — and this section stays easy. Those
rules are *more* important with OneDrive, not less.

### 9.1 ⚠️ Do NOT put the SQLite database in the OneDrive folder

This is the single most likely way to destroy the user's data, and it is the
obvious thing to try. Three independent reasons it fails:

1. **OneDrive cannot merge files.** It syncs whole files. If the PC and the
   phone both touch the database, OneDrive keeps one version and renames the
   other to something like `revision-DESKTOP-ABC123.db`. To the app, half the
   revision history silently disappears.
2. **SQLite is not one file.** It uses `-wal`, `-shm` and `-journal` sidecar
   files. OneDrive syncs each independently, so a device can receive a main
   database file paired with a stale or missing WAL. That is not "slightly out
   of date" — that is a **corrupt database**.
3. **OneDrive can sync mid-write**, capturing a torn, half-committed file.

The local database stays in `%LOCALAPPDATA%\RevisionTracker\` (§10) and never
moves. OneDrive is used to exchange *change logs*, not the database.

### 9.2 The design: one append-only log per device

```
OneDrive/Apps/RevisionTracker/
  devices/
    <device-uuid-A>.jsonl      <- written ONLY by device A (the PC)
    <device-uuid-B>.jsonl      <- written ONLY by device B (the phone)
  snapshots/
    <device-uuid-A>-<epoch>.json   <- periodic full backup, see 9.5
```

**The entire trick is this invariant: a device only ever writes to its own file,
and only ever reads the others.** No file has two writers, so OneDrive never has
a conflict to resolve and never creates a conflict copy. Everything else follows
from that.

Each device generates a UUID on first run and stores it in `setting`.

One JSON object per line:

```json
{"seq":1421,"table":"session_topic","id":"<row-uuid>","updated_at":1789412345678,"deleted":0,"data":{...}}
```

`seq` is a per-device monotonically increasing counter, so readers can resume
from a cursor instead of re-reading the whole file.

### 9.3 Push and pull

**Push** — debounced (say 30s after the last change, and on app close):
append every local row with `updated_at > last_pushed_at` to this device's own
file, then advance `last_pushed_at`.

**Do not push a session that is still running.** A row with `ended_at IS NULL`
or an open `segment` arriving on the other device is confusing and can produce a
phantom running timer. Only push rows belonging to completed sessions.

**Pull** — read every file in `devices/` *except* this device's own. Track a
local cursor per remote device:

```sql
CREATE TABLE sync_cursor (
    device_id  TEXT NOT NULL PRIMARY KEY,
    last_seq   INTEGER NOT NULL,
    generation INTEGER NOT NULL DEFAULT 0   -- see 9.5
);
```

Apply each record with `seq > last_seq`:

- Row absent locally → insert it.
- Row present → **last-write-wins on `updated_at`**; keep the higher value.
- `updated_at` identical → break the tie on device id, lexicographically. Never
  leave it to chance, or the two devices can settle on different winners and
  ping-pong forever.
- `deleted = 1` is applied like any other change; tombstones propagate normally.

Last-write-wins is crude and can lose an edit if the same row is changed on both
devices while offline. For one student with a PC and a phone, the realistic
conflict is negligible. **Do not build CRDTs.**

### 9.4 Platform notes

**Windows** — straightforward. The OneDrive folder is a real directory; locate
it via the `%OneDrive%` environment variable, and let the user override the path
in Settings. Handle **Files On-Demand**: a file may be a cloud placeholder, so
the first read triggers a download and can block for seconds. Do all sync work
off the UI thread and show a status indicator.

**Android — this is the hard part, and it should be spiked before it is
promised.** The OneDrive Android app does *not* give other apps a locally synced
folder the way the Windows client does. Two viable routes:

- **(a) Storage Access Framework.** The OneDrive app registers as a
  `DocumentsProvider`, so `ACTION_OPEN_DOCUMENT_TREE` lets the user point the
  app at the `RevisionTracker` folder once, and a persisted URI permission keeps
  that access across restarts. No Azure registration, no OAuth, far less code.
  Risk: OneDrive's provider can be slow, and its behaviour is not contractually
  guaranteed.
- **(b) Microsoft Graph API + MSAL.** Register a free application in Microsoft
  Entra, sign in with OAuth, and read/write the files over REST. More setup and
  more code, but it is the documented, supported path.

**Recommendation: timebox a spike on (a) first**, since it may solve the whole
problem in an afternoon. Fall back to (b) if the provider proves unreliable.
Build one or the other — not both.

### 9.5 Housekeeping

- **Compaction.** The `.jsonl` files grow forever. When a device's own file
  exceeds roughly 5MB, it may rewrite *its own file only*, keeping just the
  newest record per row id, and increment a `generation` value in a header line.
  Other devices seeing a new generation reset their cursor for that device to 0
  and re-read. **Never compact another device's file.**
- **Snapshots.** Periodically write a full JSON dump to `snapshots/`. This is
  the backup that makes everything else recoverable, and it reuses the exporter
  from §8.6.
- **Keep manual JSON export/import (§8.6) working regardless.** It is the escape
  hatch when sync misbehaves, and it is the only way to move data between
  devices before Phase 6 exists.
- Sync failures must be **silent and non-blocking**. If OneDrive is unreachable
  the app carries on against its local database; never block starting a timer on
  a network operation.

---

## 10. Build & run

```bash
# Desktop, during development
./gradlew :composeApp:run

# Desktop installer (.msi on Windows) — or scripts\install-desktop.bat
./gradlew :composeApp:packageMsi

# Android
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk

# Tests — the ones that matter
./gradlew :core:allTests
```

Database file locations:

- Desktop: a real user data directory (`%LOCALAPPDATA%\RevisionTracker\`), **not
  the working directory and not next to the .exe** — otherwise the data is lost
  on reinstall.
- Android: standard app database directory.

---

## 11. Roadmap

Each phase should end somewhere the user can actually try it. Do not build all
six phases before showing anything.

| Phase | Goal | Done when |
|---|---|---|
| **0** | Toolchain | `./gradlew :composeApp:run` opens an empty Compose window on Windows. JDK issue from §3 resolved. Git initialised with a sensible `.gitignore` (`local.properties`, `build/`, `.gradle/`, `*.db`). |
| **1** | Data layer | Schema from §5 in SQLDelight. Repositories exposing `Flow`. Seed data from §6 loads on first run. Tests pass. No UI yet. |
| **2** | Timer | §8.2 fully working, including multi-topic, mid-session switching, pause, crash recovery. §8.4 history list so the user can see sessions landing. **This is the first genuinely usable build — get it in front of the user.** |
| **3** | Scheduling | §7 implemented and tested, §8.1 Today screen. The app now answers "what next?". |
| **4** | Management | §8.3 subjects/topics editing, §8.5 stats, §8.6 settings + JSON export. User trims History to 4 chapters and fixes the English Lit guesses. |
| **5** | Android | Android entry point, layouts checked at phone width, installed over USB. Data moves via JSON export. |
| **6** | Sync | §9, over OneDrive. Start with the Android access spike (§9.4a) — it is the only genuinely uncertain part, so find out early whether it works. Only attempt this once phases 0–5 are genuinely being used day to day. |

---

## 12. Open questions

### Already answered — do not re-ask (confirmed 2026-09-17)

| Question | Answer |
|---|---|
| Which subjects? | The nine in §6.4, confirmed complete |
| Which 4 History chapters? | Germany 1890–1945; Conflict & tension 1894–1918; Health and the people; Elizabethan England (§6.1) |
| Maths tier | **Higher** |
| Maths / French / English Lit board | **AQA** |
| English Literature poetry | **AQA Power and Conflict** anthology, seeded per-poem (§6.2) |
| English Lit: one subject or three? | **One subject**, four children (§6.2) |
| Geography optional chapters | Cold environments; Coastal + River; Energy management (§6.1) |
| Daily/weekly time target? | **No target** — Today screen just reports time done (§8.1) |
| Sync transport | **OneDrive** (§9) |

### Still open — ask before assuming

1. **Exam dates.** Unknown at time of writing and the user said exam-countdown
   weighting is *"only worth including once you have your actual exam
   timetable"*. The schema and the neutral 0.3 fallback in §7.2 already handle
   their absence, so this blocks nothing — just ask once the timetable exists.
2. **Computer Science board.** The source document says "OCR-style spec" but is
   not certain. Section and topic titles are reliable regardless and page
   numbers are being left NULL, so this is low-risk — confirm when convenient.
3. **Android OneDrive access route** (§9.4) — needs a technical spike rather
   than a user decision, but report the outcome before committing to it.

---

## 13. Notes for agents working on this

- **The user asked for this plan to be written rather than executed.** They are
  likely to want to review before large amounts of code appear. Check in at
  phase boundaries.
- The user has **some Python** and no Kotlin. Explain Kotlin/Gradle concepts
  briefly when they first appear; do not write tutorials.
- **Do not over-build.** This is a single-user app for one student. No DI
  framework, no CRDTs, no server, no analytics, no accounts. Sync is files in a
  OneDrive folder (§9), nothing more.
- **The most destructive mistake available in this project** is putting the
  SQLite database inside the OneDrive folder. It looks like it works, then
  corrupts the database or silently drops half the history. Read §9.1.
- **Do not switch to a web stack** for convenience. The user rejected it
  explicitly and by name.
- `gcse_revision_guides_contents.md` is the source of truth for topic data and
  carries its own per-subject confidence notes — **respect them**, particularly
  the Computer Science and later-Geography page numbers, which the document
  itself says are unreliable.
- Time handling is the most likely source of real bugs: machine sleep, timezone,
  midnight boundaries for "today's total", and sessions crossing midnight. Store
  epoch millis everywhere, convert to local time only for display.
