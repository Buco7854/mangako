# Privacy Policy — Mangako

**Last updated: 2026-04-30**

Mangako is a self-hosted upload pipeline for manga. It runs entirely on
your Android device and talks to a [LANraragi](https://github.com/Difegue/LANraragi)
server you operate. This policy explains what data the app collects,
where it goes, and how long it stays.

## TL;DR

- **No analytics. No advertising. No third-party servers.**
- Data only leaves your device by being uploaded to **your own LANraragi
  server** at the URL you configure.
- Everything else stays in the app's private storage on your phone.

## What the app accesses on your device

| What                                | Why                                                                                          | When you control it                                                                                                                                                |
| ----------------------------------- | -------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Folders you pick (Storage Access)   | Detect new `.cbz` files; read them to compute titles + thumbnails; upload them.              | Granted via the Android "Open Folder" picker. Revocable from Android Settings → Apps → Mangako → Permissions, or from Mangako's own Settings → Watched folders.    |
| ComicInfo.xml inside each `.cbz`    | Drives the pipeline — title, series, writer, language, etc.                                  | Only read for files in folders you explicitly added.                                                                                                               |
| First-page thumbnail of each `.cbz` | Inbox card preview + History entry preview.                                                  | Cached in the app's private files / cache directory; deleted with the row or when the OS reclaims the cache.                                                       |
| Notification permission             | "New file detected" notifications when the watcher discovers a `.cbz`.                       | Granted on first launch. Revocable from Android Settings.                                                                                                          |
| `MANAGE_EXTERNAL_STORAGE` (optional) | Real-time file watching via `inotify` for instant detection of new `.cbz` files.             | Optional. Off by default. Granted explicitly from Settings → Real-time watching. Without it the app falls back to a 15-minute periodic scan + content-uri trigger. |
| Network access                      | Upload archives to the LANraragi URL you configured.                                         | Only contacts the URL you typed in Settings → Server.                                                                                                              |
| `RECEIVE_BOOT_COMPLETED`            | Re-arm the periodic background scan after a device reboot, so files added overnight are picked up next time.| You can revoke notification permission to silence the watcher; the scan itself can also be turned off from Settings.                                               |

## What the app stores on your device

All of the below is in the app's private internal storage
(`/data/data/com.mangako.app/`), which other apps cannot read.

- **Pending queue** — for each detected `.cbz`: its content URI, file size,
  detection timestamp, the ComicInfo fields you (or detection) provided,
  and a thumbnail.
- **History** — a rolling log of the last **30** uploads, each with the
  audit trail of pipeline rules that ran, the final filename, the upload
  status, and a thumbnail. Older entries are deleted automatically.
- **Pipeline configuration** — the rules you authored, in JSON form.
- **Settings** — the LANraragi URL + API key, watched folder URIs,
  preferences (debounce window, delete-on-success, etc.).

The LANraragi API key is held in standard app storage. It is treated as a
credential — the app never logs it and never sends it anywhere except to
the configured LANraragi URL.

## What the app sends off-device

**Only one destination**: the LANraragi server URL you configure.

The app sends:

- The renamed `.cbz` archive itself (via the LANraragi upload endpoint).
- The API key you configured (as the standard `Authorization` header
  LANraragi expects).

The app does **not** send:

- Telemetry, crash reports, or analytics.
- Files from any folder you did not explicitly add.
- Anything to Google, Anthropic, or any other third party.

## Children's privacy

Mangako is not directed at children. It does not knowingly collect
information from anyone.

## Open source

The full source is at <https://github.com/Buco7854/mangako>. You can
audit every line of network and storage code for yourself.

## Changes to this policy

If this policy materially changes, the change will appear in this file
in the repository, and the "Last updated" date at the top will move.
You can subscribe to releases on GitHub to be notified.

## Contact

Open an issue at <https://github.com/Buco7854/mangako/issues>.
