# Development test log

This file keeps the measurements that were made while the bridge was developed, in the order of the versions.
Each record names the version that it was made with. Later versions changed some of the behaviour that a record describes.
The current state is in the [test report](TEST_REPORT.md).

- Test board A: 134 parts, 539 pads, 4 layers, track and clearance 0.15 mm, one part with 94 pads at 0.4 mm pitch, antenna parallel to the board edges.
- Test board B: 133 parts, 509 pads, 4 layers, track and clearance 0.10 mm, one part with 115 pads at 0.40 mm pitch, antenna at 45 degrees.
- Test boards with a number: the 27 recorded requests of the editor channel. See the test report.
- "The test board" without a letter is test board A.

Bench machine: 2 processors, slower than a current laptop. 2 engine runs for each job when no other number is given.
The engine does not repeat its result. A difference of a few connections between two jobs is not a finding.

**Board file interface.** One board design: 134 parts, 539 pads, 4 layers, track and clearance 0.15 mm, one part with 94 pads at 0.4 mm pitch.
The routes were removed (`existing` = `remove`). 368 connections were open at the start. Job time 300 s.

Open connections are counted with the fill model of the copper areas.

| Rules | Open connections | Vias in pads |
|---|---|---|
| Version 1.2.0 default: supply area trusted as a plane | 18 (6 signal, 12 supply net) | 74 |
| Default of version 1.2.1: supply net routed with tracks | 10 and 11 in two jobs | 74 |
| `via.plane_pads` = `all` | 0 in one job, both runs | 183 |
| `planes.supply` = `plane` with `layers.supply_plane_track_cost` = 4.0 | 13 | 74 |

Versions 1.2.2 and 1.2.3 on the same board, default rules of each version. Ground tracks and supply tracks are the tracks of the nets GND and VDD_3V3 that the router made. The board has ground areas on the top, bottom and first inner layer and a supply area on the second inner layer. The count of 259 open connections at the start is lower than 368, because the fill of the ground areas joins the pour pads before routing.

| Version | Job time | Open connections | Vias | Vias in pads | Ground tracks | Ground vias | Supply tracks |
|---|---|---|---|---|---|---|---|
| 1.2.1 | 300 s | 11 (a second job: 10) | 256 | 74 | 159 with 72 mm | 93 | 111 with 149 mm |
| 1.2.2 | 300 s | 5 (2 ground, 3 signal) | 233 | 85 | 9 with 6 mm | 59 | 111 with 143 mm |
| 1.2.2 | 600 s | 2 (2 signal) | 243 | 85 | 11 with 10 mm | 61 | 116 with 146 mm |
| 1.2.3 | 300 s | 6 (3 ground, 3 signal) | 239 | 86 | 8 with 8 mm | 37 | 9 with 6 mm |
| 1.2.3 | 600 s | 4 (3 ground, 1 supply, 0 signal) | 229 | 89 | 9 with 8 mm | 38 | 10 with 8 mm |
| The reference board with its own routes | | 0 | 348 | | 51 with 25 mm | 112 | 87 with 33 mm |

- Version 1.2.3, plan for this board: 102 pour pads, 52 pads with a via into an area on a different layer, 31 pads with a via because they have no exit, 3 pads where the via does not fit and the engine makes the connection. 8 pads lie in a ground area but have fill beside less than 30 % of their outline.
- Version 1.2.3, 600 s: the job ended after 483 s. The 4 open connections are 3 ground pour pads that tracks cut off (U1.A5, R49.2, C14.2) and one supply pad whose via stands on a cut-off piece of the supply area (C14.1). All signal nets are complete.
- Version 1.2.3, 300 s: the best result came from the first pass. The second pass gave the same count.
- Version 1.2.2, 300 s: the first pass ended after 234 s with 12 open connections in its best run. The second pass put a via into 9 pads, took out 14 tracks of other nets and ended with 5.
- Version 1.2.2, 600 s: the first pass ended with 8 open connections. The second pass put a via into 7 pads, took out 15 tracks and ended with 2. Both are signal tracks that were taken out for a via and that the engine did not route again between the fixed tracks.
- Each job of version 1.2.2 was run two times. The second job gave the same result as the first.
- On this machine 300 s is too short for this board. The first pass was not at its end when the time for the further passes started.
- A test of version 1.2.2 with the first pass cut at 240 s and a job time of 600 s ran four passes: 12, 7, 6 and 5 open connections.
- Tried and not used: the tracks of the pass before not fixed in a further pass (more open connections after 100 s), and a via in a pad of a signal net that the engine did not connect (10 open connections in place of 5 in the 300 s job).

One job of version 1.2.3 by Valentine Petrov, in the editor on the web with the toolbar button, on a machine with 16 logical processors, 4 runs, 300 s:

