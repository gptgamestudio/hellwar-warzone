<p align="center">
  <img src="assets/site/hero-gunship.jpg" alt="Helicopter War — 3D Tactical Gunship Action Banner" width="100%" />
</p>

<h1 align="center">HELICOPTER WAR: EMPIRE ENDLESS BATTLE</h1>

<p align="center">
  <strong>High-octane 3D vertical tactical helicopter warfare for Android</strong><br/>
  4 Enemy Empires &bull; 200 Fortified Barracks &bull; Multi-Phase Boss Fights &bull; Cloud Firestore Sync
</p>

<p align="center">
  <a href="https://gptgamestudio.github.io/hellwar-warzone/"><strong>Official Download Website</strong></a> &bull;
  <a href="https://github.com/gptgamestudio/hellwar-warzone/releases"><strong>GitHub Releases</strong></a> &bull;
  <a href="https://gptgamestudio.github.io/hellwar-warzone/privacy.html"><strong>Privacy Policy</strong></a>
</p>

---

## Official Android APK Download

> **Package ID:** `com.hellwar.game` &bull; **Minimum Android:** Android 7.0+ (API 24) &bull; **Target SDK:** Android 16 (API 36)

<!-- RELEASE_INFO_START -->
| Field | Details |
|---|---|
| **Target Release** | `v1.0.1` |
| **APK File** | `helicopter-war.apk` (`35.10 MB` verified local build) |
| **Build Status** | Workflow fixed & verified locally; push to GitHub & trigger `v1.0.1` release tag to publish asset |
| **Official Download Page** | [gptgamestudio.github.io/hellwar-warzone](https://gptgamestudio.github.io/hellwar-warzone/) |
| **Releases Page** | [View GitHub Releases](https://github.com/gptgamestudio/hellwar-warzone/releases) |
<!-- RELEASE_INFO_END -->

### How Releases Auto-Update
Whenever a version tag (`v*`, e.g., `v1.0.1`) or manual release dispatch runs in GitHub Actions:
1. `.github/workflows/build-apk.yml` compiles and verifies `helicopter-war.apk`.
2. Uploads `helicopter-war-apk` as a workflow artifact and publishes `helicopter-war.apk` directly to GitHub Releases.
3. Automatically updates this `README.md`, `index.html`, `hosting/index.html`, `release-info.json`, and `sitemap.xml` with the exact verified tag, APK file size, release date, and direct asset URL.

---

## Release Notes (`v1.0.1`)
- **CI/CD & Google Sign-In Resilience:** Fixed GitHub Actions JDK 21 / Android SDK setup, resolved dynamic `default_web_client_id` lookup when `google-services.json` is stored via GitHub Secrets, and added automated keystore materialization.
- **AdMob Privacy & Stability:** Opted out of deprecated Privacy Sandbox `ACCESS_ADSERVICES_*` permissions (`tools:node="remove"`) while preserving `AD_ID` and live Banner, Interstitial, and `+1,000 CR` Rewarded Supply Drop ads.
- **Official SEO Website & Auto-Release Sync:** Added mobile-responsive download portal with `SoftwareApplication` schema, `sitemap.xml`, `robots.txt`, and automatic release metadata synchronization.

---

## Four Empires Showcase & 200 Enemy Barracks

Battle across **4 distinct warzones**, each guarded by **50 unique fortified barracks (200 total)**, patrol aircraft, armored columns, and environmental hazards. Destroying all 50 barracks in an empire shatters its Headquarters forcefield and triggers an epic multi-phase Boss Battle:

| Empire Theater | Fortified Targets | Supreme Flagship Boss | Environmental Hazard |
|---|---|---|---|
| **Dragon Empire** | 50 Volcanic Barracks | *Ignis Rex: Dragon Emperor* | Erupting Lava Geysers |
| **Alien Empire** | 50 Plasma Pylon Barracks | *Overseer Prime: Alien Mothership* | Orbital Plasma Lightning |
| **Orc Empire** | 50 Ironclad Siege Barracks | *Warlord Gashrak: Sky Fortress* | Burning Smog Firepits |
| **Human Empire** | 50 Military Hangar Barracks | *Project Titan: Supreme Gunship* | EMP Defense Grids |

<p align="center">
  <img src="assets/site/empire-dragon.jpg" alt="Dragon Empire Theater" width="24%" />
  <img src="assets/site/empire-alien.jpg" alt="Alien Empire Theater" width="24%" />
  <img src="assets/site/empire-orc.jpg" alt="Orc Empire Theater" width="24%" />
  <img src="assets/site/empire-human.jpg" alt="Human Empire Theater" width="24%" />
</p>

---

## Gameplay Screenshots

<p align="center">
  <img src="assets/site/screenshot-sortie.jpg" alt="Helicopter War Tactical Combat HUD" width="32%" />
  <img src="assets/site/screenshot-boss.jpg" alt="Dragon Emperor Multi-Phase Boss Battle" width="32%" />
  <img src="assets/site/screenshot-hangar.jpg" alt="Warzone Hangar Command Screen" width="32%" />
</p>

---

## Core Game Features
- **4 Playable Gunship Classes:** Unlock and pilot *Scout*, *Heavy Gunship*, *Stealth*, and *Ultimate War* helicopters with distinct armor, speed, shield, and ordnance specs.
- **5 Combat Upgrade Categories:** Upgrade Engine, Armor, Weapons, Defense Shields, and Radar/Tactical systems in the Hangar.
- **Precision Touch Controls:** Cross-shaped directional D-pad with banking roll physics, auto-firing twin 30mm autocannons, fan-spread Rocket Barrages, and Target-Lock Guided Missiles.
- **Google Sign-In & Cloud Firestore Sync:** Zero-trust Firebase backend (`hellwar-warzone`) persists pilot credits, high scores, and every destroyed barracks/headquarters victory across devices.
- **Rewarded Supply Airdrops:** Optional AdMob rewarded video drops grant `+1,000 CR` directly synced to Cloud Firestore.

---

## Android Installation Instructions
1. Open the [Official Download Website](https://gptgamestudio.github.io/hellwar-warzone/) or [GitHub Releases](https://github.com/gptgamestudio/hellwar-warzone/releases) on your Android phone.
2. Tap **Download APK (`helicopter-war.apk`)**.
3. Open the downloaded `helicopter-war.apk` from your browser downloads or file manager.
4. If Android prompts *"For your security, your phone currently isn't allowed to install unknown apps"*, tap **Settings** &rarr; enable **Allow from this source** for your browser.
5. Tap **Install** and launch **Helicopter War** (`com.hellwar.game`).

---

## Security Rules & Owner-Only Access Policy
- **Project Owner Account Only (Backend & Admin):** Only the verified developer owner account (`gptgamestudio`) holds administrative access to Firebase backend (`hellwar-warzone`), Firestore Security Rules, signing keys, and GitHub Secrets. Players have **zero access** to backend configuration, database administration, or rules modification.
- **Strict Player Data Isolation (`request.auth.uid == userId`):** Global database access defaults to deny (`allow read, write: if false`). Authenticated Google Sign-In players can only `get`/update their **own** `users/{uid}` document and append-only battle ledgers; `list` across users, cross-player reads/writes, unauthenticated access, and deletions are strictly blocked in `firestore.rules`.
- **Full Policy:** See [`privacy.html`](privacy.html) / [Official Privacy & Security Policy](https://gptgamestudio.github.io/hellwar-warzone/privacy.html).

---

## GitHub Actions Secrets & Google Search Console Setup

### 1. Optional CI Secrets (No Keys Exposed in Source)
To enable full Google Sign-In + Firebase Firestore in CI-built APKs and Firebase Hosting auto-deploy, add these in **Repo Settings &rarr; Secrets and variables &rarr; Actions**:
- `GOOGLE_SERVICES_JSON`: Contents of your `app/google-services.json`
- `DEBUG_KEYSTORE_BASE64`: Contents of `debug.keystore.base64` (keeps SHA-1 matching Firebase Android OAuth)
- `FIREBASE_SERVICE_ACCOUNT_HELLWAR_WARZONE`: Optional JSON key for Firebase Hosting auto-deploy

### 2. Google Search Console Verification
1. Open [Google Search Console](https://search.google.com/search-console) &rarr; **Add Property** &rarr; **URL prefix**: `https://gptgamestudio.github.io/hellwar-warzone/`.
2. Choose **HTML tag** verification, copy the `content="..."` token, and replace `YOUR_GOOGLE_SEARCH_CONSOLE_TOKEN` inside `index.html` (line with `google-site-verification`).
3. Push to GitHub, wait ~1 minute for GitHub Pages deployment, tap **Verify** in Search Console, then open **Sitemaps** and submit `sitemap.xml`.
