# User guide

## What you need

- Windows, Linux or macOS, 64 bit.
- An internet connection for the setup. Routing itself needs none.
- About 300 MB of disk space.
- About 1.1 GB of free memory for each engine run. The bridge starts fewer runs when memory is short.

## Setup

1. Unpack the folder to a place where you can write, for example your Documents folder.
2. Run the setup script once.
   - Windows: double-click `setup.bat`.
   - Linux and macOS: run `./setup.sh` in a terminal.
3. The script downloads the routing engine (65 MB) into `engine`. It checks the file against a fixed checksum.
   If Java 25 or later is not installed, it also downloads a Java runtime (about 50 MB) into `runtime`.
   Nothing is installed outside this folder. To remove everything, delete the folder.

Update from an older version: copy the new files over the old folder. Keep `engine` and `runtime`. The setup script is not needed again.
Compare your `config/rules.json` with the new file, because default values can change between versions. See `CHANGELOG.md`.

## Interface 1: the Auto Router dialog of the editor

1. Close the old EasyEDA auto router if it runs. Both use port 3579.
2. Start the bridge.
   - Windows: double-click `run.bat`.
   - Linux and macOS: run `./run.sh`.
3. Keep the window open. It shows the progress of each job.
4. In the EasyEDA editor open the PCB. Choose **Route > Auto Router**. Set **Router Server** to **Local**. Click **Run**.
5. The editor draws the tracks when the job ends. A job takes from 5 seconds to the full time limit of 5 minutes.

The editor shows no intermediate tracks during the job. Progress is in the bridge window.

On a laptop, route on mains power and keep the bridge window visible. See "Parallel runs".

If the editor does not find the local router:

- Chrome and Edge: use a current version.
- Firefox: open `about:config`. Set `network.websocket.allowInsecureFromHTTPS` to `true`. Restart Firefox.
- Open http://127.0.0.1:3579/api/whois in the browser. The answer must be `EasyEDA Auto Router`.

What the rules file changes on this interface: see the column "Editor channel" in the [rules file reference](RULES.md).

## Interface 2: route a board file

The board file is the PCB source of EasyEDA Standard. It is a JSON file with the blocks `head`, `shape`, `layers`, `DRCRULE` and `routerRule`.

### With the command

1. In the editor open the PCB. Choose **File > EasyEDA File Source**. Download the source as a `.json` file.
2. Route the file.
   - Windows: drop the file on `route.bat`. Or run `route.bat board.json` in a command window.
   - Linux and macOS: run `./route.sh board.json`.
3. The result is `board.routed.json` in the folder of the input file. The input file does not change.
   The report is `board.routed.report.txt`. The bridge also prints it.
4. In the editor choose **File > Open > EasyEDA Source**. Select the result file. It opens as a new PCB.
5. Rebuild the copper areas with **Shift+B**. Run the design rule check. Route the open connections by hand.

Options of the command:

| Option | Meaning |
|---|---|
| `route board.json result.json` | A different name for the result |
| `--time 600` | Job time in seconds. Without it: `time.job_s` of the rules file |
| `--rules my-rules.json` | A different rules file for this job |

To see what the bridge reads from a file and what it plans, without routing: `java -jar bridge.jar check board.json`.

### With the script in the editor

The folder `easyeda` has a script that sends the open PCB to the bridge and opens the result as a new PCB.
The toolbar button of the extension was run in the editor. The script was not run in the script dialog. Both have the same code. See the [test report](TEST_REPORT.md).

1. Start the bridge (`run.bat` or `run.sh`).
2. Open the PCB in the editor.
3. Open the script dialog of the editor (**Advanced > Extensions**). Paste the content of `easyeda/route-with-bridge.js`. Run it.
4. A box in the lower right corner shows the progress and then the report. The result opens as a new PCB.
5. Rebuild the copper areas with **Shift+B**. Run the design rule check.

For a toolbar button: load the two files of `easyeda/extension` with **Advanced > Extensions > Extensions Setting > Load Extension**.
Type the name `frbridge`. Close the editor and open it again.

If the script reports that it cannot read the source, use the command.

### Track width, clearance and via size of a board file

The board file has two blocks with these values:

