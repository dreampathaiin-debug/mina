---
name: dedao
display: Dedao
packages: ["com.luojilab.player"]
aliases: ["Dedao", "dedao", "Dedao app"]
sites: []
open: Dedao
description: Courses, audiobooks, e-books and a learning community
---
## What it is
Dedao is a learning app, package `com.luojilab.player`. It has paid courses, audiobooks, e-books, live streams and a learning community.

## Interface
Bottom tabs: Home, Read & Listen, Knowledge Community, Learning, and the profile page at the far right ("Not signed in" when signed out). Most tabs have a top search box with credits, recent learning, messages or settings icons beside it.
- **Home**: beside the search box are growth tasks, recent learning and the message centre; below, channels: Recommended, Courses, Long Interviews, AI Learning Circle; the Recommended page has promo banners, entry rows (rankings, courses, read & listen, points, categories), column content and live previews.
- **Read & Listen**: the top splits my membership, audiobooks, e-books; there is a membership banner, new arrivals, author lectures, signature titles, a knowledge map, then daily additions and major new books.
- **Knowledge Community**: community feed split into Video, Following, Recommended; each post has author, time, body, reposts, comments, likes, share.
- **Learning**: your own learning content; signed out only "Sign in / Register" shows.
- **Profile**: avatar (tap to sign in or register), credits, today's learning, badges, certificates; "My account" (balance, coupons, orders, cart, membership, tickets, points); "My content" (notes, recent learning, downloads, saved, subscription notices); invite friends. The gear at the top right is Settings.

## Features
- **Search**: from the top search box. Has an input box, a voice button, "Cancel"; below is search history, then rankings under Trending, Discover, Courses, Audiobooks, E-books.
- **Settings**: account, address management, security centre, identity verification, privacy, night mode, notifications, one-tap note, clear cache, about.

## Known behaviour
- Signed out, opening lands on the phone-number sign-in page; "Back" at the top left skips it, and browsing works without signing in.
- A full-screen promo ad often pops on open, with a close button at the bottom (resource ID `close`, no name).
- Switching to some tabs the first time may pop an onboarding tip; dismiss it before switching.
- The search box's words rotate and the element name changes with them; tapping by element may report it stale, so tap by position.
- Most icons at the top right have no name, only a resource ID, like `settingImageView` for settings.
- The bottom tabs do not show which is selected in the elements; read the picture.
