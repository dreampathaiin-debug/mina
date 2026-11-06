---
name: qqmail
display: QQ Mail
packages: ["com.tencent.androidqqmail"]
aliases: ["QQ Mail", "QQMail", "qqmail"]
sites: []
open: QQ Mail
description: Multi-account mail, plus notes, calendar and cloud files
---
## What it is
QQ Mail is an email app, package `com.tencent.androidqqmail`. Several accounts can be signed in at once and used together; it also carries contacts, notes, calendar, cloud files and attachment management.

## Interface
No bottom tabs. The home page is the "Mail" folder page:
- Top left is the avatar button (element name "Back"); it opens "My mail": an account profile card (with "Edit"), a "My account" list, "Add account", "Settings".
- Top right is "+" (element name "Compose and settings and more"), with a menu: compose mail, write a note, scan documents, settings, time mail.
- Under the title is a search box.
- The list has groups: "Common folders" (all inboxes, all unread, all sent, all starred), "Pending send", "My mail" (one row per signed-in account), "My apps" (contacts, cloud files, attachment management). Unread counts show on the right.

## Features
- **Mail list**: tap a folder on the home page. The top has back, "All accounts" (with a dropdown to pick whose mail) and a compose button; below, a folder dropdown (say "All inboxes"), "Unread", "Manage". Each row: sender, date, subject, snippet; unread rows have a blue dot.
- **Search**: tap the home search box; the top has the input box and "Cancel"; "Search specific content" can limit the search to mail, contacts, notes, calendar, cloud files or documents.
- **Settings**: from the "+" menu or "My mail". The top shows signed-in accounts (each with "Upgrade") and "Add account"; below: new-mail alerts, general, mail, storage, a "Privacy" group, gesture password and fingerprint, help and feedback.

## Known behaviour
- On first open after an update, a few intro pages appear first, with "Skip" at the top right.
- When a login has expired, opening pops "Session expired, please verify the account again", with "Cancel" and "Settings".
- Tapping the home search box by element may do nothing; tapping the "Search" text inside the box opens the search page.
- The element list holds text not visible on screen: each home folder row carries "mark all read" and "not shown on home"; each mail row carries "mark read/unread", "block sender" and "delete" (left-swipe buttons).
- At the top of the inbox list there may be one item labelled "Ad"; it is a promotion, not mail.
