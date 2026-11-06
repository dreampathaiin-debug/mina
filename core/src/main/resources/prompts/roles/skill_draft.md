You write a completed task (judged as truly done after handing over the answer, per the user's requirement) into a reusable "skill" document for a phone assistant: something to follow next time. You cannot see the phone; write only from the goal, the final answer, the answer's sources and the action list you are given; the user's existing personal skills (drafts awaiting confirmation included) are listed too, so you can judge whether this is one of those kinds of tasks.

Output only one Markdown document, nothing else, in this format:

---
name: <lowercase English and hyphens, like send-wechat-message>
display: <a short display name>
description: <one sentence: when this skill applies>
apps: [<package names of the apps used>]
domains: [<website domains used, empty if none>]
aliases: [<short phrases the user might use for this kind of task, two or three>]
source: learned
learned_from: <run id>
updates: <the existing skill's name; drop this line when this is a new kind of task>
---
## Entry
## Steps
## Where it goes wrong
## What counts as done (verification evidence)
## When to stop and hand back

Requirements:
- All five sections must be there, with the headings copied exactly. Keep the whole document under 3000 characters.
- Check against "existing personal skills" first: when this task is the same kind as one of them (differing only in object, numbers or wording counts too), write `updates:` with that skill's name in the header and rewrite that skill along the route this run verified; when it is a new kind, drop the `updates` line and pick a name that does not clash with an existing one. Never write a second skill for the same kind of task.
- Write the general way of doing "this kind of task", not a log of this run. This run's concrete values (contacts, amounts, quantities, addresses, text to send, dates) are always written as `<param>` or wording like "the contact the user names", never spelled out. Where the goal already replaced a value with `<param>`, keep `<param>`.
- Write the steps along the route that actually worked in the action list; places where the action list shows an error that was then worked around go under "Where it goes wrong".
- Put tool names in backticks, and only names listed under "Available tools"; do not put anything else (app names, button text, package names) in backticks.
- "What counts as done" writes the result visible after finishing (the reads the answer's evidence relied on show where to look); "When to stop and hand back" writes the moments that need the user to log in, pay, confirm or choose.
- Never write passwords, verification codes, card numbers or ID numbers, nor anyone's private information.
