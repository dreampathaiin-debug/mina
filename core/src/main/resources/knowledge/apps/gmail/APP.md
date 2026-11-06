---
name: gmail
display: Gmail
packages: ["com.google.android.gm"]
aliases: ["Gmail", "Google Mail", "Google email", "gmail"]
sites: ["mail.google.com"]
open: Gmail
description: Google's email app: mail, labels and categories, Meet
---
## What it is
Gmail is Google's email app, package `com.google.android.gm`. Other email accounts can be added and managed together.

## Interface
Two tabs at the bottom: "Mail" and "Meet".
- **Mail**: top left is the menu button ("open the navigation drawer"), the middle is the search box "Search in mail", top right the avatar ("account and settings"). The list opens with the current inbox name (say "Primary"), then summary rows for the "Promotions" and "Social" categories (with new-mail counts), then the mail; bottom right is the "Compose" button.
- **Drawer**: inbox categories (Primary, Promotions, Social, Updates); under "All labels": Starred, Snoozed, Important, Sent, Scheduled, Outbox, Drafts, All mail, Spam, Bin, Manage subscriptions, Create new label, plus user-made labels; under "Google apps": Calendar, Contacts; at the very bottom, "Settings" and "Help & feedback", visible only after scrolling down.
- **Meet**: has "New meeting" and "Join a meeting".

## Features
- **Mail list**: one row per message; the element name joins "unread", sender, subject and snippet; date and star are separate elements. Tapping the sender's avatar selects the message; it does not open it.
- **Search**: tap the top search box to enter; it has a back button, an input box and filter buttons: labels, from, to, attachments.
- **Accounts**: tapping the avatar opens the account sheet: manage Google account, switch account, add another account, manage accounts on this device, storage used.
- **Settings**: entered at the bottom of the drawer; first level is "General settings", one row per account, "Add account".

## Known behaviour
- On first open or after an update, in sequence: the "New look for Gmail" intro page ("Got it"), the manage-email-addresses page ("Go to GMAIL"), the system notification-permission prompt ("Don't allow" / "Always allow"), a card introducing Meet ("Learn more", "Got it"), plus a tip card at the top of the list ("Close").
- The system notification-permission prompt exposes no elements and cannot be closed with back; only its on-screen buttons work.
- First entry to the "Meet" tab pops "Allow Gmail to access phone state" ("Cancel", "Grant permission"); no elements readable.
- The account page exposes no elements; only the picture can be seen.
- While the drawer or the search page is open, the mail list behind it is still in the element list, with the drawer's items after the mail.
- Pressing back on the mail list exits the app.
