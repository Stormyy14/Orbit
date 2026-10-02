<p align="center">
  <img src="branding/orbit-logo-light.png" alt="Orbit" height="72">
</p>

<p align="center">
  A thumb-first Android browser with a quiet, precise interface.<br>
  Kotlin + Jetpack Compose on Android System WebView. No analytics, no ads; accounts are optional.
</p>

## Install on your phone (free)

1. On your phone, open the [latest release](https://github.com/Stormyy14/orbitline/releases/latest)
   and download **`Orbit-<version>.apk`**.
2. Open the downloaded file. Android will ask you to allow your browser (or Files app) to
   **install unknown apps**. Allow it for that app only.
3. Tap **Install**, then open Orbit. Optionally set it as your default browser in
   *Settings → Apps → Default apps → Browser app*.

Updates: install a newer APK from Releases over the old one. Your tabs and settings are kept
because every release is signed with the same key.

Requirements: Android 8.0 or newer, with **Android System WebView** kept up to date from
Google Play (it's the web engine, and where browser security fixes arrive).

Android shows a "Play Protect" warning for apps that don't come from Google Play. That's
normal for apps installed directly from GitHub. You can check the APK's signing certificate
(SHA-256 below) with `apksigner verify --print-certs Orbit-<version>.apk`.

```
Release signing certificate SHA-256:
0d:f0:80:3f:1d:73:5c:fd:a2:f0:b3:20:88:6b:19:9f:63:bf:b4:6b:b8:a7:78:e4:98:5e:53:bc:ee:a5:a8:63
```

## Features

| Feature | What it does |
|---|---|
| **Orbit start page** | Your sites sit on three tilted orbits around the current space. The ones you visit most (and pinned ones) sit on the inner orbit. It starts empty, with an **Add sites** button to pick sites quickly. Drag sideways to spin them (the near side follows your finger): inner orbits turn faster, and sites on the far side are drawn smaller and fainter. |
| **Profiles** | Each profile has its own spaces, favorites, history, settings and logins. Without a profile you browse as Guest. A **Google profile** is saved to the hidden app folder of your own Google Drive and restored when you add the same account on another device (logins and open tabs stay on the device). |
| **Orbit bar** | A floating bottom bar. Swipe ←/→ to switch tabs, ↑ for all tabs, ↓ to reload. Long-press and slide for quick actions (Back, Reload, Reader, New tab, Ghost tab, Forward). |
| **Search** | Google by default. One field for URLs, search, `!bangs` (`!yt`, `!w`, `!gh`…), `>commands`, `@spaces`, open tabs and history, plus voice input. |
| **Spaces** | Separate areas (Personal, Work, …), each with its own tabs, history, **cookies and logins** (needs WebView 121+). Each space has an icon you pick. |
| **Ghost tabs** | Private tabs with their own cookie jar that close themselves after 5–60 minutes in the background. They're kept out of screenshots and the recent-apps preview. |
| **Preview** | Long-press a link and choose Preview to open it in a card. Swipe up to keep it as a tab (no reload). |
| **Focus** | Block distracting sites for 15–90 minutes, with an "open anyway for 5 min" escape hatch. |
| **Shields** | On-device tracker and ad blocking (third-party only), third-party cookie blocking, cookie-banner hiding, and a per-site switch. |
| **Hide elements** | Tap any part of a page to hide it on that site for good. You can undo it from Shields. |
| **Reader view** | Articles with Dark / Paper / Light themes, serif or sans, adjustable size. |
| **Tab history** | Every page the current tab has visited. Tap one to jump back. |

There's also:
- Tab sleeping: only 6 live WebViews, the rest sleep with their state saved.
- Session restore and undo close.
- Find in page and desktop mode.
- Dark websites in dark mode.
- Downloads, file uploads and fullscreen video.

## Security and privacy

See [SECURITY.md](SECURITY.md) for how the app is hardened and how to report a vulnerability,
and [PRIVACY.md](PRIVACY.md) for exactly what is stored and what is sent over the network.
In short: everything stays on the device, nothing is backed up, and the app has no servers.

## Legal

- **License:** [Apache License 2.0](LICENSE). Copyright 2026 Stormyy14 and Orbit contributors.
  See [NOTICE](NOTICE).
- **Third-party components:** AndroidX/Jetpack Compose, Material Icons and Kotlin (Apache 2.0),
  plus the Geist fonts (SIL OFL 1.1). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md); the
  license texts are also in the app under *Settings → About → Open-source licenses*.
- **Content blocking:** Shields and "Hide elements" only change how pages are displayed on your
  own device, at your request. The blocklist is an original, hand-curated list. Hiding a cookie
  banner never clicks "accept": no consent is given on your behalf.
- **Trademarks:** website names and icons shown in the app belong to their owners and are only
  used to identify the sites you visit. Orbit isn't affiliated with any of them.
- **No warranty:** the software is provided "as is", as described in the license.

## The logo

An orbit that forms an **O**, with a moon sitting in the gap. It works in a single colour, so it
also works as an Android themed icon. Sources are in [`branding/`](branding) and are generated by
[`tools/logo.py`](tools/logo.py):

| File | Use |
|---|---|
| `orbit-mark.svg` | The mark alone (`currentColor`) |
| `orbit-logo.svg` | Mark + wordmark (Geist SemiBold) |
| `orbit-icon.svg`, `orbit-icon-1024.png` | App icon |
| `orbit-logo-light.png`, `orbit-logo-dark.png` | Wordmark renders |

## Build from source

Requirements: JDK 17, Android SDK 36.

```sh
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # minified release
```

Release signing is read from `~/.gradle/gradle.properties` (`ORBITLINE_STORE_FILE`,
`ORBITLINE_STORE_PASSWORD`, `ORBITLINE_KEY_ALIAS`, `ORBITLINE_KEY_PASSWORD`). Without those
properties, release builds are signed with the local debug key, so anyone can build.

App ID: `io.github.stormyy14.orbitline`, code package `app.orbitline`.

## Project layout

```
app/src/main/java/app/orbitline/
  MainActivity.kt        edge-to-edge host, file chooser, theme changes
  core/Browser.kt        engine: tabs, spaces/profiles, WebView wiring and policy, focus, ghosts
  core/Tab.kt            observable tab state
  core/Scripts.kt        injected JS (theme colour, reader extraction, hide elements, CSP-safe styles)
  core/Shields.kt        blocklist + cookie-banner CSS
  core/Url.kt            address resolution, !bangs, site names from titles
  core/Images.kt         favicon cache and fetching
  core/Store.kt          debounced JSON persistence
  ui/StartPage.kt        orbit start page, ghost start page
  ui/OrbitBar.kt         bottom bar, gestures, quick-action arc
  ui/Pulse.kt            search / command palette
  ui/Deck.kt             tab switcher
  ui/Sheets.kt           menu, shields, spaces, focus, settings, licenses, tab history, link menu
  ui/Overlays.kt         preview, reader, find, focus screen, toasts
  ui/SpaceIcons.kt       the icons a space can use
  ui/Theme.kt            tokens (Orb.*), Geist type scale
branding/                logo sources and renders
tools/logo.py            regenerates the logo, launcher icon and in-app mark
```

## Known limits

- The blocklist is a curated list (~200 domains), not full EasyList.
- Site permission prompts (camera, mic, location) are denied.
- `blob:` downloads aren't supported yet.
- Without WebView 121+, spaces and ghost tabs share one cookie jar (the app says so).
