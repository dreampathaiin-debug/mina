---
name: skill-authoring
display: Write skills
description: Create, import or change a skill
aliases: ["create skill", "new skill", "write a skill", "save skill", "import skill", "edit skill", "skill"]
terms: ["skill", "procedure", "workflow", "steps", "template", "always do it this way", "save as skill", "import", "rewrite"]
---
## When to write one

- Write one when the user clearly asks to "create a skill", "save this way of doing it", "import this as a skill" or "change a skill". When they are only talking about how something is done or asking whether it can be done, answer first; do not save on your own.
- A skill records how a kind of task is done from now on, not a one-off fact or preference. "I don't eat cilantro" is a memory, not a skill.
- Only `skill_save` can write a skill. When the tool is not at hand, tell the user plainly it cannot be saved this time; never say it was saved.

## Check for an existing one first

1. Use `skill_search` once, with the phrasings for this kind of task.
2. When a personal skill already matches: read its full text with `skill_read`, change it on that basis, and save under its name with the version you read. Never start a second one for the same kind of task.
3. When only a built-in skill matches: built-ins cannot be changed. When the user wants a different way, write their own skill under a new name, and tell them which one will be used from now on.

## Clarify before writing

When the user gives only a vague line ("build me a coffee-ordering skill"), use `ask_user` first to settle the one or two things that most change how it is done: which app or site, where to stop, any fixed requirements. List the key points to save in a few lines for the user to confirm, then save. When the user has already explained the way clearly, or pasted the full content, ask nothing more.

## How to write it

The whole thing is one document: a header plus five sections, headings copied exactly, the whole document under 4000 characters.

```
---
name: order-coffee
display: Order coffee
description: Use when the user wants a coffee delivered or picked up
aliases: ["order coffee", "get a coffee"]
---
## Entry
## Steps
## Where it goes wrong
## What counts as done
## When to stop and hand back
```

- name uses lowercase English, digits and hyphens and says what kind of task it is; display is a short display name.
- description says in one sentence "when to use it", and the user's likely phrasings go into aliases: whether the skill ever gets used depends on these two.
- Entry writes where to start (which app, site or search); Steps writes numbered short sentences, one action per step, naming buttons and pages, never coordinates.
- Anything that changes each time (names, amounts, addresses, text to send) is written as `<param>` or wording like "the contact the user names", never fixed.
- Where it goes wrong records the spots that go sideways and the way around them; What counts as done records where the result can be seen afterwards; When to stop records the moments that need the user to log in, pay, confirm or choose.
- Backticks wrap tool names only; never app names or button text. Never write passwords, verification codes, card numbers or anyone else's private information.
- Short over long: leave out what the model already knows how to do; write only what is particular to this kind of task and its pitfalls.

## Importing content the user pasted

When the user pastes a skill, notes or a procedure written elsewhere, rewrite it into the format above: keep the method itself, drop what has nothing to do with this (commands for other software, tools that cannot run on a phone, scripts), and fill a missing section with a line or two in the original spirit. Leave out any accounts or passwords the original contains.

## Save and tell the user

1. Save with `skill_save`: markdown is the whole document, and quote copies the user's own words this time asking for the skill to be built or changed.
2. Only an ok result means it was saved. Report from the result: the skill's name, whether it is new or a change to an existing one, that it shows in Settings › Memory & skills, and that it will be tried first the next time this kind of task comes up.
3. On a failure, fix per the reason and save again; if it still fails, say plainly it was not saved and why. Never say it was built.
4. Renaming and deleting are not possible here: ask the user to delete the old one in Settings › Memory & skills; to change the name, save a copy under the new name and then ask them to delete the old one.

## What counts as done

`skill_save` returned ok and the name in the result is the one being saved; the name in the answer, and whether it is new or a change, match the result.
