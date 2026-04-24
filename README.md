# Mangako

A modular, transparent Android bridge that watches folders for `.cbz` files, runs them through a fully user-defined rename pipeline, and uploads the result to a self-hosted [LANraragi](https://github.com/Difegue/LANraragi) server.

Built for people who download manga on their phone (Mihon, Tachiyomi, etc.) and want LANraragi-grade filenames without running a server-side script.

---

## How it works

```
┌──────────────┐      ┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│ Watched SAF  │  →   │  Inbox       │  →   │  Pipeline    │  →   │  LANraragi   │
│ folder(s)    │      │  (approval)  │      │  (rules)     │      │  /upload     │
└──────────────┘      └──────────────┘      └──────────────┘      └──────────────┘
        ▲                     │                     │                     │
        └─────────────────────┴────────── audit trail ───────────────────┘
                                  (History tab)
```

1. `DirectoryScanWorker` polls every 15 minutes across every subscribed folder, recursing up to 6 levels.
2. New `.cbz` files either land in the **Inbox** (approval mode) or go straight to the pipeline (auto mode).
3. `ProcessCbzWorker` debounces until the file size is stable, extracts `ComicInfo.xml`, runs the rule pipeline, and streams the result to LANraragi.
4. Every step is captured in an audit trail shown per-file in the History tab.

---

## Features

- **Modular rule engine** — eight rule types, composable and reorderable. The entire pipeline serializes to JSON, so you can share configs with a friend by text.
- **Per-file approval** — opt-in notification with Process/Ignore actions, plus an in-app Inbox with bulk controls. Fully automatic mode is also available.
- **Multi-folder watch** — subscribe to as many SAF directories as you want (Mihon, Tachiyomi, downloads, etc.).
- **LANraragi Standard preset** — ships a complete port of the reference `lrr-preprocess.sh` + `mihon.sh` behavior as a one-tap "Load Defaults" button.
- **Transparent audit log** — every rule invocation records before/after, nesting depth, duration, and skip reason. No black boxes.
- **ReDoS-guarded** — user-provided regex runs under a 1-second wall-clock budget; a catastrophic pattern becomes a skipped step, not a hung worker.
- **Encrypted API key** — stored as AES-GCM ciphertext keyed by Android Keystore, never leaves the secure element in plaintext.
- **Streamed uploads** — multipart body reads directly from disk; a 500MB chapter doesn't load into heap.

---

## The LANraragi Standard preset

One-tap button on the Pipeline screen. Ported verbatim from the user's bash scripts — every step is editable once loaded.

| # | Rule | What it does |
|---|------|--------------|
| 1 | Extract XML | Read `ComicInfo.xml` → `%title%`, `%series%`, `%writer%`, `%number%`, `%genre%`, `%language%`, `%summary%` |
| 2 | Extract regex | Parse `Languages: <lang>` out of `<Summary>` when `<LanguageISO>` is missing; default to English |
| 3 | Conditional | If `%title%` matches `Chapter N` / `Ch.N` / etc., rewrite the filename to `%series%.cbz` |
| 4 | Conditional | If filename doesn't start with `[...]`, prepend `[%writer%]` |
| 5 | Regex replace | Strip `\ / : * ? " < > \|` |
| 6 | 12× regex replace | All 15 flag emoji → `[Language]` (English, Japanese, Chinese, Korean, French, Spanish, German, Italian, Portuguese, Russian, Thai, Vietnamese) |
| 7 | Tag relocator | Move `(COMIC…)` / `(C96)` / `(Comiket…)` / `(Reitaisai…)` / etc. to the front |
| 8 | Regex replace | Fix misplaced leading `[Language]` — push to the position after title |
| 9 | Conditional | If no `[Language]` tag at all, append the resolved one |
| 10 | Conditional | If `%genre%` contains "Manhwa": insert `Ch %number%` before the language tag, append `[Manhwa]` |
| 11 | Clean whitespace | Collapse runs + trim |

Known non-goals (vs. the bash scripts): in-place `ComicInfo.xml` rewriting and cross-folder Series grouping. Mangako processes one file at a time and does not repack archives.

---

## Rule types

| Kind | Purpose |
|------|---------|
| `ExtractXmlMetadata` | Load fields from `ComicInfo.xml` into pipeline variables |
| `ExtractRegex` | Capture a substring from one variable into another (e.g. Language from Summary) |
| `RegexReplace` | Standard regex replace on the filename; supports `%var%` and `$1` back-refs |
| `StringAppend` / `StringPrepend` | Add literal text at either end |
| `TagRelocator` | Find a regex match, move it to front or back of the filename |
| `ConditionalFormat` | Branch into `thenRules` / `elseRules` based on a variable condition |
| `CleanWhitespace` | Collapse repeated whitespace, optional trim |

Pipelines export and import as a single JSON envelope with a schema version. Share it as a file or paste it into the Import dialog.

---

## Build & install

Requires JDK 17, Android SDK 34, a device or emulator running Android 8.0+ (API 26).

```bash
git clone https://github.com/Buco7854/mangako.git
cd mangako
gradle wrapper --gradle-version 8.9   # first time only
./gradlew assembleDebug
./gradlew installDebug                 # with a device attached
```

Release builds require a keystore (see CI section below). Without one, `assembleRelease` falls back to the debug key and prints a loud warning — that APK is for local install only.

---

## Configuration

Open the **Settings** tab in the app:

1. **LANraragi Server** — base URL (e.g. `https://lrr.example.com`) and API key. The key is encrypted at rest. A red warning appears for plain-http URLs on non-LAN hosts.
2. **Watched folders** — tap *Add folder* and pick one or more SAF trees (Mihon downloads, Tachiyomi, etc.). Permissions persist across reboots.
3. **Watcher** — enable background scanning (fires every 15 min), choose *Ask first* (Inbox + optional notification) or *Auto-process*, set debounce window, and toggle delete-on-success.

The first launch asks for `POST_NOTIFICATIONS` (Android 13+) so the approval flow can surface actions from the lock screen.

---

## Tech stack

- **Language:** Kotlin 2.0.21, Jetpack Compose (Material 3)
- **DI:** Hilt
- **Persistence:** Room (history + pending queue), DataStore (settings + pipeline JSON), Android Keystore (API key)
- **Background:** WorkManager (`DirectoryScanWorker`, `ProcessCbzWorker`, `MaintenanceWorker`)
- **HTTP:** Ktor client on OkHttp engine, streamed multipart upload
- **Serialization:** `kotlinx.serialization` with sealed-class polymorphism

Package layout under `com.mangako.app`:

```
domain/    rule engine, pipeline executor, cbz processor
data/      repositories + DataStore + Room + Keystore wrapper
work/      WorkManager workers + notification plumbing
ui/        Compose screens (pipeline, inbox, history, settings)
di/        Hilt modules
```

---

## CI / release signing

[`.github/workflows/build.yml`](.github/workflows/build.yml) runs on every push to `main` and on PRs. It:

1. Generates the Gradle wrapper on the fly (no wrapper jar committed).
2. Runs `lintDebug` + `testDebugUnitTest`, uploads test reports as artifacts.
3. Builds both `assembleDebug` and `assembleRelease`, uploads both APKs.

To get signed release APKs from CI, set these repo secrets:

| Secret | Purpose |
|--------|---------|
| `MANGAKO_KEYSTORE` | Path to the JKS/PKCS#12 file on the runner (checked in under `app/keystore.jks` works) |
| `MANGAKO_KEYSTORE_PASSWORD` | Store password |
| `MANGAKO_KEY_ALIAS` | Key alias |
| `MANGAKO_KEY_PASSWORD` | Key password |

Without them, `assembleRelease` uses the debug key and Gradle prints a warning — useful for local development, not for distribution.

---

## Testing

```bash
./gradlew testDebugUnitTest
```

Covers every rule type, regex-back-ref escaping, ReDoS cutoff, JSON round-trip on the full default template, and schema-version rejection.

---

## Contributing

Issues and PRs welcome. Keep these in mind:

- New rule type? Add a `@SerialName` subclass of `Rule`, override `withMeta`, add a branch in `PipelineExecutor.step()`, a case in `PipelineViewModel.newRule()` + `RuleKind`, and an editor in `RuleEditorSheet`. A unit test in `PipelineExecutorTest` is expected.
- Don't add string literals to Composables — use `stringResource(R.string.…)` and register the key in `strings.xml`.
- The `LanraragiException`-vs-`IOException` split drives worker retry behavior; preserve it when touching `ProcessCbzWorker`.
