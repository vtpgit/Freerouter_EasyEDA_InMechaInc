# Contributing

## Reports of a fault

Open an issue with:

- The version of the bridge. The bridge window shows it at the start.
- The interface: Auto Router dialog, toolbar button, or the command.
- The job folder `jobs/job-<time>-<n>` as an archive. It holds the board as the bridge got it, the board text for each engine run, the engine logs and the report.
  A board file is your design. Remove what you do not want to publish, or send a cut-out that shows the fault.
- What you expected and what the editor showed. For a board file job: the number of connection lines in the editor after the rebuild of the copper areas, against the count in the report.

## Build

`./build.sh` builds `bridge.jar` from `src/bridge`. It needs a JDK 17 or later. There are no dependencies.

The setup script (`setup.sh` or `setup.bat`) gets the routing engine. The bridge does not start a job without it.

## Layout of the source

| Path | Content |
|---|---|
| `src/bridge` | The bridge. One Java package without dependencies |
| `src/bridge/Main.java`, `WsConnection.java`, `RoutingJob.java`, `Cli.java` | Start and web server, websocket connection, a job of the editor channel, the commands `route` and `check` |
| `src/bridge/DsnRepair.java`, `ChannelSetup.java`, `SesConverter.java` | Editor channel: repair of the board text, settings for the engine, result for the editor |
| `src/bridge/FileJob.java` | A job of the board file interface: plan, engine runs, repair, further passes, clean-up, report |
| `src/bridge/Ee*.java` | Board file: reader (`EeBoard`), plan (`EePlan`), board text for the engine (`EeDsn`), result (`EeResult`), fill model (`EePlane`), repair (`EeBridge`), further passes (`EeRework`), clean-up (`EeTidy`), part moves (`EeMove`, `EePerturb`), checks (`EeCheck`, `EeClear`) |
| `src/bridge/Engine*.java`, `Variant.java` | Engine processes, parallel runs and their settings |
| `src/bridge/Rules.java`, `Config.java` | `config/rules.json` and `config/main.json` |
| `easyeda` | The script and the toolbar extension for the editor |
| `test/bridge` | Command line tools for single steps |
| `docs` | User guide, rules reference, test report |

## Test tools

Build them after the bridge:

```
javac -nowarn -encoding UTF-8 --release 17 -cp classes -d test/classes test/bridge/*.java
```

Run one with `java -cp classes:test/classes bridge.<Tool>`. The arguments of each tool are in the comment at the top of its source file. Examples:

| Tool | What it does |
|---|---|
| `MoveTool` | Moves every part of a board file and checks the moved file with the board reader |
| `TidyTool` | Runs the track clean-up on a routed board file |
| `NarrowTool` | Lists each track segment below the track rule and the copper that is in the way of the full width |
| `PerturbTool` | Searches part moves on the result of one engine run |
| `RecombineTool` | Builds a further pass from the results of several runs |

The system properties with the prefix `bridge.test.` are for tests only. They are not part of the interface.

## Rules for a change

- A change of the routing behaviour needs a measurement on a board: open connections, vias and track length before and after, with the same job time on the same machine.
  The engine does not repeat its result exactly, so a difference of a few connections between two jobs is not a finding.
- Count open connections with the check of the bridge and with a second method, for example the connection lines in the editor after the rebuild of the copper areas.
- Describe each new key of a rules file or settings file in `docs/RULES.md` or in the user guide, and add the change to `CHANGELOG.md`.
- The documentation uses short sentences and plain words.

## License of contributions

Contributions are accepted under the license of the project, the GNU General Public License version 3 or any later version.
