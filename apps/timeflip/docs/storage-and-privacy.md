# Storage and privacy (TF-D3)

Status: the data layer is written and type-checked against API stubs. It has not been compiled
against the Android SDK or run under Robolectric, because the SDK and Google Maven are unreachable
from the build sandbox. CI on GitHub Actions is the first real build. Any behaviour described here
that depends on an Android API is a claim about the intended design until CI passes.

## Copy, not URI

Photos are copied at import into app-private storage. The picker URI is read once, during
`PrivatePhotoStore.importPhoto`, and is never stored in the database or in preferences.

Why:

- A picker grant can be revoked, and the original can be deleted or moved at any time. If a project
  kept the URI, a saved project could silently stop opening.
- A private copy keeps the library usable offline and independent of other apps' storage.
- The copy is the one canonical image. Its oriented size is what the transition and alignment math use,
  so the preview and the exported video agree.

Cost: each imported photo takes extra storage. Section "Storage cost" gives the numbers.

## Access lifetime

- Picker grants are used only inside `importPhoto`. Nothing is kept after it returns.
- The copy is written to a `import-<uuid>.tmp` staging file, normalized, and the staging file is
  deleted in a `finally` block, whether or not the import succeeded.
- The app requests no INTERNET, storage or camera permission (see `AndroidManifest.xml`).
- Exports leave the app only through a FileProvider content URI. That URI is granted per share,
  and the grant ends with the share.

## Storage layout

| Location | Contents | Created by |
|---|---|---|
| `filesDir/timeflip/photos/<photoId>.jpg` | Oriented, downscaled JPEG copies | `PrivatePhotoStore` |
| `filesDir/timeflip/exports/<fileName>` | Finished MP4 files | Export engine (TF-D4), recorded by `RoomProjectRepository` |
| `cacheDir/thumbs/<photoId>-<maxEdge>.jpg` | Thumbnails, regenerated on demand | `PrivatePhotoStore` |
| `databases/timeflip.db` | Room database, schema version 1 | `TimeFlipDatabase.create` |
| `files/datastore/timeflip_prefs.preferences_pb` | Onboarding flag and last export preset name | `UserPreferences` |

`photoId` is a random UUID for imports and duplicates. Recovery accepts any id made of letters, digits,
`_` and `-`, which keeps arbitrary stored strings out of file paths.

## Photo copy format and storage cost

Each copy is produced by `ImageNormalizer`:

- Accepted input: JPEG, PNG, WebP, and HEIF/HEIC only when the platform decodes it. Other types are
  rejected with `UNSUPPORTED_FORMAT`. Sources above 100 megapixels or 64 MiB are also rejected.
- EXIF orientation is applied to the pixels. The output contains no EXIF metadata.
- The longest edge is at most 4096 px. Smaller images are never upscaled.
- Encoded as JPEG, quality 95. PNG and WebP transparency is flattened onto white.

Storage estimate (derived from the encoder settings, not measured):

- JPEG at quality 95 typically lands around 1.5 to 3 bits per pixel for natural photographs. This
  is an assumption about encoder behaviour, not a measurement.
- At 4096 x 3072 (12.6 MP), that is roughly 2.4 to 4.7 MB per copy.
- At the 4096 x 4096 maximum (16.8 MP), roughly 3.1 to 6.3 MB per copy.
- A project holds two copies, so roughly 5 to 12 MB of photo data, plus a thumbnail cache.
- An export at the default timing (2.8 s) and 1080 x 1920 at 30 fps has a bitrate of about 6.2 Mbps
  (`ExportProfile.bitrateBps`), which gives roughly 2.2 MB per MP4. This is also derived, not measured.

Not measured: real file sizes for real photographs, encode time, decode time, and peak heap use on
any device. Those need a device run before any storage budget is committed.

Memory during import (estimate): the decode is capped so that the longest decoded edge is at most
6144 px, which is about 113 MB of ARGB at 4:3 and about 151 MB for an extreme square source. The
output bitmap adds up to about 50 MB at 4096 x 3072. Peak transient use is therefore roughly 165 MB
in the worst realistic case. A failed decode (including out-of-memory during decode) becomes
`DECODE_FAILED`.

## Deletion behaviour

- `deleteProject(id)`: in one transaction, the project row is deleted and SQLite cascades to its
  photo and export rows. Only after the transaction commits are the project's photo copies,
  their thumbnails, and its export files deleted. If the transaction fails, no file is touched.
  Missing files are ignored.
- Replacing or swapping a photo: `saveProject` replaces the photo rows for the project in the same
  transaction. The old copy is not deleted at save time, so an undo that refers to it still works
  during the session. The next `StorageRecovery.recover()` removes the unreferenced copy.
