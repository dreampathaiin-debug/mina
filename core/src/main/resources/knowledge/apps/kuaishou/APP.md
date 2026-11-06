---
name: kuaishou
display: Kuaishou
packages: ["com.smile.gifmaker"]
aliases: ["Kuaishou", "kuaishou", "kwai"]
sites: []
open: Kuaishou
pace: careful
description: Short videos, live streams, DMs and the Kuaishou shop
---
## What it is
Kuaishou is a short-video and live-stream app, package `com.smile.gifmaker`. It also has a shop (Kuaishou Store) and direct messages.

## Interface
Bottom tabs: Home, Featured, the central "shoot" button, Messages, Me.
- **Home**: sidebar button top left; channels: Store, Following, Discover, Nearby, Trending, and "Find" (search) at the far right. Discover shows two columns of work cards.
- **Featured**: full-screen vertical videos, switched with vertical swipes. On the right: the author's avatar ("Follow"), like, comments, save, share, music disc; below, the author's name and caption.
- **Messages**: frequent contacts on top, then new followers, interaction notices and the direct-message list.
- **Me**: profile, followers / following / likes; entries for Kuaishou Store, people watched often, shopping groups, offline mode; tabs for Works, Private, Recommended, Saved, Liked. Top right has search and the "sidebar entry".

## Features
- **Search**: entered from "Find" at the top right of Home. The top has back, an input box, "Search"; below are search history (with a delete), "Trending searches", "People you may want to watch" (with a "shuffle"), then rankings for hot topics, trending people, live streams and shopping.
- **Home sidebar**: scan, Kuaishou Store, task centre; system notices; common functions (history, offline mode, settings, my wallet, drafts); tool services (official support, minor mode, content preferences, watch later).
- **"Me" page sidebar**: my wallet, my group chats, cart, QR code, scan, share profile, offline mode, watch later; at the bottom "Manage profile" and "Settings".
- **Settings**: account (bound phone, account and security, privacy), general (general settings, add widget, notifications, playback, block list, go live, storage, dark mode) and more.

## Known behaviour
- On open it may land straight on a full-screen video (Featured); the element read may still show "Home" as the selected tab, so confirm from the picture.
- A red-packet widget (with a number and a close button) often hangs over the top-left of the page, covering content.
- First entry to "Messages" may pop an intro to group chats; dismiss it with "Got it".
- System notices are not in the "Messages" tab; they are in the home sidebar.
- In the home sidebar, tappable elements are named "item_N"; their text is a separate element beside them.
- The search input box's element name is the recommended term pre-shown inside the box.
