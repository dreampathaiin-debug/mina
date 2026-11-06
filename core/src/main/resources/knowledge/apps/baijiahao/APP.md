---
name: baijiahao
display: Baijiahao
packages: ["com.baidu.baijia"]
aliases: ["Baijiahao", "baijiahao", "Baidu Baijiahao"]
sites: []
open: Baijiahao
description: Baidu's creator app: publish content, track stats
---
## What it is
Baijiahao is Baidu's creator app, package `com.baidu.baijia`. It is used to publish and manage your own content, watch reads, followers and earnings, and handle comments and notices.

## Interface
Bottom tabs: Home, Manage, the central "+" (publish), Messages, Me.
- **Home**: title "Baijiahao", below it "Trending events", switchable by channel (whole web, city, entertainment, sports; a button at the right changes channels).
- **Manage**: stat cards on top: total reads, this month's earnings, total followers, message analytics; below, your own works listed under tabs for all, posts, articles, videos, shorts and live; each row has a title, publish time, status (say "Published"), a few interaction counts and a "..." action menu; a search button sits at the top right of the list.
- **Messages**: the top splits interactions, notices, announcements, with message settings at the top right; "Interactions" groups by comment, follow, like, save, share, with the message list below.
- **Me**: profile card (followers, following, activity score, credit score), with scan and QR code at the top right; "Common functions": my benefits, my verification, my info, comment management, material management, topic management, showcase management, block-list management, my saves, streamer centre, Bai+; "More functions": publishing settings, message settings, system settings, help and feedback.

## Features
- **Publish**: the bottom "+" pops a publish panel showing how many posts are left today; choices include article, post, live, photo set, record video, upload video, video collection, drafts, with "Recommended events" below and a close button at the bottom.
- **System settings**: clear cache, about (version number), collected-personal-information list, third-party data sharing list, sign out.

## Known behaviour
- On open it may pop "Notifications are not enabled yet ...", with buttons "Cancel" and "Enable".
- Many buttons have no name, only a resource ID: back (`left_button`), scan (`scan_it`), QR code (`qr_code`), the work action menu (`operation_menu_layout`).
- In Manage, the numbers under a work show only the count in the elements, without saying which metric; read the picture's icons.
