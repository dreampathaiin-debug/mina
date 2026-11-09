# Agent quick path

[简体中文](agent-quickstart.md) | English

For AI agents on a computer: follow these fixed steps to install Mina and connect the phone, without weighing options. Full details: [Install](computer-install.en.md) and [CLI](cli.en.md).

## 1. Resolve the latest release tag

```sh
curl -s https://api.github.com/repos/wangzan101/Mina/releases/latest   # read tag_name
```

```powershell
(Invoke-RestMethod https://api.github.com/repos/wangzan101/Mina/releases/latest).tag_name
```

Below, `<tag>` is that value (e.g. `v0.1.31`) and `<ver>` is the version without the `v` (e.g. `0.1.31`).

## 2. CLI or Skill

- Agent supports skills: **install the Skill by default**; it bundles the CLI script.
- Otherwise install the CLI: `pipx install 'git+https://github.com/wangzan101/Mina.git@<tag>#subdirectory=tools/mina'`.

## 3. Install the Skill

`<skills-directory>` is your agent's skills directory, as given in that agent's own documentation; the Skill goes into a `mina` folder inside it.

POSIX:

```sh
curl -L -o mina.zip https://github.com/wangzan101/Mina/archive/refs/tags/<tag>.zip
unzip -q mina.zip -d mina-src
mkdir -p <skills-directory> && cp -R mina-src/Mina-<ver>/tools/mina <skills-directory>/mina
python3 <skills-directory>/mina/scripts/mina_cli.py --version
```

Windows PowerShell:

```powershell
Invoke-WebRequest https://github.com/wangzan101/Mina/archive/refs/tags/<tag>.zip -OutFile mina.zip
Expand-Archive mina.zip -DestinationPath mina-src
New-Item -ItemType Directory -Force "<skills-directory>" | Out-Null
Copy-Item -Recurse mina-src\Mina-<ver>\tools\mina "<skills-directory>\mina"
py -3 "<skills-directory>\mina\scripts\mina_cli.py" --version
```

Interpreter: `py -3` on Windows, `python3` elsewhere. With only the Skill, `mina` below means `python3 <skills-directory>/mina/scripts/mina_cli.py` (`py -3 ...` on Windows).

## 4. Connect

Use the connection method the user picked on the phone (Settings › Connect a computer). The agent cannot see the phone: if the user has not said which one, ask once; do not guess.

1. USB: use authorized ADB.
   ```sh
   mina devices --json
   mina connect --device SERIAL --json
   ```
   With more than one phone online, ask the user which one and keep using it.
2. Wi-Fi / private VPN: use the HTTP address and token file path the user gives you (read the token from the file only; never show it):
   ```sh
   mina --url http://PHONE:8765 --token-file /path/to/token doctor --json
   ```

State `unauthorized`: ask the user to allow USB debugging on the phone. `offline`: ask them to replug the cable. Connection information unreadable (`connection_info`): ask them to unlock the phone and open Mina once, then run `connect` again.

## Notes

- Do not submit phone tasks; never print or echo the token.
- A successful `connect` / `doctor` only proves connectivity, not that a task will succeed.
