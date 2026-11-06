---
name: info-research
display: Research something thoroughly
description: Web research: sources, cross-checks, citations
aliases: ["research", "look into", "help me understand"]
apps: ["xiaohongshu", "zhihu", "bilibili", "weibo"]
sites: ["zhihu.com", "tieba.baidu.com", "weibo.com", "douban.com"]
terms: ["research", "look into", "understand", "background", "compare", "is it reliable", "source", "latest"]
---
## Entry

First pin down what is being researched: full name, region, time span, version or model. When names collide or the phrasing is ambiguous, ask the user once, and while waiting research the parts the question does not affect. Conditions the user did not give (a time range, say): pick a common set yourself and state at the top of the conclusion which set you used.

Start with `web_navigate` to open a search page (Google, Bing), or use the in-app search inside a relevant app.

## Steps

1. **Find directions**: run two or three broad searches first, to see what the claims are, which sites discuss it, and whether an authoritative publisher exists. In-app search uses two to four keywords, never a full-sentence question.
2. **Find the original source**: for the fact the user asked about directly, trace it to where it was first published: the publisher's own site, an official announcement, the original document, the paper itself, the product or service's own page. News and reposts are leads only; follow the links or names they give to the original.
3. **Add real experience and negatives**: real user experience lives in communities and video platforms (Xiaohongshu, Zhihu, Tieba, Weibo, Bilibili, Douban); problems live on complaint platforms. Run one dedicated search with negative words ("problems", "complaints", "drawbacks").
4. **Cross-check**: read the wording of every key conclusion in at least two unrelated sources. When the two sides disagree, look at publication times and which side is closer to the events, and write the disagreement itself down.
5. **Write the conclusion**: after each conclusion, cite the source (site or publisher) and that item's date; keep what the source actually said separate from your own inference; for anything searched but not found, write what was searched and what is still missing.

## Where it goes wrong

- Search-result snippets are not the original text; they are often truncated or stale. Open the page with `web_read_page` and read the body, and make sure which site you actually landed on.
- One press release gets reposted by many sites; it looks like many sources but there is only one origin.
- Promotional posts, reviews with purchase links and anonymous leaks are low-trust; flag them when you use them.
- A date on a page may be the repost or update date, not when the thing happened.
- Nothing found, a page that will not open, a sign-in wall: all of these mean only that this attempt read nothing. Never take them as "this does not exist".

## What counts as done (verification evidence)

Every conclusion points back to a page actually read this time, with source and date written down; each key conclusion has two independent sources; weak or contradictory spots have their reasons stated.

## When to stop and hand back

When what needs reading is behind a login, or the next step becomes booking, buying or signing up, things the user must decide or pay for themselves, hand the findings back to the user.
