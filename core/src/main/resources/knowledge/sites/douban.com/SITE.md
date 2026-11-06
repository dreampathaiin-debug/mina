---
name: douban.com
display: Douban
description: Ratings, short reviews and group threads for books and films
domains: ["douban.com"]
aliases: ["Douban"]
apps: []
open: douban.com
mode: desktop
---
## What it is
A rating and review community for books, films and music, with interest-based group discussions (city rental groups, for example).

## Pages and URLs
- Category search: `https://search.douban.com/<book|movie|music>/subject_search?search_text=<encoded name>`. The old site-wide search path exposes no ordinary links; use category search.
- Group discussion search: `https://www.douban.com/group/search?cat=1013&q=<encoded keyword>`. Results are viewable without signing in.
- Group threads: `https://www.douban.com/group/topic/<thread id>/`. When signed out it shows "You do not have permission to view this page. Please sign in and try again.".

## Features
- **Entry search**: book, film and music entries. Same-named works abound: films are separated into originals and sequels by year, books by author and edition, music by creator and album.
- **Ratings and short reviews**: overall score, number of raters, 1-5 star distribution, and for popular short reviews the author, date, helpful count and body. Works with the same total score may be lopsided or polarized in their distribution.
- **Group threads**: title, post time, body, reply count. Thread content is the poster's own account; rental threads go stale fast, with sublets, wanted ads and agents posing as individuals mixed in.

## Known behaviour
- Picking the wrong category returns empty results.
- Group search results mix in discussions merely touching the keywords (relationships, buying a home and the like); filter by title and group.
- To find personal listings, search the "<city> rentals" group; among same-named groups, the one with more members is more active.
