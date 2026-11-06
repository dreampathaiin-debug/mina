---
name: android-settings
display: Android Settings
packages: ["com.android.settings"]
aliases: ["system settings", "phone settings", "Android Settings", "Settings", "settings"]
sites: []
open: Settings
description: Android system settings: network, display, permissions
---
## What it is
The settings app that ships with Android, package `com.android.settings`.

## Interface
The home page is a category list with a search box at the top; entering a category leads through layers of sub-pages and detail pages. Setting names, categories and layering vary by system version and phone maker, and some makers add their own entries. The back button's text and look change per page; whether it returns to the home page is judged from the page title and structure.

## Features
- **Search settings**: the top search box finds a setting by name directly.
- **Toggle settings**: Wi-Fi, Bluetooth, airplane mode, location and the like. A toggle's state is sometimes shown only by colour and position; the UI tree may carry no clear on/off text.
- **About phone**: model, system version and more; the same page may also hold the serial number, IMEI, device name and other identifiers.
- **Permissions, developer options, accounts, security, system update, install sources, reset**: changing these really changes the device's state.

## Known behaviour
Tapping a toggle switches it at once, so to merely learn the current state, read the accessibility properties or look at a screenshot; never rely on tapping.
