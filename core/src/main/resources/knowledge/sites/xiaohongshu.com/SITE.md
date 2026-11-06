---
name: xiaohongshu.com
display: Xiaohongshu
domains: ["xiaohongshu.com", "xhslink.com"]
aliases: ["Xiaohongshu", "XHS", "xiaohongshu", "xhs"]
apps: ["xiaohongshu"]
open: xiaohongshu.com
mode: desktop
description: Note community: search notes, read body and comments
---
## What it is
A content community built on user notes, which are image-text or video, with ads and recommendation-seeking posts mixed in.

## Pages and URLs
- Search: `https://www.xiaohongshu.com/search_result?keyword=<encoded keyword>&source=web_explore_feed`, opens directly.
- Note detail: `/explore/<note id>` with an `xsec_token` parameter; without the token it will not open.
- `xhslink.com` is the share short-link domain.

## Features
- **Search**: results are cards with title, author, like count, cover, image-text or video type, and link. About 20 per first screen; two or three scrolls down reach fifty or sixty. A valid signed-in session is usually required.
- **Note detail**: body, publish time, IP location, likes, saves, comment count, and comments (author, wording, like count). Opening a card from search pops a detail layer; the card's link carries its own `xsec_token`.
- **Phone app**: see `xiaohongshu` for the matching description.

## Known behaviour
- Some cards have no title and show the opening of the body instead.
- Comments load only the first page at first; a comment count of 0 is what actually means no comments.
- Abbreviated counts like "12k" display as-is.
- Publish time and update time are two fields; some notes show only the latter.
- Search results carry ad cards, which are not notes.
- When the service is busy it says the network is busy; retrying after a few seconds usually clears it.
