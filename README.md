# beConscient

A self-hosted, privacy-first measurement of **all** your screen time: phone and computer, work included.

Most screen-time tools show per-app totals on one device. beConscient records when you pick up a screen, what you do and what pulled you in, across your Android phone and your Linux desktop, and shows it on a page you host yourself. The headline number is not your screen time but the hours you were awake **away** from screens.

It measures and shows, so you have an honest baseline before you change anything. When you're ready to change, the Android collector can also be your home screen: a text-only launcher that makes you wait five seconds, with a message you wrote, before opening the apps you chose. It never blocks them.

## What it records

| Source | What | What it never sends |
|---|---|---|
| Android | Foreground app periods, unlocks, notification arrivals and whether you tapped or dismissed them, YouTube Shorts vs regular YouTube, and each launcher pause with what you chose | Notification content, senders, messages, your pause messages |
| Linux desktop (GNOME, Wayland) | Active screen time, split into work and personal by your work hours, or into any category for a stretch you mark on the page | Window titles, app names, URLs |
| Browser extension (Chrome, Brave) | Time on YouTube (with video title) and LinkedIn (page kind only) | Any other site, URLs, LinkedIn titles |

Everything travels inside an HPKE envelope to your server, so a TLS-terminating proxy in front of it sees only ciphertext.

## Repository layout

| Folder | What |
|---|---|
| [`contract/`](contract) | Event, envelope and API contracts shared by every component |
| [`backend/`](backend) | Quarkus service: ingest, derivation, page API |
| [`frontend/`](frontend) | The web page (React) |
| [`android/`](android) | Android collector (Kotlin) |
| [`desktop/`](desktop) | Desktop agent and native messaging host (Go) |
| [`extension/`](extension) | Browser extension (Manifest V3) |
| [`deploy/`](deploy) | How production runs (the manifests live in [be-infra](https://github.com/AndDev741/be-infra)) |
| [`tools/`](tools) | Key generation and envelope test vectors |
| [`docs/`](docs) | Design notes |

Start with [`docs/design.md`](docs/design.md).

## Status

Under active development.
