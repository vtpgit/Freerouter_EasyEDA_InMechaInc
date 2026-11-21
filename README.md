# EasyEDA to Freerouting bridge

A local auto router for the EasyEDA Standard editor. It puts the Freerouting engine behind the local router interface of the editor.
It replaces the EasyEDA local auto router 0.8.11.

The bridge is a small Java program without dependencies. It runs on your computer. Boards do not leave it.

Version 1.3.0. License: GPL-3.0-or-later.

## What it does

The bridge has two interfaces.

| Interface | What the bridge gets | Use it for |
|---|---|---|
| Editor channel | The board text of the Auto Router dialog: pads, nets, widths, clearances, one via size | A fast start. No file handling |
| Board file | The PCB source of the editor: all of the above, and the copper areas, the footprints and the design rules | Boards with planes, fine-pitch parts, an antenna or a second via size |

The board file interface routes more connections on such boards, because the bridge can read what the Auto Router dialog does not send.

## Results

**Editor channel.** 27 recorded requests of the Auto Router dialog, 2 to 8 layers, 4148 connections. Both programs on the same machine with 300 s for each board. The same geometry check counts the open connections of both.

| | EasyEDA local router 0.8.11 | Bridge |
|---|---|---|
| Boards fully routed, of 27 | 12 | 24 |
| Boards with no result | 3 | 0 |
| Open connections, of 4148 | 1001 | 9 |
| Connections routed | 75.9 % | 99.8 % |

**Board file.** Two boards with 4 layers and copper areas, 134 and 133 parts, each with a part of about 100 pads at 0.4 mm pitch. Machine with 2 processors, 4 engine runs, job time 600 s.

| | Board A, rules 0.15 mm | Board B, rules 0.10 mm |
|---|---|---|
| Connections to route | 259 | 221 |
| Open connections after the job | 3 | 0 |
| Track segments below the track rule | 14 | 0 |

Board A in the editor on a laptop with 300 s, with an earlier version: the Auto Router dialog left 55 connections open. The board file interface left 10.

The result of a large board depends on the speed of the computer and on the job time. The engine does not repeat its result exactly.
All figures, the test set-up and the limits of the tests: [docs/TEST_REPORT.md](docs/TEST_REPORT.md).

## Features

Both interfaces:

- The Freerouting 2.5.0 engine, started as a separate program, without network access.
- Several engine runs for each job at the same time, with different settings. The bridge takes the best result.
- Repair of the board text that the editor sends: board outline in pieces, pads without size, net names that the engine cannot read.
- A rules file for the values that the editor does not send: via on pads, layer costs, planes, time limits.
- A job folder with the input, the engine logs and the result of each run.

Board file interface:

- Track width, clearance and via size from the design rules of the file.
- Copper areas. A pad in a copper area of its net gets no track and no via. A pad whose net has an area on a different layer gets a small via in the pad.
- A model of the fill of each copper area. The count of open connections uses it.
- Repair after routing: vias and short tracks for pads that tracks cut off from their area.
- Further engine passes on the best result, with routes taken from the other runs.
- Narrow tracks: the bridge gives narrow segments the full width where the clearance permits, and one run of four makes none.
- Track clean-up: pieces joined to one record, small steps removed.
- Keep-out around antenna copper that follows the shape of the antenna. More keep-outs by rule.
- Existing tracks can stay or go. A second, smaller via size for vias in pads. Wider tracks for chosen nets.
- A report for each job: open connections by net with pad names, vias, track length, narrow segments, and checks of the result file.
- A toolbar button for the editor that sends the open PCB and opens the result as a new PCB.

## Requirements

- Windows, Linux or macOS, 64 bit.
- An internet connection for the setup. Routing needs none.
- About 300 MB of disk space.
- About 1.1 GB of free memory for each engine run. The bridge starts fewer runs when memory is short.
- Java 25 or later for the engine. The setup script downloads a Java runtime into the folder when none is installed.

## Install

1. Download the release file `easyeda-freerouting-bridge-<version>.zip` from the Releases page of this repository. Unpack it to a folder where you can write.
2. Run the setup script once: `setup.bat` on Windows, `./setup.sh` on Linux and macOS.
   It downloads the routing engine (65 MB) from the Freerouting project and checks the file against a fixed checksum.
   Nothing is installed outside the folder.

To build `bridge.jar` from this repository: see "Build from source".

## Use

**Auto Router dialog.**

