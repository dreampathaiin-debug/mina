---
name: content-transcribe
display: Turn content into text
description: Turn video, podcast or article into text
aliases: ["summarize video", "organize podcast", "article summary", "extract subtitles"]
apps: ["bilibili", "xiaohongshu"]
sites: ["youtube.com", "xiaoyuzhoufm.com"]
terms: ["summary", "summarize", "transcribe", "subtitles", "podcast", "chapters", "key points", "quotes", "transcript"]
---
## Entry

First ask which result is wanted: a full transcript, a summary, chapters, quotes, or a rewrite as an article. When the user does not say, make a five-to-ten-sentence summary.

The text comes from where the content lives: articles read the page body; videos use their subtitles or the transcript in the description; podcasts look for a transcript or show notes on the episode page. There is no speech-to-text on this device, so when there is only audio, ask the user for a transcript or subtitle file.

## Steps

1. **Get the full text**: read the page body with `web_read_page`; subtitle or transcript files the user sent sit in `/workspace/inbox/`, read with `read_file`.
2. **Read in segments**: for long content read it in several passes with a little overlap between them, drop the duplicates afterwards, and confirm nothing was skipped from start to end.
3. **Write in the wanted shape**: the formats and examples are in `references/transcript-output-formats.md`; read it once before writing. A summary's skeleton: one or two sentences on the whole; three to eight topics in order of appearance, each with an approximate start time and two or three sentences; and finally the points, numbers and mentioned books or works worth noting.
4. **Write in the content's language**, or in the language the user asked for.
5. **Read it back**: after writing, check it against the source: is the order right, are any topics missing, can the times and quotes be found in the source text.

## Where it goes wrong

- Subtitles carry only words; charts, slides and on-screen text in the video cannot be read, so never claim to have seen the picture.
- Auto-generated subtitles have typos and bad line breaks; check proper nouns, names and numbers against context or the description.
- Times are estimated from subtitle positions: write "around 23:00 in", never to the second.
- Mark the passages that were inaudible or had no subtitles; never fill them in.

## What counts as done (verification evidence)

The result covers every part of the source; every quote and time can be found in the subtitles or body text that was fetched.

## When to stop and hand back

When the content needs a login or payment, or there is only audio and no transcript at all, tell the user what is missing and ask them to provide it.
