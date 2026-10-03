# Security policy

## Reporting a vulnerability

Please **don't** open a public issue for security problems. Report them privately through
GitHub: **Security → Report a vulnerability** on
https://github.com/Stormyy14/Orbit (private vulnerability reporting).

Include what you found, how to reproduce it, and the Orbit and Android System WebView
versions. You'll get a reply as soon as possible, and credit in the release notes if you want it.

## Supported versions

Only the latest release receives security fixes.

## How Orbit is hardened

Orbit relies on **Android System WebView** for the web engine, so keep WebView updated from
Google Play. That's where most browser-engine security fixes arrive. On top of WebView:

- **No JavaScript interface.** Pages can't call app code. The only page → app channel is a
  `WebMessageListener` that accepts messages only from the main frame of the page you're on,
  and only while you're using "Hide elements".
- **No local access.** File and content access are disabled, and `file:`, `content:`,
  `javascript:` and `intent:` URLs typed or pasted into the address bar are treated as searches.
- **Other apps need a tap.** Links to other apps (`intent:`, `market:` …) only open from a tap;
  otherwise you're asked first. Intents are restricted to browsable activities with no explicit
  component, selector, clip data or URI-permission grants. `browser_fallback_url` must be http(s).
- **Input from other apps** (shared text, web-search intents) can only open http(s) pages or run
  a search.
- **TLS errors are never bypassed**, mixed content is blocked, and pages can't navigate to
  top-level `data:` URLs.
- **Pop-ups** need a user gesture; site permission requests (camera, mic, location) are denied.
- **Safe Browsing** is explicitly enabled.
- **Privacy:** third-party cookies are refused while Shields is on and always in ghost tabs.
  Ghost tabs use a separate WebView profile (where supported), are excluded from screenshots and
  the recent-apps preview, and their data is wiped when they close or on the next start.
- **Data stays on the device:** no backup or device-transfer of app data.
- Network fetches the app makes itself (icons, reader images, suggestions) are size-capped and
  time-limited.
