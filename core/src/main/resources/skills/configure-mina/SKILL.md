---
name: configure-mina
display: Change Mina's own settings
description: View and change Mina's own settings
aliases: ["change your settings", "go faster", "stop muting", "change the look"]
terms: ["settings", "configure", "faster", "slower", "mute", "appearance", "dark", "light", "capsule"]
---
## See what can be changed first

Call `settings_list` first. It lists every setting changeable right now: key, display name, current value, and (when there are only a few choices) all allowed values.

**Use only the keys and values the list gives**; never guess a word. When what the user said and what you see disagree (the user says "stop muting", the list calls it "mute_apps"), pick the entry by name and read its `label` back to the user to confirm it is that one.

## Say it before changing

When the user has clearly said what to change, use `settings_set` directly and report the new value after. When their words are vague ("make it faster"), first say what you will change and to what, then change it:

> I set the operating pace to "fast"; it applies from the next task.

**Never change anything the user did not mention.** When changing several items, say so item by item.

## What cannot be changed here

- **Model, API key, sign-in**: not in the list. When the user wants these, send them to Settings › Models; you cannot do this step.
- **Deleting data** (clearing the workspace, resetting the run environment, deleting conversations): also not in the list; ask the user to do it themselves.
- **Container network and proxy**: not in the list. Both move data off the phone, so the user opens or fills them in Settings › Files & compute themselves; never change them for them.

These exclusions are deliberate: the first would let the model swap out its own brain and touch the user's money, the second is irreversible, and the network line is the user's to draw.

## When it takes effect

Most changes apply from the next task; the look (dark or light) switches at once. Mention that when reporting, so the user does not think it did not apply.
