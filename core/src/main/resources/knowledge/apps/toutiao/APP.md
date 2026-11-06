---
name: toutiao
display: Toutiao
packages: ["com.ss.android.article.news"]
aliases: ["Toutiao", "toutiao", "Today's Headlines"]
sites: []
open: Toutiao
description: A news and article feed, short videos and a shop
---
## What it is
Toutiao is a news app, package `com.ss.android.article.news`, with an article feed, vertical videos and a shop.

## Interface
Bottom tabs: Home, Video, the central "+" (publish), Store, and the profile page at the far right ("Not signed in" when signed out).
- **Home**: a search box at the top (cycling recommended trending terms) and "Doubao AI" beside it; channels below: Following, Recommended, Trending, Discover, your city, Video. Each feed item has title, source and comment count.
- **Video**: full-screen vertical videos, switched by vertical swipes; the top has search, channels (Following, Featured, Recommended, Find shorts) and publish; on the right the author's avatar, like, comments, save, share.
- **Store**: top channels Live, Store, national subsidy; below a product search box, cart and orders, check-in, coupons, followed shops, then the product feed.
- **Profile**: signed out, it opens the sign-in page; "Settings" at the top right.

## Features
- **Search**: from the Home search box. The top has back, an input box, a camera icon, "Search"; below are recommended terms, search history (with a delete), "You may want to search", "Toutiao trending"; at the bottom an "AI search" switch, "Voice search" and an "incognito search" switch.
- **Settings**: scan, privacy, dark mode, elder mode, font size, article scroll style, auto reader mode, clear cache, audio, playback and network, push notifications, safe browsing. Signed out, entered from the sign-in page's top right.

## Known behaviour
- Signed out, opening shows the sign-in page ("One-tap login" with a Douyin account, plus "Other ways"); "Close" at the top left dismisses it; Home and Video work without signing in.
- On first open the app's own notification prompt may sit on top ("Allow 'Toutiao' to send notifications?"), with "Always allow" and "Not now".
- The search box's element name is the trending term showing, not what the user typed; the search page's input box also pre-shows a recommended term.
- In the Home feed a title is often split one element per character; the whole title is in the outer tappable element's name.
- Entering the Store may pop a large check-in-cash dialog ("Link Douyin and claim") with a closing "×"; it exposes no elements.
- On the Store page the element list holds mostly the top channels and bottom tabs; the search box, entries and products need the picture. Signed out, prices are partial, with "Sign in to see prices" at the bottom.
