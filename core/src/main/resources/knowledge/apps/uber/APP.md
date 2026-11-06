---
name: uber
display: Uber
packages: ["com.ubercab"]
aliases: ["Uber", "uber"]
sites: []
open: Uber
description: Ride types, prices and trips
---
## What it is
Uber is a ride-hailing app, package `com.ubercab`.

## Interface
Bottom entries: Home, Services, Activity, Account. In Home, "Where to?" takes the trip; entries like Later book a trip for a set time.

## Features
- **Estimates**: after the pickup point and destination are entered, a list of ride types appears with names, estimates in the original currency, arrival estimates, capacity and surge pricing. Prices usually need a sign-in to show.
- **Trips**: Activity holds past and current trips; a booked trip shows the driver, plate number and arrival time.

## Known behaviour
- The default pickup point is the device GPS, but it can be changed to an address in another city via the "Search another city" entry.
- Address input boxes have a clear button X; after typing, pick the match from the suggestions.
- Airport addresses ask follow-ups like "Which airline"; a Skip skips them.
- Input-box content may change while typing; read it again after entering.