| Block | Dialog of the editor | Use in the editor |
|---|---|---|
| `DRCRULE` | Design Rule dialog | The design rule check |
| `routerRule` | Auto Router dialog (menu Route, Auto Router): track width, clearance, via diameter, via drill diameter | The values for the Auto Router. The editor stores them in the file. They do not change when the design rules change |

The bridge takes the design rules (`rules_from` = `design_rules`). The toolbar button and the script do not open the Auto Router dialog, so the values of that dialog can be old.

- The report and the bridge window name the source. When the two blocks have different values, they show both.
- `rules_from` = `auto_router` takes the Auto Router values.
- `via.diameter_mm` and `via.hole_mm` in the rules file come before both blocks.
- Only the rule `Default` of the Design Rule dialog is read. For other values for single nets use `nets` in the rules file. Example: route with the design rules and make each track wider after routing where the clearance permits: `"nets": [{ "match": ".*", "widen_to_mm": 0.15 }]`.
- The Auto Router block has a list of nets with their own width and clearance. The editor fills it with all nets of the board. With `rules_from` = `design_rules`, the bridge reads an entry only when its values are different from the Auto Router values of the board.

### What the bridge does with a board file

Before the engine starts:

| Step | Rule in the rules file |
|---|---|
| A copper area of a ground net on an inner layer makes that net a plane net. The engine connects each pad of the net with a via into the plane | `planes.from_copper_areas` |
| A copper area of a supply net on an inner layer makes that net a plane net too. Its layer gets a high cost factor, so that few signal tracks cut the area | `planes.supply`, `layers.supply_plane_track_cost` |
| A pad that has no exit on its layer gets a small via in its centre. The via is fixed. The exit test uses the pads, the board edge and the keep-outs of the empty board | `via.on_pads` |
| A pad that lies in a copper area of its net on its own layer (a pour pad) gets no track and no via. The copper area connects it. This applies to every net with a copper area. See "Pads in copper areas" | `planes.pours`, `planes.pour_contact` |
| A pad whose net has a copper area on a different layer gets a small via in its centre, into that area. The engine makes no track for it | `via.plane_pads` |
| The engine gets two via sizes. The standard via of the file is never put on a pad. The small via is used only in pads | `via.pad_via_diameter_mm` |
| Copper without net inside a footprint (an antenna) closes the area around it on all layers. The area follows the copper (its hull plus a margin). The pads of that part stay open for their own track | `keepout.antenna_shape`, `keepout.antenna_margin_mm` |
| Holes that are not plated and a rim at the board edge are closed for new copper | `keepout.hole_clearance_mm`, `edge.copper_to_edge_mm` |
| Each layer gets a cost factor. A ground plane layer gets the highest factor, so the plane stays whole | `layers` |
| Tracks and vias of the file stay and are fixed. Or: the ones that are not locked are removed first. See "Existing tracks" | `existing` |

After the engine has ended:

| Step | Rule |
|---|---|
| The new tracks and vias go into the `shape` block with new ids. All other blocks and all footprints stay as they were, byte for byte | |
| Vias get their real size. The Auto Router dialog cannot do this: its answer has no via size | |
| The stored fill of each copper area is emptied, because it does not fit the new tracks. Rebuild with Shift+B | |
| Track segments that the engine made narrower than the rule get the full width back where the clearance permits | `track.narrow` |
| Tracks of chosen nets are made wider where the clearance permits | `nets`, `widen_to_mm` |
| Track clean-up of the chosen result. See "Track clean-up" | `track.tidy` |
| Mixed job: each second run may put the small via into any pad. See "Parallel runs" | `via.on_pads` |
| A further pass can take the routes of a group of nets from another run. See "Routes of other runs" | `recombine` |
| The bridge models the fill of each copper area of the routed nets: the outline minus the clearance of the area to copper of other nets, with parts narrower than 0.19 mm removed. A via or a pad is joined to the fill only where a piece of fill reaches it | |
| Repair of the copper areas. Each group of pads that the fill does not join to its net gets one via or one short track. See "Pads in copper areas" | `planes.bridge_tracks`, `planes.repair_track_mm` |
| Further passes. For a group that the repair cannot join, the bridge puts a via into one pad, takes out the tracks of other nets that are in the way, and starts the engine again on the result. The engine routes those tracks again | `planes.second_pass` |
| The bridge reads the written file again and checks it: connections with the fill model, exact clearance, board edge, keep-outs, track width, via sizes, ids | |