- The button sent the board, showed the progress and opened the result as a new PCB.
- The report had 10 open connections. After the rebuild of the copper areas the editor showed 10 connection lines.
- The same board through the Auto Router dialog on that machine: 64 open connections, 55 after the rebuild of the copper areas by the count of the fill model. The dialog sends no copper areas.
- Runs 3 and 4 of that job were copies of runs 1 and 2. This fault is corrected in version 1.2.4.

Version 1.2.4 with 4 runs at the same time on the machine with 2 processors, job time 600 s: see the table of the runs in "Parallel runs" in the [user guide](USER_GUIDE.md#parallel-runs).

One job of version 1.2.4 by Valentine Petrov with the default job time of 600 s. The board had new settings of the copper areas: clearance 0.15 mm in place of 0.254 mm, and direct pad connection on the top layer. The result board came back with the fill that the editor had built:

- All signal nets were complete. The report had 8 open connections, all in the ground and supply areas.
- The fill of the editor leaves 12 connections open. The model of the bridge joined 5 pads that this fill does not join.
  Cause: the model removed parts of the fill below 0.10 mm width. The fill of the editor has no part below 0.19 mm. With 0.19 mm the model gives the same 14 open pads as the fill of the editor.
- No further pass ran. The first pass and the result files of 4 runs used 561 s of the 600 s. 6 of the open pads could have taken a via in a further pass.

Version 1.2.5 corrects both points. The same board with its routes removed, 4 runs at the same time on the machine with 2 processors, job time limit 1200 s:

| | Version 1.2.4, job in the editor | Version 1.2.5, test job |
|---|---|---|
| Open connections by the fill of the editor or by the corrected model | 12 (11 ground, 1 supply) | 3 (2 ground, 1 signal) |
| Open connections in the report | 8 | 3 |
| Further passes | none | 2, the job ended after 956 s |
| Vias | 230 | 230 |
| Track pieces narrower than the track rule | 54 | 45 |

Track clean-up of version 1.2.6 on four routed results of the same board. The first is the result of the editor job with version 1.2.4:

| Result | Track records | Narrow segments | Small steps removed | Side steps removed |
|---|---|---|---|---|
| Job in the editor, version 1.2.4 | 680 to 402 | 54 to 19 | 5 | 24 |
| Test job, version 1.2.5 | 654 to 394 | 45 to 18 | 0 | 15 |
| Sample, version 1.2.3 | 654 to 397 | 66 to 22 | 6 | 34 |
| Sample, version 1.2.2 | 800 to 468 | 58 to 19 | 15 | 39 |

- In all four results the checks are the same before and after the clean-up: open connections with the fill model, clearance errors (0), copper outside the board (0).
- An independent check gives the same pad groups by tracks and vias for all 143 nets, and the same open pads with its own fill model.
- The smallest clearance of a track with the full width is 0.1515 mm before and after the clean-up. The rule of the board is 0.1500 mm.
- The largest shift of a segment was 0.010 mm.
- Side steps that stay in the first result: 17 lie between two pads or vias that are not on one line. 10 have no room.
- One complete job with the clean-up (board with routes removed, 4 runs on the machine with 2 processors, limit 600 s): 656 track pieces became 396 records. 43 narrow segments became 18. The checks of the result were the same as before the clean-up.
- The design rule check of the editor was not run on a cleaned file.

Narrow tracks, version 1.2.10, on the machine with 2 processors, 4 runs, 600 s.

Test board B (design rules 0.10 mm):

| | Job in the editor, version 1.2.9 | `avoid`: no run with narrow tracks | Version 1.2.10, default (`mixed`) |
|---|---|---|---|
| Open connections | 0 | 0 | 0 |
| Narrow segments in the result | 13, smallest 0.050 mm, 9.4 mm | 0 | 0 |
| Vias | 195 | 210 | 213 |
| Track length | 988 mm | 968 mm | 1017 mm |
| Job time | 306 s on 16 processors | 411 s | 332 s |

- With `mixed`, run 4 was the run without narrow tracks. It had 0 open connections after the first pass and was taken. The other three runs had 1, 3 and 2 open connections and 31, 27 and 33 narrow segments.
- The check tool that is independent of the bridge: 0 open connections, no track below 0.100 mm, and no clearance error apart from the 2 contacts at the antenna.
- The changed clean-up on the result of the job in the editor: 13 narrow segments became 7, and 9.4 mm became 5.1 mm. 6 segments got the full width in their position. Two of them were 2.5 mm and 0.4 mm long and had 0.0002 mm to spare.
  The 7 segments that stay are short of the full width by 0.008 mm to 0.022 mm. The independent check found no new clearance error.

Test board A (rules 0.15 mm, one part with 94 pads at 0.4 mm pitch):

| | Version 1.2.9 (`repair`) | `avoid` | Version 1.2.10, default (`mixed`) |
|---|---|---|---|
| Open connections | 2 | 12 | 3 |
| Narrow segments in the result | 20, 6.0 mm | 0 | 14, 6.7 mm |

- On this board no run without narrow tracks comes near to a complete result. The job keeps the result of a normal run.
  The job has one normal run less than before. This is the cost of `mixed` on a dense board. `track.narrow` = `repair` gives the run list of version 1.2.9.
- The changed clean-up on the result of version 1.2.9: 20 narrow segments became 15, and 6.0 mm became 2.3 mm.

Antenna keep-out, version 1.2.9, on test board B (133 parts, design rules 0.10 mm, antenna at 45 degrees), on the machine with 2 processors, 4 runs, 600 s:

| | Rectangle (1.2.8) | Hull (1.2.9), job 1 | Hull (1.2.9), job 2 |
|---|---|---|---|
| Pads with no exit and no room for a via, in the plan | 5 | 0 | 0 |
| First pass, open connections of runs 1 to 4 | 17, 15, 15, 16 | 3, 1, 0, 3 | 3, 1, 0, 3 |
| Result of the job, open connections | 10 | 0 | 0 |

- Job 2 was made with the packaged program. It gave the same four runs as job 1. Each run ended before the time limit, and the job took 265 s.
- The check tool that is independent of the bridge counts the same open connections on each result. Clearance at 0.10 mm: the 2 contacts of the antenna tracks with the antenna copper, and no other error.
- Job 1: the new copper nearest to the antenna copper, apart from the two antenna nets, is 1.09 mm away. No track is on the far side of the antenna.
- Test board A has an antenna parallel to the board edges: hull 79 mm2, rectangle 84 mm2. Job with 600 s and version 1.2.9: 2 open connections. Both are ground pads in the copper area that cannot take a via.

Part moves, version 1.2.9:

- The move of a footprint record was run on every part of three boards (322 parts), as a shift and as a turn by 180 degrees with a shift.
  Checked with the board reader: each pad of the part at the expected place with the same size, all other pads unchanged, the same count of shapes and fields, and the move back gives the first positions.
- Search for moves on 7 saved first-pass results of the two boards. 31 open ground pads were on parts with 2 pads that the rules permit to move.
  2 pads got a move, of 0.025 mm and 0.050 mm. Each of the two results then had 1 open connection less. 13 pads: the second pad of the part has a via. 16 pads: no shift up to 0.15 mm makes room for a via.
- One job from a saved first pass with one run and 3 open connections, with and without `parts.move`. With it, one part moved by 0.025 mm and the first pass had 2 open connections.
  Both jobs ended with 0 open connections after the further pass. The further pass takes out the tracks in the way of a via, so it solves the same case without a move.
- The result file with the moved part: only that footprint record differs from the input, and both pads have the same shift. Independent check: 0 open connections and no new clearance error.
- No job on the two boards had a better result with `parts.move` = `on`. This is why the default is `off`.
- Not built: moves larger than a pad with a new engine pass, and moves before the first pass. With the corrected keep-out, no signal pad stays open in every run on the two boards. So these moves have no test case.

Mixed job and routes of other runs, version 1.2.8, on test board B (133 parts, design rules 0.10 mm), on the machine with 2 processors:

- A job with the permission for all four runs (`via.on_pads` = `all`) against a job without it (`needed`), 600 s each: 104 vias in pads against 76, 190 vias against 210, 799 mm of track against 867 mm, 14 open connections against 10. Under the part with 0.40 mm pad pitch the small vias in neighbour pads leave 0.151 mm, and a track with its clearance needs 0.30 mm. The job with the permission had 43 such pairs of vias at this part, the other job 30.
- One mixed job, 600 s. First pass: 17 open (73 vias in pads), 17 (107, with the permission), 13 (73), 15 (112, with the permission). Result after the further pass: 9 open, from the run with 13.
- Routes of other runs, first form (not in this version): the route of the open net alone, the tracks in its way taken out and routed again by the engine. 10 passes on results of that job. 4 of them ran beside a pass without routes on the same result: each ended with 1 to 4 more open connections than that pass. 6 ran on results of a further pass: 2 ended with 1 open connection less than the result before, 4 with 2 to 7 more. The engine routed again only a part of the tracks that were taken out. With time for 5 to 11 engine passes the outcome was the same.
- Routes of other runs with closed groups, where tracks of nets with copper areas could be taken out (not in this version): 1 pass, 13 open against 11 for the same result without the group. Two connections of the supply net were lost.
- Routes of other runs as in this version (closed groups, no track taken out): 1 pass on a result with 17 open, two groups of 1 net each. It ended with 10 open, the pass on the same result without the groups with 11.
- For the best result of that job (9 open after the further pass) no group could be taken. 6 of its open connections are open in every run. For the other nets, each group from another run had a route too near to a via in a pad that the further pass had added, or grew to more than 40 nets.

Fill model of version 1.2.7 against the fill of the editor, on test board B (133 parts, copper areas with 0.10 mm clearance, before routing): the fill of the editor leaves 2 ground pads open. The model leaves the same 2 pads open. On the routed result of that job the model and the independent fill model give the same 2 open ground pads.

Function tests of version 1.2.3 on the cut-out with 55 parts, with the ground areas changed:

| Ground areas | Plan | Result after 70 s |
|---|---|---|
| Bottom layer only | 36 ground pads on the top layer get a via into the bottom area. 8 pads are pour pads. 3 pads cannot take a via | 0 open connections, 58 vias, 45 of them in pads |
| Top layer only | 44 pour pads. 3 pads have too little contact with the fill. The engine routes them to one pour pad | 7 open connections, all ground pour pads that tracks cut off. The net has no second layer for a repair |

Notes on the first table:

- The first row is the result that the editor showed as failed on the supply net. Version 1.2.0 reported 6 for it.
- Without any via for plane pads (`via.plane_pads` = `none`) a job of version 1.2.0 left 43 signal connections open.
- The reference board itself, as delivered with its routes, has 0 open connections in the model.
- These comparisons of version 1.2.0 were counted without the fill model and were not repeated: `layers.costs` = `engine`, `via.on_pads` = `all`, `via.plane_stubs` = `on`.

Function tests on a cut-out of that board with 55 parts: a layer limit for nets, a width for a net, wider tracks after routing, a net that is not routed,
a keep-out box and a pad list for vias. Each rule had its effect in the result file.

A second program with its own geometry code and its own fill model checked each result file. It found:
the same open connections, no clearance error of the new copper, no copper outside the board, unique ids,
and all footprints and all blocks outside `shape` equal to the input.
Two tracks touch the antenna copper at the two antenna pads. This contact is the connection. The design rule check of the editor can show it.

**Editor channel.** The same machine and the same boards with the settings of version 1.1 and with the default rules of version 1.2. Job time 300 s.

| Board | Connections | Open with 1.1 settings | Open with 1.2 rules |
|---|---|---|---|
| The board of the file test, as the Auto Router dialog sends it | 392 | 75 | 29 |
| Test board 39, 4 layers | 276 | 12 | 2 |
| Test board 18, 8 layers | 80 | 28 | 0 |
| Test board 21, 2 layers, 641 pads | 463 | 0 | 0 |

All 27 recorded editor requests were replayed with the default rules of version 1.2 and a job time of 100 s. This is one third of the time that the editor gives.

- Every result was checked by geometry. The count of the bridge was correct on all 27 boards.
- 20 of 27 boards were fully routed in 100 s. The 15 boards that version 1.1 completed in less than 100 s were complete again.
- The 7 other boards are large or hard. Two of them are in the table above with 300 s.

Vias on boards that both versions complete, 300 s: test board 4: 8 and 8. Test board 7: 27 and 27. Test board 20: 18 and 11. Test board 21: 106 and 98.
Run 2 with the basic settings gives this. With the rules in every run, test board 4 had 25 vias.

All 27 recorded editor requests with version 1.2.8 and with the original EasyEDA router 0.8.11. Same machine, same day, 300 s, the same check by geometry for both. Version 1.2.9 does not change the editor channel.

| | Original 0.8.11 | Bridge 1.2.8 |
|---|---|---|
| Boards fully routed, of 27 | 12 | 24 |
| Boards with no result | 3 | 0 |
| Open connections, of 4148 | 1001 | 9 |
| Connections routed | 75.9 % | 99.8 % |
| Vias on the 12 boards that both complete | 251 | 295 |
| Track length on these 12 boards | 23093 mm | 22874 mm |
| Sum of the job times on these 12 boards | 101 s | 804 s |

- The 1001 open connections of the original have 624 on the 3 boards without result.
- Open with the bridge: 6 on a board that has 32 pad pairs closer than the clearance rule, 1 on a second board, 2 on a board with 4 layers at the time limit.
- 38 of the 44 added vias are on one board: 69 against 31. Run 1 was complete after 76 s. The early stop rule then stopped run 2, which gives fewer vias, after 119 s.
- The count of the bridge was equal to the check on all 27 boards.

For comparison, the test of version 1.1 on a faster machine with 300 s: 23 of 27 boards fully routed, 98.9 % of the connections.
The original EasyEDA router 0.8.11: 12 of 27 boards, 75.8 %.
