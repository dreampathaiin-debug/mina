---
name: xiaoyuzhoufm.com
display: Xiaoyuzhou
domains: ["xiaoyuzhoufm.com"]
aliases: ["Xiaoyuzhou"]
apps: []
open: xiaoyuzhoufm.com
mode: desktop
description: Chinese podcast platform: episode audio, title, duration
---
## What it is
A Chinese podcast platform. Each episode has a public episode page carrying the audio address and metadata.

## Pages and URLs
Episode page `https://www.xiaoyuzhoufm.com/episode/<id>`, where `<id>` is 20 to 32 lowercase hex characters (`[0-9a-f]{20,32}`).

## APIs
The episode page's full HTML is about 300KB; only three fields are needed, so fetch the page with `wget` in the sandbox and filter with `grep -oE`:

```sh
wget -qO- 'https://www.xiaoyuzhoufm.com/episode/<id>' | grep -oE '<meta property="og:audio" content="[^"]+"|"duration":[0-9]+|<meta property="og:title" content="[^"]+"'
```

- `og:audio`: the audio file's real address, usually under `https://media.xyzcdn.net/`.
- `duration`: episode duration in seconds.
- `og:title`: the episode title.

## Known behaviour
After a redesign the page may lack `og:audio`; the audio address can then not be read from the page.