A board with vias in pads needs filled and capped vias. Ask the manufacturer before you order.

### Pads in copper areas

The rule for every net that has a copper area, ground or supply:

| Where the pad is | What the pad gets |
|---|---|
| In a copper area of its net on its own layer | No track and no via. The fill of the area is the connection |
| Its net has a copper area on a different layer, and the pad is not in an area on its own layer | One small via in the centre of the pad, into that area. No track |
| The via in the pad does not keep the clearance to a pad of a different net | The engine connects the pad with a track and a via |

Without this rule the engine makes a track and a via for each ground pad, also where the top area connects the pad already. These tracks and vias take space from the signal tracks.

The steps:

1. Before routing. The bridge models the fill of the areas on the board without routes. A pad is a pour pad when fill is beside at least 30 % of its outline (`planes.pour_contact`). A pad in open fill has 50 % to 100 %. A pad that the fill touches at one corner only is not taken as connected by the area. The engine gets a pour pad as a pad without net. It routes nothing to it.
2. A pad that is not a pour pad gets the via in the pad when the fill of an area of its net on a different layer is at the pad centre. With an area on an inner layer, the engine has that area as a plane. With an area on an outer layer only, the pad and its via are a net of their own for the engine. In both forms the engine makes no track for the pad.
3. A net without a plane for the engine can have pads that no area connects and that cannot take a via. One pour pad then stays in the net, and the engine routes those pads to it.
4. After routing. Signal tracks cut the fill into pieces. The bridge models the fill again and looks at each group of pads that is not joined to the largest group of the net. It tries these repairs in turn:
   - one standard via in the piece of fill of the group, at a position where the via reaches fill of the net on a different layer
   - one track of up to `planes.repair_track_mm` on the outer layer, from a pad to copper of the net or to a position for a standard via
   - one small via in a pad of the group
   - one track on an inner layer from a via on a cut-off piece of fill to the main piece
5. Further passes. A group can stay open because tracks of other nets run below its pads on the other layers. The engine does not take tracks out to make room for a via. The bridge does this part:
   - One pad of the group gets a small via in its centre. The via must keep the clearance to everything that cannot move: pads, holes, keep-outs, locked tracks.
   - The tracks and vias of other nets that are too near to that via are taken out of the result.
   - The engine starts again on that result. All tracks of the pass before are fixed. The engine routes the open connections in the free space. The vias of the repair stay fixed too.
   - Each run of a further pass goes on with the result of a different run of the pass before, the best result first.
   - The passes end when a pass gives no better result, when no pad can get a via and no group of nets can be taken from another run, after pass 4, or when less than 15 s are left. The bridge takes the best result of all passes.

A pad that stays cut off after these steps is an open connection in the report. Connect it by hand.
A net with one copper area only has no second layer for a repair via. On such a net a cut-off pour pad stays open.

To switch the repair off: `planes.bridge_tracks` = `off` and `planes.second_pass` = `off`. The bridge then adds no via and no track for the copper areas after the engine. The report lists the pads that are cut off.

A job with pour pads, or with `recombine` = `on` and two runs or more, keeps one quarter of the job time for the further passes, 50 s to 300 s. A job time below 150 s keeps nothing. A further pass then runs only when the engine ends early.

A net that the editor marks as a skip net and that has a copper area is handled by these rules. A skip net without a copper area is not routed.

The behaviour of earlier versions: `planes.pours` = `engine` (every pad gets its connection from the engine), `planes.supply` = `tracks` (a supply net is routed with tracks), `via.plane_pads` = `fine_pitch` (a via only in pads up to 0.3 mm wide).

### Routes of other runs

The runs of a job leave different connections open. A further pass can take the routes of a group of nets from the result of another run (`recombine` = `on`). The engine routes nothing again for this step.

How a group is made, for a net without copper area that is open in a result and has fewer open connections in the result of another run:

1. The group starts with the open net. Its tracks and vias come from the other run.
2. Each net that has a track in the way of these routes joins the group. Its tracks and vias come from the other run too.
3. Step 2 repeats until no track of a net outside the group is in the way.

