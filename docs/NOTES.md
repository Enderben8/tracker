# Implementation notes

Decisions taken while building that `PROJECT_SPEC.md` does not dictate, plus the
known rough edges. The spec is the brief; this is what actually happened.

## Decisions not in the spec

- **"Archive" is a soft delete** (`deleted = 1`). The schema has no `archived`
  column. Archiving a topic archives its descendants. History queries do not
  filter on deleted topics, so old sessions still display properly.
- **Seed rows have stable ids** (`seed:<subject>:<title-slug-path>`) rather than
  random UUIDs. Two devices that seed independently then produce identical rows,
  so sync does not duplicate ~400 topics. Rows you create get random UUIDs.
- **A subject whose exam has passed leaves the queue entirely.** The spec only
  said its topics must not top the queue.
- **415 topics**, not the spec's estimated ~150 (Geography's chapters, 17 poems,
  the generated Maths and French lists).
- **Computer Science**: "Exam Questions" / "Revision Questions" rows omitted —
  practice material would clutter the queue. Page numbers left null throughout,
  as the source document flags them unreliable.
- **Sync transport is Google Drive, not OneDrive** (§9 of the spec assumed
  OneDrive). The design needs only a folder both devices can read and write, so
  nothing else changed. Either works.

## Sync, in practice

The engine is spec §9: each device appends to its own `devices/<id>.jsonl` and
only ever reads the others, so no file has two writers and the cloud provider
never has a conflict to resolve.

Things learned from running it on a real phone and PC:

- **Android's Drive provider lies about writes.** Overwriting a file read back
  stale or empty content, and a deleted file still appeared in listings. So the
  engine **never reads its own file back**: a local `sync_log` table is the
  source of truth and the shared file is only a published copy, re-published
  until it lands. `SafSyncFolder` remembers exact document Uris, never
  re-creates a name it knows, and prefers the newest if duplicates appear
  (Drive allows two files with the same name).
- **Settings → Sync → "Test folder"** runs create / list / read-back /
  overwrite-longer / overwrite-shorter / delete / no-duplicates against the real
  folder, with timings, waiting up to 20s per step. Run it before trusting a new
  location. The shorter-overwrite step is the one that catches providers that
  leave stale bytes behind.
- Drive usage is kept small deliberately: logs compact at 1 MB, snapshots are
  compact JSON, weekly, 3 kept per device, and a failed snapshot never fails a
  sync. Typical total: under 1 MB.
- The desktop app syncs on launch, every 2 minutes, 30 seconds after a change,
  and once more while closing. Android syncs when it starts, stops and resumes.
- Device-local settings (`device_id`, `seeded_at`, `heartbeat`, `sync.*`) never
  sync and never export.

## Known rough edges

- Deleting a past session does not undo the spaced-repetition update it caused.
- Topics can be nested only by adding under a parent; there is no drag to
  re-parent.
- Importing a backup merges and never removes; it cannot undo an add.
- Session-level notes cannot be edited after saving (per-topic durations can).
- The desktop file dialogs (export/import) are only exercised by hand; the logic
  behind them is tested.

## Testing

```bash
./gradlew :core:desktopTest :composeApp:desktopTest
```

`core` holds the tests that matter: scheduler, SM-2, stats, backup merge, and a
two-device sync simulation against a fake shared folder. `RenderTest` renders
each screen off-screen into `composeApp/build/screenshots/` at desktop and phone
sizes — use it to check layouts instead of launching the app.

## Releasing

`app.version` in `gradle.properties` is the single source of the version number
for both platforms. To release:

```bash
git tag v1.2.3 && git push origin v1.2.3
```

The `release` workflow builds the Windows installer and the APK and attaches
them to a GitHub release. It refuses to run if the tag and `app.version`
disagree.

### Signing the Android app properly

Without a signing key the workflow falls back to the debug key, which means each
build is signed differently and cannot be installed over the last one. To fix
that, create a key once:

```bash
keytool -genkeypair -v -keystore revision-release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias revision
```

Keep the `.jks` out of the repo (it is gitignored), then add four repository
secrets under *Settings → Secrets and variables → Actions*:
`ANDROID_KEYSTORE_BASE64` (the file, base64-encoded), `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`. The workflow picks them up
automatically. **Losing the key means future builds can no longer upgrade
installed ones.**
