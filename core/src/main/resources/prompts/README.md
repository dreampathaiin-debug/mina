# prompts/: prompts shipped with the app

The fixed text the model sees lives here, not as strings in code; everything is read through `core.prompt.Prompts` / `core.prompt.Feedback`.
`{{name}}` placeholders in the text are filled by the code that reads them (limits, allowed values, names on screen, and so on); a missing fill fails loudly.

## Layers

| Directory | What it is | Where it goes | Who reads it |
|---|---|---|---|
| `system/identity.md` | P0 identity and stance: act for the user, stay within the user's authorization, look before doing, no pretending, say when you don't know | Start of the system prompt | `SystemPrompt.identity()` |
| `system/principles.md` | P1 general principles: whose word counts, how to run a task, answering from evidence, handling obstacles, finding things, safety; true for every screen and every capability | Right after identity | `SystemPrompt.identity()` |
| `capabilities/` | P2 capability notes, one section per capability; `general.md` covers the general tools (reporting failure, follow-up questions, confirmation) and the overall rules for ending a task, and since the general tools are always present it always follows the principles; `groups.md` says which entry point leads to each group of tools (only groups this task was given an entry for are listed); `handover.md` covers handing back to the user and the check before order and send actions, and appears only after a screen-bearing group is expanded; the phone, web and sandbox sections are appended only after their group is expanded; `app-access.md` holds the app-access rules and is appended after the phone section only when the user has disabled apps | System prompt | `SystemPrompt.assemble()` |
| `tools/` | P3 one description per tool (the `description` in the tool definition): how to use it, parameters, what to do with results; when several tools share one template, the tool declares `promptFile` | Tool definitions | The tool itself |
| `entries/` | The short note an entry tool shows while its group is collapsed (a sentence or two: what it does, which tools open up after calling it); once expanded, the full text in `tools/` replaces it with the parameters unchanged | Tool definitions | `ToolRegistry` |
| `feedback/` | Feedback the tool results and the main loop give the model: hints, error explanations (`failure.<error code>`), reminders, source labels for reference material; one key per entry | Tool results, context messages | Tools, `AgentLoop` |
| `roles/` | Prompts for internal roles: verification, describing the screen, locating, memory extraction, route matching, skill drafting, app-knowledge supplements, and judging the outcome after handing over the answer | Each role's own request | Each role |

App, site knowledge and skills are not here; see `knowledge/` and `skills/`.

## Where things go

- The higher up, the more general and neutral: system/ never names a specific tool, app or technology; anything true only for one capability belongs in that capability's note.
- Wording tied to one tool (how to use it, what the parameters mean, what to do with results) goes in `tools/<tool>.md`, not system/.
- Rules that hold only when a capability exists go in `capabilities/<capability>.md`, gated by the same switch as that group of tools.
  When a line in a section needs another capability's tools, start the line with `<!-- needs: tool name -->`: the line is kept only if that tool is present.
- system/ never names any tool (checked by tests; words that are English anyway, like tap and back, do not count).
- Explanations inside tool results go in `feedback/tools.md`, ones the main loop writes itself go in `feedback/loop.md`; keys are unique across both files, and every key must be used by some code.
- Each feedback entry is self-contained: no leading or trailing whitespace, and code never concatenates it with other text (`+` or string templates); to combine, write the separator in the template and place the other entry with a `{{placeholder}}`.
- Within a task the system prompt changes only when a group of tools expands (so the cache invalidates at most once per group); anything that changes every turn (step count, time, which app) never goes into the fixed text here.

## Format

- `tools/`, `system/`, `capabilities/`, `roles/`: one file is one piece of verbatim text; leading and trailing whitespace is trimmed when read.
- `feedback/*.md`: may start with a `<!-- note -->`; each entry starts with a line of its own reading `## key` (keys use only lowercase letters, digits, `_`, `.`),
  and the text up to the next entry is verbatim and may span lines; blank lines at the edges don't count (so separators go in the middle of the template).

## Size

Byte counts (UTF-8) per layer live in the golden file `core/src/test/resources/prompt-surface/sizes.txt`, for reference only, with no hard cap:
every change shows up as growth in the commit diff. Before adding, see whether something old can come out; when something clearly grows, say why in the commit.

## After changing

All text the model sees has golden files: `core/src/test/resources/prompt-surface/` (system prompt, tool definitions, feedback, roles) and
`loop-snapshots/` (message sequences replayed by a fake model). For intentional changes, regenerate with `./gradlew :core:test -PupdateSnapshots`
and review them together in the commit; for changes that affect model behavior, run the evals first. `prompt_version` in the run record summarizes this task's system prompt and tool definitions,
so eval results can be compared by prompt version.

## Tool names

- Phone and web each have one set of screen tools (`phone_*`, `web_*`, same verbs and parameters); verbs sharing a name share the `tools/<verb>.md` template, and the template's `{{verb}}` is replaced with that set's tool name;
  when one screen needs its own note, write `tools/<screen>_<verb>.md`.
- `capabilities/general.md` never names any capability's tools (checked by tests); "which channel to pick" lives in `channels.md`, appended only when there is more than one screen.
- Tool names mentioned in knowledge, skills and prompts must all be currently registered (checked by tests). Old names from before a rename are mapped to the new ones by `LegacyNames`,
  used only for reading old things (saved routes, run records, personal skills and procedures); when the model calls an old name, it is only told the new name, and the call is not executed.