The routes of the group take the place of the routes of these nets in the result. All tracks are fixed. The engine then goes on with the connections that are still open.

A group is taken only when all of these are true:

- The nets of the group have fewer open connections in the other run than in this result.
- No track or via of a net with a copper area is in the way. Such a net cannot change runs, because its pads in the copper areas and its repair vias belong to this result.
- No route of the group is too near to a via in a pad that an earlier pass added.
- The group has 40 nets or fewer. A pass takes at most 6 groups, and two groups must not be near to each other.

A via that the repair added after an earlier pass gives way to a group. The repair after the pass sets its vias again.

In a further pass each result gets two runs where a group can be taken: one without the group and one with it. The job keeps the result with the fewest open connections of all runs and passes. So this step costs a run, and it cannot make the result of the job worse.

Each run folder of the job has a file `open.txt` with the open connections of that run and the changes that the bridge made before it.

### Track clean-up

The engine gives one connection as many track pieces. A piece ends where the engine changes the width. The engine also leaves small steps between pieces. The bridge cleans the tracks of the chosen result. Tracks that were in the file before the job stay as they are.

Without change of copper:

- Pieces that join end to end become one track record. A joint stays where a via, a pad or a third track of the net is.
- A corner point on a straight line is removed.

With change of copper (`track.tidy` = `on`):

- A segment that the engine made narrow gets the full width.
  Cause of most narrow segments: the engine keeps 0.0015 mm more than the clearance rule. Where the full width keeps the rule but not this margin, the engine makes the segment narrow.
  The bridge tests the full width in the position of the segment. Then it tests a shift of the segment to the side. The shift is the value by which the full width is too near, up to 0.015 mm. The corners at the two ends move along the segments before and after it.
  An end of the track in the centre of a pad or a via of its net moves with the segment and stays inside that copper.
  The full width must keep the clearance rule plus 0.0002 mm. This is less than the margin of the engine: a track below the smallest width of the board maker is the larger fault.
  When all tracks have their last form, the bridge tests each narrow segment again, up to 5 rounds.
  The report lists each segment that stays narrow, with its net, layer, width, length, position and the copper that is in the way.
- A small step becomes one segment. A small step is two segments of 0.2 mm or less at about a right angle.
- A side step goes away. A side step is a segment of 0.2 mm or less between two segments with the same direction, where the track moves sideways by 0.1 mm or less. One of the two segments moves to the line of the other.
- A corner point that is 0.004 mm or less from a straight line is removed.

Each other change of copper must keep the clearance rule plus 0.0015 mm to all copper of other nets, to the keep-outs and to the board edge. This is the margin of the engine. A corner with a via, a pad or the end of a second track does not move.

After the clean-up the bridge checks the file again: open connections with the fill model, clearance, board edge and keep-outs. When a check is worse than before, the bridge tests the changes of copper for each layer alone. A layer that makes a check worse keeps its copper, and the report says so.

The clean-up does not change the route. A detour or a jog that the engine needs for the clearance stays. Example: two round pads of 1.7 mm at 2.54 mm pitch. A track at 45 degrees with 0.15 mm width and 0.15 mm clearance needs 2.15 mm across the two pad centres and has 1.80 mm. The track must cross the gap at a steeper angle, so the engine makes a short vertical segment there.

### Narrow tracks

The engine makes a track segment narrower than the rule where the full width does not fit: 3/4, 3/5 and 1/2 of the rule width. With a rule of 0.10 mm these are 0.075, 0.06 and 0.05 mm.
Most of these segments are short of the full width by a few thousandths of a millimetre. A width below the limit of the board maker fails the check of the board maker.

The bridge does three things (`track.narrow`):

1. Repair. After routing, a narrow segment gets the full width where the clearance permits, in its position or after a small shift. See "Track clean-up". The report lists each segment that stays narrow.
2. One run of four without narrow tracks (`mixed`, the default, with 4 runs or more). The engine of that run finds a path with the full width or leaves the connection open.
   When that run connects as much as the best other run, its result is taken, and the result has no narrow segment.
3. All runs without narrow tracks (`avoid`). Use it when the board is fully routed with `mixed` and the result still has narrow segments, or when a narrow track is never acceptable.
   On a dense board this setting leaves more connections open.

