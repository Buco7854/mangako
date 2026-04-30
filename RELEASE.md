# Releasing Mangako

End-to-end guide for shipping a release, the first time and every time
after.

## One-time setup

### 1. Generate the release keystore

The keystore is the cryptographic identity of the app. Once you publish
under a given key, **you can never change it for that listing** —
losing the keystore or the password means you can never update the
app under the same package name on the same store. Treat it like a
private SSH key.

There's a helper script that walks you through it:

```bash
./scripts/generate-keystore.sh
```

It will:
1. Prompt for a keystore password (twice, for confirmation).
2. Prompt for a separate key password (Play recommends "same as
   keystore"; press enter to mirror the keystore password).
3. Write `mangako-release.jks` to the project root.
4. Print the env vars you need for local signed builds.

`*.jks` is gitignored — the file stays on your machine. **Back it up
somewhere safe** (encrypted cloud, password manager attachment, USB
drive — anywhere your `~/.ssh/id_ed25519` would go is fine).

### 2. Create local env config

For day-to-day signed builds, drop a single file into the project root
(also gitignored):

```bash
# keystore.properties
storeFile=/absolute/path/to/mangako-release.jks
storePassword=...
keyAlias=mangako
keyPassword=...
```

…then `source` an env loader before building, **or** export the four
variables Gradle reads:

```bash
export MANGAKO_KEYSTORE=/absolute/path/to/mangako-release.jks
export MANGAKO_KEYSTORE_PASSWORD=...
export MANGAKO_KEY_ALIAS=mangako
export MANGAKO_KEY_PASSWORD=...
```

If those are not set, the release build falls back to the **debug** key
and prints a loud warning. Debug-signed APKs work for sideload but
should never be published to a store.

### 3. (Optional) Configure CI release signing

The `.github/workflows/release.yml` workflow signs and publishes a
release whenever you push a `v*` tag. To enable it, add four
**repository secrets** in GitHub → Settings → Secrets and variables →
Actions:

| Secret name                  | Value                                                                |
| ---------------------------- | -------------------------------------------------------------------- |
| `MANGAKO_KEYSTORE_BASE64`    | `base64 -w0 mangako-release.jks` output (one long line, no newlines) |
| `MANGAKO_KEYSTORE_PASSWORD`  | The keystore password                                                |
| `MANGAKO_KEY_ALIAS`          | The key alias (e.g. `mangako`)                                       |
| `MANGAKO_KEY_PASSWORD`       | The key password                                                     |

`base64 -w0` on Linux, `base64 -i mangako-release.jks` on macOS — the
key is the file gets onto a single line so it round-trips through
GitHub's secret store cleanly.

### 4. Privacy policy hosting

Google Play requires a publicly-reachable privacy policy URL. The
canonical copy lives at [`PRIVACY.md`](./PRIVACY.md). Host it via:

- GitHub: link directly to the raw or rendered file
  (`https://github.com/Buco7854/mangako/blob/main/PRIVACY.md`).
- A static-page service (GitHub Pages, Cloudflare Pages, etc.).

Update Play Console → Policy → Privacy Policy with that URL.

## Per-release checklist

Run through this every time you cut a version.

### Before tagging

- [ ] Bump `versionCode` (must strictly increase) and `versionName`
      (semver) in `app/build.gradle.kts`.
- [ ] Update `PRIVACY.md`'s "Last updated" date if the data flow
      changed.
- [ ] Run `./gradlew lintRelease` locally and address any reported
      issues. Lint reports go to `app/build/reports/lint-results-*.html`.
- [ ] Run `./gradlew testDebugUnitTest` — all 58+ tests must pass.
- [ ] Smoke-test on a real device: detect → process → upload to your
      LANraragi instance.

### Tagging + publishing

```bash
git tag -a v0.2.0 -m "0.2.0 — your headline change"
git push origin v0.2.0
```

The `release.yml` workflow runs on tag push and:

1. Builds the signed APK and AAB.
2. Creates a GitHub Release at the tag.
3. Attaches both artifacts.

The release notes default to the auto-generated commit list; edit them
on GitHub afterwards if you want a curated changelog.

### Distribution channels

Pick whichever applies. You can do all three.

#### A. GitHub Releases (sideload + Obtainium)

Already handled by the release workflow. Users add the GitHub repo to
[Obtainium](https://obtainium.imranr.dev) for auto-update notifications.

#### B. Google Play

1. [Play Console](https://play.google.com/console) — $25 one-time fee.
2. Create the app, fill out the listing (description, screenshots, the
   privacy policy URL above, content rating, target audience).
3. Upload the `.aab` from the GitHub Release artifacts (or download
   from your CI run).
4. **About `MANAGE_EXTERNAL_STORAGE`**: Play treats this as a sensitive
   permission and reviews each request. Mangako uses it only for
   real-time `inotify` watching, which is optional. If review pushes
   back, you can either:
   - Provide the justification (the manifest comment + this section
     are a starting point), OR
   - Build a Play-specific variant without that permission. The app's
     fallback path (SAF + 15-minute periodic scan + content-uri
     triggers) keeps it functional without it.
5. Roll out to internal testing → closed → production as you gain
   confidence.

#### C. F-Droid / IzzyOnDroid

Lightweight, no fees, slower. Submit an inclusion request to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) referencing this
repo. For IzzyOnDroid, follow [the inclusion guide](https://apt.izzysoft.de/fdroid/index/info)
— they'll pull from your GitHub Releases.

## Recovery: lost keystore

If the keystore goes missing, your only option for the existing app
listings is:

- **Play Store**: request a key reset via Play Console support — only
  possible if you originally enabled "Play App Signing" (where Google
  holds the upload key). For a self-managed key, the listing is gone
  and you must publish a new app under a different package name.
- **GitHub / sideload / F-Droid**: generate a new keystore. Existing
  installs can't update directly — users must uninstall first, then
  install the new build. Bumping the package name (e.g.
  `com.mangako.app2`) avoids the conflict but breaks update history.

This is why the keystore matters more than the code.