1. Close the old EasyEDA auto router if it runs. Both use port 3579.
2. Start the bridge: `run.bat` on Windows, `./run.sh` on Linux and macOS. Keep the window open.
3. In the editor open the PCB. Choose **Route > Auto Router**. Set **Router Server** to **Local**. Click **Run**.

**Board file.**

1. In the editor choose **File > EasyEDA File Source**. Download the source as a `.json` file.
2. Route it: drop the file on `route.bat`, or run `./route.sh board.json`.
3. The result is `board.routed.json`, with the report `board.routed.report.txt`. The input file does not change.
4. In the editor choose **File > Open > EasyEDA Source** and select the result. It opens as a new PCB.
5. Rebuild the copper areas with **Shift+B**. Run the design rule check. Route the open connections by hand.

The toolbar button of `easyeda/extension` does steps 1 to 4 from inside the editor.

Route critical nets by hand first, for example an antenna feed or a crystal. The bridge keeps existing tracks. See "Existing tracks" in the user guide.

Full instructions: [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

## Examples

The folder [examples](examples) has three board files with the result and the report of one job for each. Boards A and B are the boards of the results above.

| Folder | Board | Use it for |
|---|---|---|
| `examples/small` | 55 parts, 4 layers, a cut-out of board A | A first test. The job takes one to two minutes |
| `examples/board-a` | 134 parts, 4 layers, rules 0.15 mm | A dense board that does not route completely |
| `examples/board-b` | 133 parts, 4 layers, rules 0.10 mm, antenna at 45 degrees | A board that routes completely |

First test after the setup: `./route.sh examples/small/small.json`, or drop that file on `route.bat`.
The board designs have their own terms of use. See [examples/README.md](examples/README.md).

## Configuration

| File | Content |
|---|---|
| `config/rules.json` | Routing rules: vias, planes, copper areas, layer costs, narrow tracks, keep-outs, part moves, job time. Read for each job. Reference: [docs/RULES.md](docs/RULES.md) |
| `config/main.json` | Program settings: port, number of engine runs, memory, time shares, input repair. Read at the start. Reference: "Settings" in the [user guide](docs/USER_GUIDE.md#settings) |

## Documentation

| File | Content |
|---|---|
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) | Setup, the two interfaces, what the bridge does with a board file, parallel runs, settings, what to do when a job fails |
| [docs/RULES.md](docs/RULES.md) | Every key of the rules file |
| [docs/TEST_REPORT.md](docs/TEST_REPORT.md) | What was tested, with results, and what was not tested |
| [docs/DEVELOPMENT_TEST_LOG.md](docs/DEVELOPMENT_TEST_LOG.md) | The measurements of each version during development |
| [examples/README.md](examples/README.md) | The example boards and their results |
| [CHANGELOG.md](CHANGELOG.md) | Changes of each version |
| [NOTICE.md](NOTICE.md) | Credits and licenses of the parts |

## Limits

- The bridge is an auto router. It counts connections, vias and track length. It does not know return paths, loop areas, matched lengths or the needs of a radio feed.
- All vias go through the board. No blind and buried vias, no differential pairs, no length matching.
- Tracks run at 0, 45 and 90 degrees.
- The editor builds the fill of the copper areas. The bridge works with a model of the fill. After the rebuild in the editor, look for connection lines that stay visible.
- The board file interface was tested on two board designs of one designer. See the test report for the full list of limits.

## Build from source

`./build.sh` builds `bridge.jar` from `src/`. It needs a JDK 17 or later. The bridge has no dependencies.

The folder `test/bridge` has command line tools for single steps of the bridge, for example the track clean-up on a routed file. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Credits

**Routing engine: Freerouting.**
Alfons Wirtz wrote the router and published it under the GNU General Public License version 3.
The Freerouting project maintains and develops it: <https://github.com/freerouting/freerouting>.
The bridge runs the unmodified release Freerouting 2.5.0 as a separate program. All routing is done by that engine.

**Bridge: customization, feature development, testing, integration testing and productization.**
Valentine Petrov, INMECHA INC.

INMECHA INC
245 S Spruce Ave
South San Francisco, CA 94080
USA

Details, versions and checksums: [NOTICE.md](NOTICE.md).

## License

Copyright (C) 2026 Valentine Petrov, INMECHA INC.

This program is free software. You can redistribute it and modify it under the terms of the GNU General Public License as published by the Free Software Foundation, version 3 of the License or any later version.
This program comes without any warranty. See [LICENSE](LICENSE).

EasyEDA is a trademark of its owner. This project is not affiliated with EasyEDA, with JLCPCB or with the Freerouting project.
