---
name: weixin.sogou.com
display: Sogou WeChat search
domains: ["weixin.sogou.com"]
aliases: ["Sogou WeChat search"]
apps: []
open: weixin.sogou.com
mode: desktop
description: Search WeChat official-account articles
---
## What it is
Sogou's search over WeChat official-account content. Results are article snippet cards, not full text.

## Pages and URLs
- Article search: `https://weixin.sogou.com/weixin?type=2&query=<encoded keyword>`.
- Use the full name or a core term with a business qualifier; common short terms are very noisy.

## Features
- **Official-account article search**: each card has an article snippet, the account name and a redirect link.

## Known behaviour
- Card links are encrypted redirects that expire, and several cards may share one address; they are not stable article URLs.
- Telling cards apart is covered in `references/article-links.md`.