### Antenna keep-out

A footprint with copper that has no net is taken as an antenna. The bridge closes the area around this copper for new copper on all layers.

The area is the convex hull of the copper plus the margin (`keepout.antenna_shape` = `hull`). The setting `box` closes the rectangle around the copper.
For an antenna that stands at an angle the rectangle is much larger than the antenna.

Example, test board B: the antenna is a meander at 45 degrees with 21 mm2 of copper. The rectangle has 198 mm2 and the hull has 81 mm2.
A connector and a resistor stand inside the rectangle, 2.6 mm and 5.5 mm away from the antenna. With `box` the bridge closes the area around their pads.
The job ended with 10 open connections with `box` and with 0 with `hull`.

- The hull is stored as rows of rectangles of 0.1 mm height. It is never smaller than the true hull.
- The area on the two long sides of an antenna strip is open now. The bridge does not know which side is the ground side.
  To close more area, add rectangles with `keepout.boxes`, or set `keepout.antenna_shape` to `box`.
- An antenna that is parallel to the board edges has nearly the same area with both settings.

### Part moves

`parts.move` = `on` permits the bridge to shift a part. The default is `off`. The bridge never moves a part without this setting.

What it is for: a pad of a net with a copper area lies in the area, tracks of other nets cut it off from the fill, and no via fits in the pad, because tracks on the other layers pass below its centre.
A shift of the part by a part of a millimetre can bring the centre of the pad to a point where the via fits.

A move is made only when all of this is true:

- The reference of the part matches `parts.movable` and not `parts.fixed`, and the part has no more pads than `parts.max_pads`.
- No pad of the part has a via.
- Each track that ends in a pad of the part still ends in that pad after the move. So no track changes.
- The pads keep the clearance to all copper of other nets, to keep-outs and to holes.
- The gap to the body of each other part stays at `parts.gap_mm` or more, or is not smaller than before.
- A via of the pad-via size fits in the centre of the pad after the move and reaches the fill of the net on a different layer.

The bridge takes the shortest shift that passes, not more than `parts.max_shift_mm`. It repairs and counts the moved board again. The move stays only when the count of open connections is lower and no check is worse.
At an equal count of open connections, a result without moved parts is taken before a result with moved parts.

The report has a section PART MOVES. It lists each moved part with its shift, and each open pad that no move could help, with the cause.
All shapes of the footprint move together: pads, silk, texts and the outline of the 3D model. The schematic does not change.

Measured effect: see the [test report](TEST_REPORT.md). On the two test boards the moves did not change the result of a job.

### Existing tracks

`existing` = `remove` routes the board again from the start. Tracks and vias that are not locked are left out of the result file.
Lock a track in the editor to keep it. The input file does not change. This is the tested path.

`existing` = `keep` is for a board with a small number of tracks that you drew first, for example a crystal or a radio feed.
The engine reads an existing track as a connection only when it ends on the centre of a pad or on a second track.
The bridge adds a short link from a track end that touches a pad to the centre of that pad, so that the engine sees the connection.
On a board that is fully routed, `keep` gave no result in the test: the engine did not accept the existing copper and did not end in time.
A pad that has existing copper of its net gets no via from the bridge.

## Parallel runs

The bridge can run the engine several times on the same board at the same time. Each run is a separate process.
The best result is taken.

Why this helps: the result of the engine depends on the order in which it routes the connections and on its first stage
(fanout: short escape tracks with a via for SMD pads). On the test boards no single setting was best for every board.

**Number of runs.** `router.parallel_runs` sets it. With 0 the bridge chooses: one run for every 4 logical processors, at most 4.
The bridge then checks the free memory and starts fewer runs if needed. The start text of the bridge shows the number.

**What the runs do.** The runs use this list in order. With 4 runs the first 4 entries are used.

Board file interface with `via.on_pads` = `mixed` (the default). This is the mixed job:

| Run | Board order | Small via permitted in any pad |
|---|---|---|
| 1 | as in the file | no |
| 2 | as in the file | yes |
| 3 | order 1 | no |
| 4 | order 1 | yes |
| 5 and more | order 2, order 3, ... in pairs | no and yes in turn |

