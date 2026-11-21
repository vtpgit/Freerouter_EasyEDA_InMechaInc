# Changelog

All notable changes of the bridge. The newest version is first.
Keys in code font are keys of `config/rules.json` or `config/main.json`.

## 1.3.0

First public release. The routing behaviour is the same as in version 1.2.10.

Added

- Example boards with results in the folder `examples`.

Changed

- Documentation: new README, and a user guide, a rules reference and a test report in the folder `docs`.
- The run of the editor channel with the name "settings of version 1.1" has the name "basic settings".
- Every source file has the copyright and license header.

## 1.2.10

Changed

- `track.narrow` has the new value `mixed`, and it is the default. With 4 runs or more, one run of four gets no narrow tracks from the engine. At an equal count of open connections the result with fewer narrow segments is taken.
- Track clean-up: a narrow segment gets the full width when the full width keeps the clearance rule plus 0.0002 mm. The value was 0.0015 mm.
- Track clean-up: the shift to the side is the value that is needed, up to 0.015 mm. An end of a track in a pad or a via of its net moves with the segment. Each narrow segment is tested again after all other tracks have their last form.
- The early stop rule waits while a run without narrow tracks still works.

Added

- The report lists each narrow segment that stays, with net, layer, width, length, position and the copper that is in the way.

## 1.2.9

Fixed

- The closed area around antenna copper follows the copper (`keepout.antenna_shape`, default `hull`). It was the rectangle around the copper. For an antenna at an angle the rectangle closed board area far from the antenna, and pads in it had no exit.

Added

- Part moves (`parts.move`, default `off`): a part with two pads can shift by up to 0.15 mm to make room for a via in a pad.

## 1.2.8

Added

- Mixed job (`via.on_pads` = `mixed`, the default): each second run has the small via permitted in any pad.
- Routes of other runs (`recombine`, default `on`): a further pass can take the routes of a closed group of nets from the result of another run.
- The report shows the vias in pads for each run. Each run folder has the file `open.txt` with the open connections of the run.

Changed

- A job with `recombine` = `on` and two runs or more keeps time for further passes also when the board has no pads in copper areas.

## 1.2.7

Changed

- A board file job takes the track width, the clearance and the via size from the design rules of the file (`rules_from`, default `design_rules`). It took the stored values of the Auto Router dialog. The report names the source and shows both sets when they differ.

Fixed

- Fill model: a keep-out rectangle of the plan does not stop the fill of a copper area. The editor does not know these rectangles.

## 1.2.6

Added

- Track clean-up of the result of a board file job (`track.tidy`, default `on`): track pieces that join end to end become one record, narrow segments get the full width where the position or a small shift permits, and small steps go away.

## 1.2.5

Fixed

- Fill model: the smallest width of the fill is 0.19 mm, measured on a fill that the editor stored. It was 0.10 mm, and the report counted fewer open connections than the editor showed.

Changed

- A board file job keeps one quarter of its time for the further passes, from 50 s to 300 s. A further pass starts with 15 s of job time left.
- The report gives the reason when a job with open pads in copper areas has no further pass.

## 1.2.4

Changed

- The default job time of a board file job is 600 s (`time.job_s`). It was 300 s.

Fixed

- Board file job: the runs with a different order of pads and nets were copies of runs 1 and 2. The order change did not work on a board file.

## 1.2.3

Changed

- The rule for pads in copper areas applies to every net with a copper area, also to a net with one area only.
- A pad whose net has a copper area on a different layer gets a via in the pad into that area, and no track (`via.plane_pads` = `all`).
- A supply net with a copper area is a plane net (`planes.supply` = `plane`). The layer of a supply area has the cost factor 4.0 for tracks (`layers.supply_plane_track_cost`).
- A pad counts as a pad in a copper area only when fill is beside at least 30 % of its outline (`planes.pour_contact`).

## 1.2.2

Added

- A pad that lies in a copper area of its net gets no track and no via from the engine (`planes.pours`).
- Repair of the copper areas after routing: a via in a cut-off piece of fill, a short track from a pad, or a via in a pad (`planes.bridge_tracks`, `planes.repair_track_mm`).
- Further engine passes on the first result, for pads that tracks cut off from the copper area of their net (`planes.second_pass`). The report has the section FURTHER PASSES.

## 1.2.1

Fixed

- Version 1.2.0 counted every via inside the outline of a copper area as connected. The bridge now models the fill of each copper area, and the count of open connections uses the model.

## 1.2.0

Added

- Board file interface: the commands `route` and `check`, a script and a toolbar button for the editor.
- Rules file `config/rules.json`.
- Board file interface: vias in pads, plane nets from copper areas, a second via size, keep-outs, layer cost factors, full width for narrowed segments, wider tracks for chosen nets, and checks of the result file.
- Editor channel: the engine may put a via on a pad of the same net (`via.on_pads`), planes by name (`planes.list`), layer cost factors on boards with more than 2 layers.
- Count and report of track segments that are narrower than the rule (`track.narrow`).

Fixed

- Editor channel: the lengths in the engine settings were 10 times too large, because the editor writes its unit of 10 mil as 1 mil.
- A new request that comes directly after a result is no longer refused.

## 1.1.0

Added

- Several engine runs for each job (`router.parallel_runs`).
- Time limit for the last engine stage (`router.optimizer_share`).
- One sub-folder for each run in the job folder, and the file `chosen.txt`.

Fixed

- The bridge did not read the status line of the engine with `1 violation` in the singular.

## 1.0.0

- First version: the Freerouting engine behind the local auto router interface of the EasyEDA Standard editor, with repair of the board text that the editor sends.