- `duplicateProject`: copies rows and photo files under new ids. Exports are not copied.
- `deleteAllData()`: deletes every export, photo and project row in one transaction. Then it deletes
  the whole `rootDir` tree and the thumbnail directory. Then it runs `VACUUM` and a WAL checkpoint,
  so deleted rows are not left in free pages.
- Preferences: `UserPreferences.clearAll()` removes every key, including onboarding state.
- Thumbnails are removed when their photo is deleted and by `deleteAllData()`.

Known gap: single-project deletion does not run `VACUUM`. The deleted project's names and metadata
may remain in free pages of `timeflip.db` until the next `VACUUM`. That happens during "Delete all
data". Automatic vacuum is not enabled.

## Recovery guarantees

`StorageRecovery.recover()` runs at app start. `AppContainer.startupRecovery` launches it on a background
dispatcher, so it can overlap a new import or export. It is idempotent.

What it does:

- Removes unfinished files (`*.tmp` and `*.part`) in `photos/`, `exports/` and `cacheDir/thumbs/`
  that are older than 10 minutes. Those are interrupted imports, exports, and thumbnail writes.
- Removes photo copies and export files that no row references, once they are older than 10 minutes.
- Removes thumbnails older than 7 days, and thumbnails whose source copy no longer exists.
- Reports, but does not delete, photo rows whose copy is missing. The UI should show SOURCE_MISSING for
  those photos. The project row is kept.
- Leaves files it does not recognize alone.

What it guarantees:

- A saved project never loses its row because of recovery.
- A project saved before a crash keeps its photos if the copies were fully written. Writes go to a
  temp file, are synced, then renamed. A reader sees either the old file or the complete new file.
- An import or export interrupted by process death leaves at most a `*.tmp` file, which recovery removes.

What it does not guarantee:

- A crash between a successful `importPhoto` and `saveProject` leaves an unreferenced copy. Recovery
  removes it only once the copy is more than 10 minutes old, so it can be removed at a start that
  happens more than 10 minutes after the crash, not at the start immediately after it.
- It does not repair a photo copy that was corrupted on disk. Such a copy is decoded again on use,
  and a decode failure is reported as DECODE_FAILED.
- Recovery is not ordered before imports and exports. The app starts it in the background, so it can
  overlap an operation that has just started. The grace period protects files written in the last
  10 minutes, including an import in progress. That is a heuristic, not a lock. An operation that
  pauses for longer than 10 minutes between writing a file and recording its row can lose that file
  if a recovery run happens to start during the pause.

## What is never copied off the device

- There is no backend and no INTERNET permission. The data layer has no network code.
- `allowBackup` is false in the manifest, so the library is not included in cloud or device backup.
- Nothing is logged. The data layer has no logging calls. Error categories carry short debug text that
  contains no file names, URIs or image content. Exception causes can contain paths in stack traces,
  and that is why no stack trace should be sent off the device.
- No analytics events are emitted from this layer.

## Known limitations

- The Android build, Room/KSP code generation and Robolectric tests have not been run. See the status
  note at the top.
- Colour profiles (ICC) are dropped during re-encoding. Wide-gamut photographs can shift in colour.
- Sources above 100 megapixels or 64 MiB are rejected. Such photos show as UNSUPPORTED_FORMAT.
- A source whose longest edge is between 6145 and about 8190 px is decoded at half size, then upscaled
  to the 4096 px copy by 1.0x to 1.33x. This is a deliberate trade-off against decode memory. The
  exported video is at most 1920 px wide, and the copy stays above 3072 px in this band, so the
  effect on the exported video is not visible.
- HEIF/HEIC support depends on the device's BitmapFactory. Unsupported devices get UNSUPPORTED_FORMAT.
- Single-project deletion leaves free pages until `VACUUM` (see Deletion behaviour).
- Thumbnails are regenerated from copies, so a thumbnail request after the copy is removed returns null.

## Contracts required from other seats

- TF-D4 (export engine): the partial MP4 is named `<base>.part` in `exports/`. Recovery removes
  `.part` files that are older than the grace period. A partial that never gets a row is also removed as
  an unreferenced file. Exports must keep being written inside `filesDir/timeflip/exports`
  (`StorageLayout.exportsDir(rootDir)`).
- TF-D5 (FileProvider): `res/xml/file_paths.xml` now maps `files-path timeflip/exports/`, which matches
  the export location. No change needed here.
- TF-D5 (AppContainer): the constructors are called with `rootDir = filesDir/timeflip` and
  `cacheDir = context.cacheDir`. `cacheDir` is passed to `RoomProjectRepository`, so thumbnails are
  cleaned up. Recovery runs at startup in the background, which the grace period supports.
- The Room schema JSON is written by KSP to `apps/timeflip/schemas`. It must be committed with the
  first migration-ready build.
