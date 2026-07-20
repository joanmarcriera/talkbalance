# Release & distribution runbook

Talk Balance is distributed **open-source (GPL-3.0)** via a **self-hosted F-Droid
repository** — no Google Play, no developer fee. Users get auto-updates by adding
the repo; everyone else can sideload the APK.

- **Repo / landing page:** https://fdroid.joanmarcriera.es/
- **F-Droid repo URL:** `https://fdroid.joanmarcriera.es/repo`
- **Repo signing fingerprint (SHA-256):**
  `016B7A7974FA44A3B1FC5960034AA7ED62298932E4CB0D8ABA31A859AC7D3E20`
- **App signing cert (SHA-256):**
  `3331dc566703de7b8edb170cad122a498a67d5c20db89c15a9936380c73aa7ef`
- Served by nginx as service `fdroid` in `/opt/stacks/core/sites.yml` (project
  `sites`) on the Hetzner box; files at `/opt/stacks/core/fdroid/`.
  DNS: `fdroid` A → 78.46.160.189 (OVH, record 5425147557).

## ⚠️ Critical keys — back these up offline

Losing either key is unrecoverable: lose the **app** key and you can never ship an
update users can install over the existing app; lose the **repo** key and every
user must re-add the repo.

| Key | File | Password |
|---|---|---|
| App signing | `~/.android-keystores/talkbalance-release.jks` (alias `talkbalance`) | `~/.tokens` → `TALKBALANCE_KEYSTORE_PASSWORD` |
| F-Droid repo index | `~/Development/_inbox/talkbalance-fdroid/keystore.p12` | in that workspace's `config.yml` (`keystorepass`) |

Both live only on the Mac. **Copy them offline / to a password manager.** See the
`BLOCKER:` task in Vikunja project 51.

## Build tooling (this machine)

- `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
- `fdroid` = pipx (`~/.local/bin/fdroid`, fdroidserver 2.4.5); its venv needs
  `setuptools<81` injected (Python 3.14 dropped `pkg_resources`).
- Release signing is read from env (sourced from `~/.tokens`); if the vars are
  absent the release build is left unsigned rather than failing (see
  `app/build.gradle.kts`).

## Cut a new release

```bash
cd ~/Documents/Claude/Projects/TalkerTracker
# 1. bump versionCode (+1) and versionName in app/build.gradle.kts, commit.
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
set -a; source ~/.tokens; set +a
./gradlew test assembleRelease --no-daemon            # signed APK

# 2. publish into the F-Droid repo
WS=~/Development/_inbox/talkbalance-fdroid
cp app/build/outputs/apk/release/app-release.apk "$WS/repo/talkbalance-<versionName>.apk"
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$HOME/.local/bin:$JAVA_HOME/bin:$PATH"
( cd "$WS" && fdroid update --pretty )                # regenerates + signs index

# 3. push to the server (rsync as marc; sudo not needed — dir is marc-owned)
rsync -az --delete "$WS/repo/" marc@joanmarcriera.es:/opt/stacks/core/fdroid/repo/
# landing page only if changed:
rsync -az "$WS/site/index.html" "$WS/site/repo-qr.png" marc@joanmarcriera.es:/opt/stacks/core/fdroid/

# 4. verify
curl -sI https://fdroid.joanmarcriera.es/repo/index-v1.jar | head -1
```

F-Droid clients poll the repo and offer the update automatically. `archive_older: 0`
keeps only the current version in the repo.

## Going public (deferred to v1)

The source is not yet on public GitHub. Before flipping public: scrub internal-infra
references (`git.lab.riera.co.uk`, `~/.tokens`, server IPs) from `PROJECT_STATE.md`,
`DECISIONS.md`, `TASKS.md`; confirm no secrets in history (keystores are gitignored);
then create the public GitHub repo and add `SourceCode`/`IssueTracker` to the
F-Droid metadata (`~/Development/_inbox/talkbalance-fdroid/metadata/uk.co.riera.talkbalance.yml`).
Optionally submit to f-droid.org main later (requires build-from-source recipe for
the vendored sherpa AAR).

## Monetisation

Free app. Donations via the existing Lemon Squeezy "buy me a coffee" (add a footer
link to the landing page / an in-app "Support" row) — NOT Play Billing (not on Play).
Consumer willingness-to-pay tested ~nil in research, so treat as tips + portfolio.
