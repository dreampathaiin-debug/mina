---
name: bilibili.com
display: Bilibili
domains: ["bilibili.com"]
aliases: ["bilibili", "Bilibili"]
apps: ["bilibili"]
open: bilibili.com
mode: desktop
description: Video site: teardown, comparison and review videos, comments
---
## What it is

A Chinese video community with wide coverage. 3C-tech teardown, comparison and long-term-use videos are plentiful; white goods, home and baby gear are relatively sparse. Among video authors there are both independent reviewers and vendor or commercially-partnered accounts. A same-named phone app exists.

## Pages and URLs

- Search: `https://search.bilibili.com/all?keyword=<keyword>`, keyword URL-encoded. To limit by publish time, add `pubtime_begin_s` and `pubtime_end_s`, given as Unix second timestamps.
- Video page: `/video/BV...`. Anime and column addresses are not BV videos.

## Features

- **Video search**: results have video links, titles and publish dates.
- **Video page**: description body and publish time; the body does not include comments.
- **Comment section**: comments render inside a separate shadow DOM, so reading the whole page's text or scrolling the outer page usually misses them. What is readable is usually the heat-ranked leading part; each comment has author, body, like count, reply count, date and pinned state; the page separately shows the total.

## Known behaviour

- Comments not read may mean the section is closed, empty, or failed to load.
- The comment API is signed, and requests from outside the page are unreliable.