A run with the permission can put the small via (`via.pad_via_diameter_mm`) into any pad of the net, and at any other position. It has more vias in pads and less track. It can have more open connections: under a part with a small pad pitch, vias in neighbour pads leave no room for a track between them on any layer.
A further pass that goes on with such a result keeps the permission.
"as in the file" and "order 1" were the two best runs of the list below in the tests.

Board file interface with another value of `via.on_pads`:

| Run | Board order | Engine settings |
|---|---|---|
| 1 | as in the file | fanout stage on |
| 2 | as in the file | fanout stage off |
| 3 | as in the file | fanout stage on, the preferred track direction of each layer exchanged (horizontal and vertical) |
| 4 | order 1 | fanout stage on |
| 5 and more | further orders | fanout stage on and off in turn |

Runs 3 and 4 were chosen by a test on the test board, with 4 runs at the same time. Open connections after the first pass and after the second pass:

| Run | First pass | Second pass |
|---|---|---|
| as in the file | 10 | 6 |
| fanout stage off | 19 | 14 |
| track directions exchanged | 11 | 8 |
| order 1 | 11 | 8 |
| order 2 | 14 | 14 |
| via cost 30 in place of 50 | 14 | 12 |
| via cost 100 | 18 | 11 |
| cost 300 in place of 100 for taking out a track | 18 | 11 |

Editor channel:

| Run | Board order | Settings |
|---|---|---|
| 1 | as sent by the editor | The rules file, fanout stage on |
| 2 | as sent by the editor | The basic settings: no via on pads, layer costs of the engine, fanout stage with the lengths as the engine reads them (see "Input repair") |
| 3 | as sent by the editor | The rules file, fanout stage off |
| 4 | order 1 | The rules file, fanout stage on |
| 5 | order 2 | The rules file, fanout stage off |
| 6 | order 3 | The basic settings |
| 7 and more | further orders | The three settings in turn |

A different order changes only the sequence of pads and nets in the board text. The board is the same.

Why run 2 has the basic settings: on boards that every run completes, that run gave the fewest vias.
In one test it had 556 vias on 23 boards. The run without fanout stage had 736.
On hard boards run 1 completes more connections. The [test report](TEST_REPORT.md) shows this.
With one run per job only run 1 is used.

With `track.narrow` set to `avoid` no run has the fanout stage. The runs then differ in the order only.
With `track.narrow` set to `mixed` and 4 runs or more, run 4 (and 8, 12, ...) has the words "no narrow tracks" in its name. The engine of that run has no fanout stage and makes no narrow segment.

**Which result is taken.** In this sequence:

1. Fewest defects. Editor channel: open connections plus clearance violations that the other runs do not have.
   Board file: open connections as counted by the bridge on the result file, then clearance violations of the engine.
2. With `track.narrow` set to `avoid`, or to `mixed` with 4 runs or more: fewest narrow segments.
3. Mixed job: most vias in a pad of their net.
4. Fewest vias.
5. Shortest total track length.

**When the job ends.** A job ends when all runs have ended. Two rules shorten that:

- When one run has connected everything, the other runs get 1.5 times its time plus 5 s (`router.early_stop_factor`). Then the bridge stops them.
  A run without narrow tracks needs more time. While such a run works, this rule waits. It starts when that run is complete or has ended.
- At the end of the job time the bridge stops every run that still works, if at least one run has a result.

**Processors with fast and slow cores** (Intel 12th generation and later):

- Fast cores finish more routing passes in the job time. The automatic number of runs is low for this reason.
- Each run uses one core for routing. More runs than fast cores put some runs on slow cores. They cost memory and heat and return less.
- A laptop processor lowers its clock speed when many cores work. Compare the pass times in `engine.log` for 1 run and for 4 runs before you use more.
- Windows on battery power moves programs that are minimized or in the background to the slow cores.
  Route on mains power and do not minimize the bridge window.
- `engine.affinity_mask` limits the engine to chosen logical processors. Example: on an Intel Core i7-1260P the fast cores are
  normally logical processors 0 to 7, so the mask is `"ff"`. This setting was tested on Linux only.

**Memory.** With more than one run each engine gets a heap limit of 768 MB. One engine process then uses up to about 1.1 GB.
The largest test board needs 360 MB of live data.

## Input repair (editor channel)

