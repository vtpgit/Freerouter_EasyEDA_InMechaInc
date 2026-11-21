# Test report

Version 1.3.0. The routing behaviour of this version is the same as in version 1.2.10.

The measurements of each earlier version are in the [development test log](DEVELOPMENT_TEST_LOG.md).

## Test set-up

| | Bench tests | Integration tests |
|---|---|---|
| By | Automated | Valentine Petrov, INMECHA INC |
| Machine | Linux, 2 processors, about half the speed of the laptop | Windows laptop, Intel Core i7-1260P, 16 logical processors, 16 GB |
| Editor | None. Recorded requests of the editor, and board files | EasyEDA Standard in the browser |
| Engine runs for each job | 2, or 4 where stated | 4 |

Bench tests:

- Editor channel: the requests that the editor sent are replayed through the websocket interface. A geometry check that is independent of the bridge counts the open connections of each result.
- Board file: jobs through the command and through the websocket request of the toolbar button. A second check tool reads each result file and counts open connections, clearance errors and track widths.
- Three test suites run on each version: the protocol of the editor channel, the board file requests, and the script of the toolbar button with a stand-in for the editor functions.

Integration tests in the editor:

- The Auto Router dialog with the bridge as the local router.
- The toolbar button of the extension: it sent the board, showed the progress and opened the result as a new PCB.
- Board file jobs with the versions 1.2.3, 1.2.4, 1.2.6 and 1.2.9 on two boards of the tester's own design.
- Each result opened in the editor, copper areas rebuilt, connection lines counted against the report, and the tracks and vias reviewed against the design rules and the limits of the board maker.

The integration tests found these faults, and each one led to a correction:

| Finding of the integration tests | Correction |
|---|---|
| The editor showed open connections of a supply net that the report did not have | Model of the fill of the copper areas (1.2.1) |
| Ground tracks and vias at pads that the top copper area already connected | No track and no via for a pad in a copper area of its net (1.2.2, 1.2.3) |
| 12 open connections by the fill of the editor against 8 in the report | Smallest width of the fill in the model, measured on the fill of the editor (1.2.5) |
| Tracks in many pieces with small steps | Track clean-up (1.2.6) |
| Vias of 0.4 mm where the design rules have 0.3 mm | Track width, clearance and via size from the design rules of the file (1.2.7) |
| Open connections at pads near an antenna at 45 degrees | Antenna keep-out by the shape of the copper (1.2.9) |
| Track segments of 0.075 mm and 0.050 mm at a rule of 0.100 mm | One run of four without narrow tracks, and a stronger repair of narrow segments (1.2.10) |

The engine does not repeat its result exactly. A difference of a few connections between two jobs is not a finding.

## Editor channel

27 recorded requests of the Auto Router dialog: 2 to 8 layers, 45 to 635 connections for each board, 4148 connections in total.
The bridge and the EasyEDA local auto router 0.8.11 ran on the bench machine on the same day, with 300 s for each board. The bridge had 2 engine runs for each job.

| | EasyEDA local router 0.8.11 | Bridge |
|---|---|---|
| Boards fully routed, of 27 | 12 | 24 |
| Boards with no result | 3 | 0 |
| Open connections, of 4148 | 1001 | 9 |
| Connections routed | 75.9 % | 99.8 % |
| Vias on the 12 boards that both complete | 251 | 295 |
| Track length on these 12 boards | 23093 mm | 22874 mm |
| Sum of the job times on these 12 boards | 101 s | 804 s |

- The 1001 open connections of the original router have 624 on the 3 boards without result.
- Open with the bridge: 6 on a board that has 32 pad pairs closer than the clearance rule, 1 on a second board, 2 on a board with 4 layers at the time limit.
- 38 of the 44 added vias are on one board. One run was complete after 76 s. The early stop rule then stopped the run that gives fewer vias.
- The bridge needs more time on a simple board: 12 s to 20 s where the original router needs 1 s.
- The count of the bridge was equal to the independent check on all 27 boards.
- This test was made with version 1.2.8. Later versions did not change the editor channel.

Where the gain comes from, in the order of size: the repair of the board text that the editor sends, the newer engine, the parallel runs, and the rules for vias on pads and layer costs.

## Board file interface

Two boards with 4 layers, with copper areas for ground and for one supply net. Both board files are in the folder `examples` of the repository.

| | Board A | Board B |
|---|---|---|
| Parts and pads | 134 and 539 | 133 and 509 |
| Design rules: track, clearance | 0.15 mm, 0.15 mm | 0.10 mm, 0.10 mm |
| Densest part | 94 pads at 0.4 mm pitch | 115 pads at 0.40 mm pitch |
| Antenna | Parallel to the board edges | Meander at 45 degrees |
| Connections to route | 259 | 221 |

