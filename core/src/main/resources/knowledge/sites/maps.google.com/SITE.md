---
name: maps.google.com
display: Google Maps
description: Place details, plus driving, transit and walking routes
domains: ["maps.google.com"]
aliases: ["Google Maps"]
apps: []
open: maps.google.com
mode: desktop
---
## What it is
Google's web maps, for place details and routes, usually without a sign-in. The real address lives under `google.com/maps`, sharing the google.com domain with Google Flights and the like.

## Pages and URLs
- Search: `https://www.google.com/maps/search/<encoded query>`.
- Routes: `https://www.google.com/maps/dir/<encoded origin>/<encoded destination>`.

## Features
- **Places**: name, address, rating and review count, open status, full hours, phone. Hours and phone show only after expanding the details.
- **Routes**: open the route panel, enter origin and destination, switch travel mode (driving, transit, walking and more). Each mode lists several options, each with duration, distance, the roads or transit lines taken, and notes.

## Known behaviour
- A place name in the URL does not mean the page selected that place; same-named branches abound, so check the name, address and city shown on the page.
- Pressing enter directly in the origin or destination box may pick the current location, a recently searched place or another same-named one; picking from the suggestion list is steadier.
- Switching travel mode or opening another option card refreshes the page wholesale; anything read before belongs to the previous state.