The bridge corrects three defects of the board data that the editor sends. Each one stopped or degraded routing on real boards.
The bridge window names every repair that it makes.

| Defect | Repair |
|---|---|
| The board outline arrives as several open pieces | The pieces are joined into closed loops. If that is not possible, the outline stays as sent and a warning is shown |
| A pad has no area | The pad becomes a small circle at the same point |
| A net name has special characters such as `=`, `[`, `/` | The net gets a temporary name. The result carries the original name |

Nets that the editor marks as not to route (the skip option of the Auto Router dialog) are not routed. Their pads stay as obstacles.

The editor writes its unit of 10 mil as 1 mil into the board text. The engine therefore reads the board 10 times too small.
Tracks and pads are not affected, because the result goes back in the same unit. The engine settings that are lengths were affected:
the distance to the board edge and the two distances of the first stage would be 10 times too large.
The bridge corrects them.

Advice: draw the board outline as closed shapes. Then the first repair is not needed.

## Settings

File `config/main.json`. Restart the bridge after a change.

| Key | Default | Meaning |
|---|---|---|
| `web.port` | 3579 | The port that the editor uses. Do not change it for EasyEDA |
| `web.allowed_origins` | empty | Empty: any web page may connect, as with the original router. Example to restrict: `["https://easyeda.com"]`. The bridge window shows the origin of each connection |
| `router.parallel_runs` | 0 | Engine runs per job. 0: automatic. 1: one run. See "Parallel runs" |
| `router.early_stop_factor` | 1.5 | See "Parallel runs". 0: always wait for every run |
| `router.max_parallel_jobs` | 1 | Routing jobs at the same time. A further request is refused. Each job starts its own engine runs |
| `router.min_timeout` | 300000 | Lower limit of the job time in ms on the editor channel. The editor asks for 300 s. A larger value gives hard boards more time. Not tested with the editor: it is not known how long the editor waits |
| `router.keep_heartbeat` | 5000 | Interval in ms of the message that keeps the connection to the editor open during a job. Lower limit 1000 |
| `router.finish_margin` | 10000 | Time in ms kept free at the end of the job for writing the result |
| `router.fanout_share` | 0.25 | Part of the job time that the engine may use for its first stage. Without a limit that stage used the whole job on one test board. 0: no limit |
| `router.optimizer_share` | 0.2 | Part of the job time that the engine may use for its last stage (shorter tracks and fewer vias after everything is connected). One test run spent 180 s there, changed nothing and ended late. 0: no limit |
| `router.send_progress` | false | Send the number of open connections to the editor during the job. Not tested with the editor |
| `router.keep_jobs` | 10 | Number of job folders kept in `jobs` |
| `engine.offline` | true | The engine gets no network access |
| `engine.max_heap_mb` | 0 | Heap limit for one engine process in MB. 0: automatic (no limit for one run, 768 for parallel runs) |
| `engine.affinity_mask` | empty | Hexadecimal mask of the logical processors that the engine may use. Empty: no limit. See "Parallel runs" |
| `engine.args` | empty | Extra Freerouting options for every run. They come after the options that the rules file makes |
| `repair.join_outline`, `fix_zero_size_pads`, `rename_nets`, `drop_protected_nets` | true | Switch for each input repair |
| `repair.reorder_layers` | false | Change the layer order from top, bottom, inner to top, inner, bottom. No effect was measured with this engine version |

## Network and data

- The bridge listens on 127.0.0.1 only. Other computers cannot reach it.
- The engine is started with usage data off (`-da`). With `engine.offline` its only other request, a version check at GitHub, is blocked.
- Boards stay on your computer. The folder `jobs` holds the last 10 jobs.

## If a job fails

The bridge window shows the reason. The folder `jobs/job-<time>-<n>` holds:

- Editor channel: `original.dsn`, the board as the editor sent it.
- Board file: `original.json`, the board file. `result.json` and `report.txt`, when the job gave a result.
- `run-1`, `run-2`, ...: one folder for each engine run with `input.dsn` (the board text for the engine, in the order of that run), `engine.log` and `output.ses`.
- `pass-2`, `pass-3`, ...: the runs of the further passes of a board file job, in the same form.
- `chosen.txt`: the run whose result was taken.

Keep that folder for analysis.