Bench machine, 4 engine runs, job time 600 s, default rules:

| | Board A | Board B |
|---|---|---|
| Open connections | 3 | 0 |
| Track segments below the track rule | 14, with 6.7 mm in total | 0 |
| Smallest track width | 0.075 mm | 0.100 mm |
| Vias | 232 | 213 |
| Job time used | 587 s | 332 s |

- Board B: the independent check counts 0 open connections and no track below 0.100 mm. Its only clearance findings are the two tracks that end on the antenna copper at the antenna pads. This contact is the connection.
- Board A: the 3 open connections are pads of the ground net and of the supply net that tracks cut off from their copper area.
- Board A through the Auto Router dialog in the editor, with 300 s and version 1.2.3: 55 open connections after the rebuild of the copper areas. The board file interface on the same laptop with the same time: 10. The editor showed 10 connection lines.

### Measurements of single functions

| Function | Measurement |
|---|---|
| Antenna keep-out | Board B with `keepout.antenna_shape` = `box`: 10 open connections. With `hull`: 0. The rectangle has 198 mm2, the hull 81 mm2, the antenna copper 21 mm2 |
| Narrow tracks | Board B with `track.narrow` = `repair`: 0 open connections and 13 narrow segments down to 0.050 mm. With `mixed`: 0 and 0. With `avoid`: 0 and 0 |
| Narrow tracks | Board A with `repair`: 2 open connections and 20 narrow segments. With `mixed`: 3 and 14. With `avoid`: 12 and 0 |
| Track clean-up | Four results of board A: 680, 654, 654 and 800 track records became 402, 394, 397 and 468. Narrow segments: 54, 45, 66 and 58 became 19, 18, 22 and 19. Open connections and clearance did not change |
| Design rules as the source | Board B has design rules of 0.10 mm, 0.10 mm and a via of 0.30 mm. The stored values of the Auto Router dialog were 0.15 mm, 0.15 mm and 0.40 mm |
| Part moves | 31 open ground pads on movable parts in 7 results. 2 pads got a move, of 0.025 mm and 0.050 mm. No job had a better result with `parts.move` = `on`. This is why the default is `off` |
| Move of a footprint | Every part of three boards (322 parts) was moved and read again. Each pad of the part was at the expected place, and all other pads were unchanged |

## What was not tested

- The script `easyeda/route-with-bridge.js` in the script dialog of the editor. It has the same code as the toolbar button, which was run in the editor.
- The results of version 1.2.10 and later in the editor. The last integration test used version 1.2.9.
- macOS. Linux was used for the bench tests only. The integration tests ran on Windows. A separate test of each script on each system was not recorded.
- Boards of other designers on the board file interface. It was run on two board designs of one designer.
- A pad in a copper area has no track. Its connection is the fill that the editor builds. The bridge works with a model of that fill.
  The smallest width of the fill in the model (0.19 mm) is from one board file. It is not known how this width changes with the settings of the copper area.
- A net whose copper areas are on outer layers only, on a board that was designed in that form.
- `existing` = `keep` with a partly routed board. With a fully routed board it gave no result.
- 4 runs at the same time on the bench machine share 2 processors. The laptop of the integration tests has 16 logical processors.
- Part moves in the editor. The moved footprint was checked with the readers of the bridge and of the check tool only.
- The antenna keep-out by hull was run on two boards with one antenna footprint each.
- Design rule check of the editor on a result with the track clean-up of version 1.2.10. The clean-up was checked with the independent check tool.

## What the bridge does not do

- Blind and buried vias. All vias go through the board.
- Differential pairs and length matching.
- Placement of parts. The only move of a part is the small shift of `parts.move`.
- The fill of copper areas. The editor builds it. The model of the bridge has a grid of 0.025 mm. The fill of the editor can differ at narrow positions and at the spokes of a pad.
- Tracks at any angle. The engine routes at 0, 45 and 90 degrees.
- The editor channel cannot carry copper areas, keep-outs or a second via size. The track clean-up and the repair of narrow segments work on the board file interface only.
- A via on a pad on the editor channel is the standard via of the board. A board with such vias needs filled and capped vias. To forbid them set `via.on_pads` to `off`.
- A track segment stays narrow where the full width does not fit, also after a shift of 0.015 mm. The report lists these segments. `track.narrow` = `avoid` makes none and can leave more connections open.
- The router does not fix a board that breaks its own rules. If pads are closer than the clearance rule, the bridge window says so.
- Electrical rules. The bridge does not know return paths, loop areas or the needs of a radio feed. Route such nets by hand.
