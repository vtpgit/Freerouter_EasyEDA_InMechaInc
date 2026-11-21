# Rules file reference

File `config/rules.json`. All lengths are millimetres. The bridge reads the file at the start of each job. No restart is needed.

| Key | Default | Meaning | Editor channel | Board file |
|---|---|---|---|---|
| `time.job_s` | 600 | Job time in seconds. The script and the toolbar button of the editor use this value | no, the editor sets 300 | yes |
| `edge.copper_to_edge_mm` | 0.3 | Distance of new copper from the board edge | yes | yes |
| `via.on_pads` | `mixed` | `off`: no via on a pad. `needed`: see the next two columns. `all`: the engine may use any pad of the net. `mixed`: the mixed job, see "Parallel runs" in the [user guide](USER_GUIDE.md#parallel-runs) | `needed`, `all` and `mixed`: the engine may put the via of the board on a pad of the same net | `needed`: the bridge puts a small via into each pad without exit and, in a further pass, into a pad that tracks cut off from the copper area of its net. `all`: the engine may also use the small via at any position. `mixed`: as `needed`, and each second run has the permission of `all` |
| `via.plane_pads` | `all` | Small via in a pad whose net has a copper area on a different layer. `all`: every such surface-mount pad that is not a pour pad. `fine_pitch`: only pads up to `via.fine_pitch_pad_mm` wide. `none`: no such via. The engine then connects these pads | no | yes |
| `via.fine_pitch_pad_mm` | 0.3 | See above | no | yes |
| `via.plane_stubs` | `off` | `on`: before the engine starts, each surface-mount pad of a plane net on a part with up to 4 pads gets a short track to a standard via beside the pad, where there is room. These are fixed for the engine. On the test board this gave no gain | no | yes |
| `via.pads` | empty | Pads by name that get a small via in the pad. Example: `["U1.A17", "U1.AL15"]`. Use it for pads that the report of a first job lists as open | no | yes |
| `via.pad_via_diameter_mm`, `via.pad_via_hole_mm` | 0.249, 0.15 | Size of the small via | no | yes |
| `via.diameter_mm`, `via.hole_mm` | 0 | Size of the standard via. 0: the value of the file | no | yes |
| `via.cost` | 50 | Cost of one via. A higher value gives fewer vias and longer tracks | yes | yes |
| `via.plane_cost` | 5 | Cost of a via of a plane net | yes | yes |
| `planes.from_copper_areas` | `inner` | `none`, `inner` or `all`: which copper areas make a plane net | no | yes |
| `planes.list` | empty | Planes by hand. Example: `[{"net": "GND", "layer": "inner1"}]`. Layer names: `top`, `inner1`, `inner2`, ..., `bottom` | yes | yes |
| `planes.supply` | `plane` | A copper area of a net that is not ground. `plane`: the area is the connection, as for ground. `tracks`: the area is not taken as a connection, and the engine routes the net with tracks | no | yes |
| `planes.pours` | `after` | Pads in a copper area of their net. `after`: the engine makes no track and no via for them, and the bridge repairs the copper areas after routing. `engine`: the engine connects every pad. See "Pads in copper areas" in the [user guide](USER_GUIDE.md#pads-in-copper-areas) | no | yes |
| `planes.pour_contact` | 0.3 | A pad counts as connected by a copper area on its layer when fill is beside this part of the pad outline, 0 to 1. 0: every contact counts | no | yes |
| `planes.bridge_tracks` | `on` | The repair of the copper areas after routing: vias and short tracks for groups of pads that the fill does not join to their net. `off`: no repair and no further pass | no | yes |
| `planes.repair_track_mm` | 4.0 | Largest length of a repair track on an outer layer. 0: no such track | no | yes |
| `planes.second_pass` | `on` | Further engine passes for pads that tracks cut off from the copper area of their net. This needs `via.on_pads` = `needed` or `all`. `off`: one pass | no | yes |
| `planes.ground_net` | pattern | Which net names are ground. Used for the layer cost and for `planes.supply` | yes | yes |
| `layers.costs` | `rules` | `rules`: the factors below, on boards with more than 2 layers. `engine`: the engine sets its own factors. On boards with inner layers it then prefers the inner layers. A board with 2 layers always gets the factors of the engine, unless `layers.by_layer` has an entry | yes | yes |
| `layers.top_track_cost` | 1.0 | Cost factor of a track on the top layer | yes | yes |
| `layers.bottom_track_cost` | 1.15 | Bottom layer | yes | yes |
| `layers.inner_track_cost` | 1.6 | Inner layer without a plane | yes | yes |
| `layers.supply_plane_track_cost` | 4.0 | Layer with a plane of a supply net. With 1.6 the test board had 182 mm of signal track on that layer and 4 open connections of the supply net. With 4.0 it had 59 mm and none | yes | yes |
| `layers.ground_plane_track_cost` | 4.0 | Layer with a plane of a ground net | yes | yes |
| `layers.by_layer` | empty | Factor for one layer by name. Example: `{"inner2": 2.5}` | yes | yes |
| `first_stage.min_via_distance_mm` | 0 | First engine stage (an escape track with a via for each surface-mount pad): smallest distance of the via from the pad | yes | yes |
| `first_stage.max_escape_mm` | 3.0 | Largest length of an escape track | yes | yes |
| `track.narrow` | `mixed` | The engine makes a track narrower than the rule where the full width does not fit. It uses 3/4, 3/5 and 1/2 of the width. `allow`: no action. `warn`: the narrow segments are counted and reported. `repair`: after routing, each narrow segment gets the full width where the clearance permits, and the rest is reported. `mixed`: `repair`, and with 4 runs or more one run of four gets no narrow tracks from the engine, and at an equal count of open connections the result with fewer narrow segments wins. `avoid`: `repair`, and no run gets narrow tracks from the engine. See "Narrow tracks" in the [user guide](USER_GUIDE.md#narrow-tracks) | `repair` and `mixed` work as `warn` | yes |
| `track.tidy` | `on` | Clean-up of the tracks of the job in the result file. `on`: see "Track clean-up" in the [user guide](USER_GUIDE.md#track-clean-up). `join`: track pieces that join end to end become one record, and no copper changes. `off`: the tracks stay as the engine made them | no | yes |
| `track.retry_width_mm` | 0 | The engine tries a failed connection again with this width. 0: off | yes | yes |
| `nets` | empty | Rules for nets by name pattern. See below | no | yes |
| `keepout.antenna_margin_mm` | 0.3 | Margin of the closed area around copper without net. Below 0: off | no | yes |
| `keepout.antenna_shape` | `hull` | Shape of that area. `hull`: the convex hull of the copper. `box`: the rectangle around the copper. See "Antenna keep-out" in the [user guide](USER_GUIDE.md#antenna-keep-out) | no | yes |
| `keepout.hole_clearance_mm` | 0.2 | Distance of new copper from a hole that is not plated | no | yes |
| `keepout.boxes` | empty | More keep-outs. Example: `[{"layers": ["inner1"], "box_mm": [24, 6, 40, 12]}]`. The numbers are x and y of two corners, from the origin of the file. No `layers`: all layers | no | yes |
| `existing` | `keep` | `keep`: tracks and vias of the file stay and are fixed. `remove`: tracks and vias that are not locked are removed before routing | no | yes |
| `parts.move` | `off` | `on`: a part of the list `parts.movable` can shift a little to make room for a via in a pad. See "Part moves" in the [user guide](USER_GUIDE.md#part-moves) | no | yes |
| `parts.movable` | `R[0-9]+`, `C[0-9]+`, `L[0-9]+` | Regular expressions for the whole reference of a part that can move | no | yes |
| `parts.fixed` | empty | Regular expressions for references that never move. They come first | no | yes |
| `parts.max_pads` | 2 | A part with more pads does not move | no | yes |
| `parts.max_shift_mm` | 0.15 | Largest shift of a part. Upper limit 1.0 | no | yes |
| `parts.gap_mm` | 0.15 | Gap between the bodies of two parts. A move can make a gap smaller only down to this value | no | yes |
| `recombine` | `on` | `on`: a further pass can take the routes of a group of nets from the result of another run. See "Routes of other runs" in the [user guide](USER_GUIDE.md#routes-of-other-runs). `off`: no such step | no | yes |
| `rules_from` | `design_rules` | Source of the track width, the clearance and the size of the standard via of a board file. `design_rules`: the Design Rule dialog. `auto_router`: the values of the Auto Router dialog that the editor stored in the file. See "Track width, clearance and via size of a board file" in the [user guide](USER_GUIDE.md#track-width-clearance-and-via-size-of-a-board-file) | no, the editor sends the Auto Router values | yes |

A plane in `planes.list` on the editor channel: the engine connects the pads of the net with vias into that layer.
The copper area must be in the editor on that layer. If it is not, these pads stay open and the bridge cannot see that.

Entries of `nets`. The first entry whose pattern matches the whole net name is used.

```json
"nets": [
  { "match": "XC[12]|XL[12]|RF_.*", "layers": ["top"] },
  { "match": "VBAT|VIN|VDD_.*|QI_.*", "widen_to_mm": 0.5 },
  { "match": "SHIELD", "skip": true }
]
```

| Key | Meaning |
|---|---|
| `match` | Regular expression for the net name |
| `layers` | The net may use these layers only. Use it for crystal and radio nets. A net that has no route on its layers stays open |
| `width_mm`, `clearance_mm` | Width and clearance of the net during routing. A larger width makes routing harder. Prefer `widen_to_mm` |
| `widen_to_mm` | After routing, each segment of the net gets the largest width in steps of 0.05 mm up to this value that keeps the clearance |
| `skip` | The net is not routed |
