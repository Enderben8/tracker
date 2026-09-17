# Revision Tracker

**Read `PROJECT_SPEC.md` before doing anything.** It is the complete brief:
stack, schema, scheduling algorithm, seed data, screens, sync design, roadmap.

Quick orientation:

- A GCSE revision-logging app. Log sessions with a live timer, track time per
  topic, get told what to revise next. Windows desktop first, Android later,
  synced via OneDrive.
- **Kotlin Multiplatform + Compose Multiplatform.** Native, not web — the user
  rejected a web app explicitly. Do not switch stacks.
- **No code exists yet.** Phase 0 in the spec is proving the toolchain works.
- The user has some Python and no Kotlin. Explain JVM/Gradle concepts briefly
  as they come up.
- The user prefers plans written down and reviewed over large amounts of code
  appearing unannounced. Check in at phase boundaries.

Two things that will bite:

1. System Java is 25 and the Android Gradle Plugin will likely reject it. Point
   Gradle at Android Studio's bundled JDK — see §3.
2. **Never put the SQLite database inside the OneDrive folder.** It appears to
   work, then corrupts the database or silently drops history. See §9.1.

`gcse_revision_guides_contents.md` is the source data for the topic tree. It has
per-subject confidence notes — respect them.
