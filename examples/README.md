# Example boards

Three board files of EasyEDA Standard, each with the result and the report of one job.
Boards A and B are the two boards of the [test report](../docs/TEST_REPORT.md).

| Folder | Board | Connections to route | Stored result |
|---|---|---|---|
| `small` | 55 parts, 179 pads, 4 layers, track and clearance 0.15 mm. A cut-out of board A | 82 | 0 open connections, 38 vias, job time 105 s |
| `board-a` | 134 parts, 539 pads, 4 layers, track and clearance 0.15 mm, one part with 94 pads at 0.4 mm pitch | 259 | 3 open connections, 14 narrow segments, 232 vias, job time 584 s |
| `board-b` | 133 parts, 509 pads, 4 layers, track and clearance 0.10 mm, one part with 115 pads at 0.40 mm pitch, antenna at 45 degrees | 221 | 0 open connections, 0 narrow segments, 213 vias, job time 341 s |

## Files

| File | Content |
|---|---|
| `<name>.json` | The board before routing. This is the PCB source of the editor |
| `<name>.example-result.json` | The result of one job with version 1.3.0 and the default rules |
| `<name>.example-report.txt` | The report of that job |

The stored jobs ran on a Linux machine with 2 processors, with 4 engine runs at the same time and a job time limit of 600 s.
A faster machine needs less time. The engine does not repeat its result exactly, so your result can differ by some connections, most of all on board A.

## Route an example

1. Run the setup script once. See the [user guide](../docs/USER_GUIDE.md).
2. Route the small board: `./route.sh examples/small/small.json`. On Windows drop the file on `route.bat`.
3. The result is `small.routed.json` beside the input, with the report `small.routed.report.txt`. Version control ignores these two files.
4. In the editor choose **File > Open > EasyEDA Source** and select the result. Rebuild the copper areas with **Shift+B**.

To see the plan of the bridge for a board without routing: `java -jar bridge.jar check examples/board-b/board-b.json`.

## What each board shows

**small.** A fast board for a first test and for a test after a change of the program. It has copper areas for ground on three layers and for one supply net. So it uses the pads in copper areas, the vias in pads and the repair after routing.

**board-a.** A dense board that the bridge does not route completely. The routes were removed from the design for this file.
The open connections of the stored result are pads of the ground net and of the supply net that tracks cut off from their copper area.
The antenna is parallel to the board edges. Use this board to compare settings, for example `track.narrow` = `repair` against `mixed`.

**board-b.** A board that routes completely. 18 vias that were in the file stay in place.
The antenna stands at 45 degrees. The plan in the report shows the closed area around it: 81 mm2, where the rectangle around the antenna has 198 mm2.
The run without narrow tracks is the chosen run of the stored result.
The stored result has a track of the bridge for the antenna feed. In a real design route such a net by hand before the job.

## Terms of use of the board designs

The board designs in this folder are by Valentine Petrov, INMECHA INC.

Copyright (C) 2026 Valentine Petrov, INMECHA INC.

You can use the files to test and to develop the bridge. No other right to the board designs is given.
The license of the program does not cover the board designs.
