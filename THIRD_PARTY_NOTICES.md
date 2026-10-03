# Third-party notices

Orbit includes the following third-party components. Each is used under its own license;
the full license texts ship inside the app (Settings → About → Open-source licenses) and in this
repository (`LICENSE`, `GEIST_OFL.txt`).

| Component | Copyright | License |
|---|---|---|
| Jetpack Compose (UI, Foundation, Material 3, Animation) | The Android Open Source Project | Apache License 2.0 |
| AndroidX Activity, Core, Lifecycle, WebKit | The Android Open Source Project | Apache License 2.0 |
| Material Icons (`material-icons-extended`) | Google LLC | Apache License 2.0 |
| Kotlin standard library, kotlinx.coroutines | JetBrains s.r.o. and contributors | Apache License 2.0 |
| Geist and Geist Mono fonts | The Geist Project Authors | SIL Open Font License 1.1 (`GEIST_OFL.txt`) |
| Tor, tor-android (Orbit VPN) | The Tor Project, Inc.; Guardian Project | BSD 3-Clause License |
| jtorctl | The Tor Project, Inc.; Guardian Project | BSD 3-Clause License |
| OpenSSL (inside Tor) | The OpenSSL Project Authors | Apache License 2.0 |
| libevent (inside Tor) | Niels Provos, Nick Mathewson and contributors | BSD 3-Clause License |
| zlib (inside Tor) | Jean-loup Gailly and Mark Adler | zlib License |
| Zstandard (inside Tor) | Meta Platforms, Inc. and affiliates | BSD 3-Clause License |
| AndroidX LocalBroadcastManager | The Android Open Source Project | Apache License 2.0 |

Orbit renders web pages with **Android System WebView**, which is installed and updated
separately by the device (Google Play / the system) and is not distributed with this app.

## Trademarks

Website names and icons shown in the app (for example on the start page) belong to their
respective owners. They are displayed only to identify the sites you visit, the same way any
browser shows a site's favicon and title. Orbit is not affiliated with or endorsed by them.

Orbit VPN uses the Tor network but isn't made or endorsed by The Tor Project. "Tor" is a
trademark of The Tor Project, Inc., used here only to say which network Orbit VPN uses.

The filter lists Shields uses (EasyList, EasyPrivacy, EasyList Cookie List; dual-licensed
GPLv3+ / CC BY-SA 3.0+, by The EasyList authors) are not included in the app or this repository:
each device downloads them from their publishers. The built-in list in `Shields.kt` is an
original, hand-curated list of domains; it does not
copy any third-party filter list.
