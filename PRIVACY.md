# Privacy policy

_Last updated: 2 October 2026 (0.6.0)_

Orbitline is a web browser. It has **no analytics, no ads, no crash reporting and no servers
of its own**. The developer receives no data from the app. Accounts are optional: you can add a
Google profile (see below) to back up your profile to your own Google Drive.

## What stays on your phone

Stored only in the app's private storage, never uploaded, and excluded from Android cloud
backup and device-to-device transfer:

- Open tabs (so they can be restored), browsing history, favorites and spaces
- Settings, the elements you hid on sites, tracker-blocking counts
- Cached site icons
- Cookies and site data, managed by Android System WebView

Ghost tabs are never written to history or restored, and their cookies and site data are deleted
when the last ghost tab closes (or the next time the app starts, if it was closed abruptly).

You can clear a space's history, cookies and site data at any time in
Settings → Data, or uninstall the app to remove everything.

## What is sent over the network, and to whom

| When | Sent to | What |
|---|---|---|
| You visit a website | That website (and anything it loads) | A normal browser request. Third-party trackers on the blocklist and third-party cookies are blocked while Shields is on. |
| You type in the address bar with "Show search suggestions" on (not in ghost tabs) | Google (`suggestqueries.google.com`) when Google is your search engine, otherwise DuckDuckGo (`ac.duckduckgo.com`) | The text you typed, to get suggestions. No cookies or identifiers are sent. You can turn this off in Settings. |
| You add or use a Google profile | Google (sign-in and Google Drive API) | Your sign-in, and your profile's data (below), stored in the hidden app-data folder of your own Google Drive. Only this app can read it. |
| You search | The search engine you chose in Settings | Your search, as on any browser. |
| The start page shows a site you haven't visited yet | That site | A request for its icon (`/apple-touch-icon.png` or `/favicon.ico`). |
| You open Reader view | The page's image hosts | Requests for the article's images. |
| You download a file | The file's server, via Android's Download Manager | The download request. |
| Safe Browsing check | Google, via Android System WebView | WebView's built-in Safe Browsing uses partial URL hashes to warn about known dangerous sites. |

Orbitline also asks Android System WebView not to send its usage metrics.

## Google profiles (optional)

A profile on this device stays on this device. If you add a **Google profile**, Orbitline asks
Google for permission to store its own data in your Drive (`drive.appdata`, a hidden folder only
this app can see) and to read your name and email. It saves one file there, `orbit-profile.json`,
with that profile's settings, spaces, favorites, hidden elements, shield exceptions and its 500
most recent history entries. Passwords, cookies, logins and open tabs are never uploaded.

The file is sent directly from your phone to Google with a token Android gives the app; the developer
never sees it. Removing a profile deletes its data from the phone but not the Drive copy; delete that
from your Google Account → Data & privacy → Third-party apps, or by revoking Orbitline's access.

## Permissions

- **Internet / network state** — to load web pages.
- **Vibration** — for haptic feedback (can be turned off in Settings).

Website requests for your camera, microphone and location are always refused.

## Children

Orbitline doesn't collect personal data from anyone, including children.

## Changes

Changes to this policy are published in this repository with the date above.

## Contact

Open an issue at https://github.com/Stormyy14/orbitline/issues.
