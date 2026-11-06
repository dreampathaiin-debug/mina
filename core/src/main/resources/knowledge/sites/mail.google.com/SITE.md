---
name: mail.google.com
display: Gmail
description: Gmail on the web: mail search, reading, composing and labels
domains: ["mail.google.com"]
aliases: ["Gmail", "Google Mail"]
apps: ["gmail"]
open: mail.google.com
mode: desktop
---
## What it is
Google's web mailbox, requiring a Google account sign-in.

## Pages and URLs
Entry `https://mail.google.com/`. Interface: a search box at the top; on the left, Compose plus inbox, sent, drafts, spam, bin and custom labels; the mail list in the middle. The interface language follows the account's settings, so button names are not necessarily English. Appending `#search/<encoded query>` to the current account's address searches directly; the account index in the address (like `u/0`) matches the signed-in account. Search syntax is in `references/gmail-search-syntax.md`.

## Features
- **List and search**: each row has sender, subject, date and unread state, with no body. The list is virtualized: scrolling down removes the rows above, so the rows scrolled past are not the mailbox's total.
- **Message body**: opening a message shows sender, subject, body, time and attachments. Opening a message may change its unread state.
- **Compose, reply, forward**: new mail uses Compose; reply and forward require the message open first. On a reply, recipient and subject fill in automatically. After sending, the message appears under Sent; closing the compose window does not mean it went out.

## Known behaviour
Signing into the Google account and "confirm it is you" are two separate checks.
