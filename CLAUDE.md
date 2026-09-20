# Revision Tracker

**Read `docs/PROJECT_SPEC.md` before doing anything.** It is the complete brief:
stack, schema, scheduling algorithm, seed data, screens, sync design, roadmap.
`docs/NOTES.md` records what was decided while building and the rough edges.

Quick orientation:

- A GCSE revision-logging app. Log sessions with a live timer, track time per
  topic, get told what to revise next. Windows desktop and Android, synced
  through a shared cloud folder (Google Drive in practice).
- **Kotlin Multiplatform + Compose Multiplatform.** Native, not web — the user
  rejected a web app explicitly. Do not switch stacks.
- Modules: `core` (data, scheduler, sync; no UI, well tested), `composeApp` (all
  screens, shared), `androidApp` (Android entry point only).
- The user has some Python and no Kotlin. Explain JVM/Gradle concepts briefly
  as they come up.
- The user prefers plans written down and reviewed over large amounts of code
  appearing unannounced. Check in at phase boundaries.
- Phases 0–6 of the spec are built. The repo is public at
  github.com/Enderben8/tracker; keep personal details out of it.

Three things that will bite:

1. **Never put the SQLite database inside the synced folder.** It appears to
   work, then corrupts the database or silently drops history. See spec §9.1.
2. **Do not launch, click or kill app windows on the user's desktop** — an
   earlier test cleanup interrupted a real revision session. Use
   `composeApp/src/desktopTest/.../RenderTest.kt`, which renders every screen
   off-screen into `composeApp/build/screenshots/`.
3. `app.version` in `gradle.properties` is the only place the version lives; the
   release workflow refuses a tag that disagrees with it.

`docs/gcse_revision_guides_contents.md` is the source data for the topic tree.
It has per-subject confidence notes — respect them.
