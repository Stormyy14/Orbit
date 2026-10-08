# Privacy policy

_Last updated: 4 October 2026 (extensions)_

Orbit is a web browser. It has **no analytics, no ads, no crash reporting and no servers
of its own**. The developer receives no data from the app. Accounts are optional: you can add a
Google profile (see below) to back up your profile to your own Google Drive.

## What stays on your phone

Stored only in the app's private storage, never uploaded, and excluded from Android cloud
backup and device-to-device transfer:

- Open tabs (so they can be restored), browsing history, favorites and spaces
- Settings, the elements you hid on sites, tracker-blocking counts, and whether Orbit VPN is on
- The downloaded filter lists, and Tor's network directory (so Orbit VPN connects faster next time)
- If you add Orbit's home-screen widgets: the active profile's favorite sites (address and name)
  and whether Orbit VPN is on, so the widgets can show them. Favorites appear on your home screen.
- Cached site icons
- The extensions you install (their code and whether each is on). What an extension saves for a
  site is kept in that site's storage, like any site data.
- Cookies and site data, managed by Android System WebView

Ghost tabs are never written to history or restored, and their cookies and site data are deleted
when the last ghost tab closes (or the next time the app starts, if it was closed abruptly).

You can clear a space's history, cookies and site data at any time in
Settings → Data, or uninstall the app to remove everything.

## What is sent over the network, and to whom

| When | Sent to | What |
|---|---|---|
| You visit a website | That website (and anything it loads) | A normal browser request. Ads and trackers on the filter lists and third-party cookies are blocked while Shields is on, and known tracking parameters are removed from links. Pages also receive the Global Privacy Control signal. |
| Every few days, while Shields is on | `easylist.to` and `secure.fanboy.co.nz` (fallback: `easylist-downloads.adblockplus.org`) | A download of the public filter lists. Nothing about you or your browsing is sent; all matching happens on the device. |
| Orbit VPN is on | The Tor network (volunteer-run relays), then the website | Everything Orbit loads goes through three Tor relays: the first sees your IP address but not what you open, the last sees which site you open (and its content, if the site isn't https) but not your IP address. Orbit opens http sites over https where it can. When it connects, Orbit asks `check.torproject.org` (through Tor) to confirm the connection. Downloads, which Android's download manager handles, don't go through Tor. |
| You type in the address bar with "Show search suggestions" on (not in ghost tabs) | Google (`suggestqueries.google.com`) when Google is your search engine, otherwise DuckDuckGo (`ac.duckduckgo.com`) | The text you typed, to get suggestions. No cookies or identifiers are sent. You can turn this off in Settings. |
| You add or use a Google profile | Google (sign-in, Google Drive API, and `googleusercontent.com` for your account photo) | Your sign-in, and your profile's data (below), stored in the hidden app-data folder of your own Google Drive. Only this app can read it. Your account photo is downloaded to show on the profile. |
| You search | The search engine you chose in Settings | Your search, as on any browser. |
| The start page shows a site you haven't visited yet | That site | A request for its icon (`/apple-touch-icon.png` or `/favicon.ico`). |
| You open Reader view | The page's image hosts | Requests for the article's images. |
| You open Reader view on a page that shows little text or a paywall | The page's website, and the Internet Archive (`archive.org`) | The page is fetched again without cookies, once as a normal browser and once as Google's search crawler, and the Internet Archive is asked for its latest copy of the page (it receives the page's address). Through Orbit VPN when it's on. |
| You tap a link to a site whose app is on your phone (not in ghost tabs; can be turned off in Settings) | That app | The link, handed over by Android, as other browsers do. |
| Orbit checks for updates (every few hours, or when you ask; can be turned off in Settings) | GitHub (`api.github.com`) | A request for the latest Orbit release. Nothing about you or your browsing is sent. |
| You tap Update | GitHub (`github.com` and its download servers) | A download of the new Orbit APK and its checksum. |
| You install an extension (from a `.user.js` link or a link you paste) | The server the link points to (usually Greasy Fork) | A download of the script and any libraries it lists (`@require`), over https only, through Orbit VPN when it's on. No cookies are sent. |
| You visit Greasy Fork or Sleazy Fork | That site | So its Install button works, the page can ask Orbit whether a script is installed, and gets the names and versions of the extensions you have (as with Violentmonkey). |
| An extension runs | Whatever the extension's code contacts | Extensions run inside the pages they match and can read and change them, and make the requests those pages could. Orbit shows which sites each one runs on before you install it. |
| You download a file | The file's server, via Android's Download Manager | The download request, with the site's cookies for that server and the address of the page you downloaded it from, as browsers send. Files a page makes itself (`blob:` and `data:` links) are saved straight from the page, with no request. |
| Safe Browsing check | Google, via Android System WebView | WebView's built-in Safe Browsing uses partial URL hashes to warn about known dangerous sites. |

Orbit also asks Android System WebView not to send its usage metrics.

## Google profiles (optional)

A profile on this device stays on this device. If you add a **Google profile**, Orbit asks
Google for permission to store its own data in your Drive (`drive.appdata`, a hidden folder only
this app can see) and to read your name, email and account photo. It saves one file there, `orbit-profile.json`,
with that profile's settings, spaces, favorites, hidden elements, shield exceptions and its 500
most recent history entries. Passwords, cookies, logins and open tabs are never uploaded.

The file is sent directly from your phone to Google with a token Android gives the app; the developer
never sees it. Removing a profile deletes its data from the phone but not the Drive copy; delete that
from your Google Account → Data & privacy → Third-party apps, or by revoking Orbit's access.

## Permissions

- **Internet / network state** — to load web pages.
- **Vibration** — for haptic feedback (can be turned off in Settings).
- **Install apps** — so Orbit can install its own updates. Android asks you to confirm every
  update and only accepts one signed with the same key as the Orbit you have.
- **Notifications** and **foreground service (media playback)** — to show media controls and keep
  video or music playing while Orbit is in the background. Asked for the first time something plays;
  "Keep playing in the background" in Settings turns it off.

A start page wallpaper is picked with the system photo picker, so Orbit only gets the one image you
choose. A scaled-down copy is kept in Orbit's private storage on this device; it isn't synced.

Website requests for your camera, microphone and location are always refused.

## Children

Orbit doesn't collect personal data from anyone, including children.

## Changes

Changes to this policy are published in this repository with the date above.

## Contact

Open an issue at https://github.com/Stormyy14/Orbit/issues.
