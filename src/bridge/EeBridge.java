/*
 * EasyEDA to Freerouting bridge.
 * Copyright (C) 2026 Valentine Petrov, INMECHA INC.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. It comes WITHOUT ANY WARRANTY. See the file LICENSE.
 * The routing engine is Freerouting by Alfons Wirtz and the Freerouting contributors. See NOTICE.md.
 */
package bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Repair of nets with copper areas, after the engine has routed the signal nets.
 *
 * The engine takes a pad that lies in a copper area of its net as connected. Signal tracks then cut the fill of
 * the area into islands. The bridge models the fill (EePlane) and looks at each group of pads of the net that is
 * not joined to the main group:
 * 1. An island that holds pads of the group gets one standard via, at a position where the via keeps all rules
 *    and stands in an island of the main group on a different layer.
 * 2. A pad or a via of the group gets a short track on an outer layer. The track ends on copper of the main group
 *    on that layer, or at a position where a standard via fits and reaches the main group.
 * 3. A pad gets a small via in the pad, when the via fits and reaches the main group.
 * 4. A via on a cut-off island of an inner layer gets a track on that layer to the main island, where a path exists.
 * What stays in a separate group is an open connection in the report.
 */
final class EeBridge
{
    /** The board text with the added copper. The text of the board when nothing was added. */
    final String text;
    int tracks_added = 0;
    double length_added = 0.0;
    int island_vias_added = 0;
    int pad_vias_added = 0;
    /** Tracks on outer layers from a pad to copper of its net or to a new via. */
    int surface_tracks_added = 0;
    /** Standard vias at the end of such tracks. */
    int track_vias_added = 0;
    /** One line per net for the report. */
    final List<String> lines = new ArrayList<String>();

    /** A via that the bridge added. */
    static final class AddedVia
    {
        static final int ISLAND = 0, PAD = 1, TRACK_END = 2;
        int kind;
        String net;
        double x, y, diameter;
        /** PAD: index of the pad in the list of the board */
        int pad_index = -1;
        /** true: the via keeps the clearance of the engine to all copper, and to the pads of its net. It can be a fixed via in a further pass. */
        boolean roomy;
    }

    final List<AddedVia> vias = new ArrayList<AddedVia>();

    /** Margin on the grid, so that a track between two cell centres keeps the clearance. */
    private static final double GRID_MARGIN_MM = 0.02;
    /** Margin of a via that the bridge places, so that rounding in the file makes no clearance error. */
    private static final double VIA_MARGIN_MM = 0.004;
    /**
     * The engine works with an octagon around each via. Its corners are up to 0.017 mm outside a standard via.
     * A via that the engine gets as a fixed via in a further pass keeps this added distance to copper of other nets.
     */
    static final double ENGINE_MARGIN_MM = 0.04;
    /** A standard via keeps this distance from a surface-mount pad of its own net. */
    private static final double OWN_PAD_GAP_MM = 0.05;
    private static final int MAX_ROUNDS = 8;
    private static final int MAX_CANDIDATES = 600;
    private static final int[] DX = { 1, -1, 0, 0, 1, 1, -1, -1 };
    private static final int[] DY = { 0, 0, 1, -1, 1, -1, 1, -1 };

    private final EePlan plan;
    private final String prefix;
    private int next_id = 1;
    /** Per net: groups at the start, island vias, pad vias, tracks on inner layers, their length, tracks on outer layers, their length, vias at track ends. */
    private final Map<String, double[]> stats = new LinkedHashMap<String, double[]>();

    EeBridge(EeBoard p_board, EePlan p_plan)
    {
        this.plan = p_plan;
        this.prefix = EeResult.free_prefix(p_board.text);
        EeBoard board = p_board;
        String current = p_board.text;
        for (int round = 0; round < MAX_ROUNDS; ++round)
        {
            List<String> records = new ArrayList<String>();
            List<Copper> added = new ArrayList<Copper>();
            for (String net : p_plan.fill_areas.keySet())
            {
                repair(board, net, records, added, round == 0);
            }
            if (records.isEmpty())
            {
                break;
            }
            StringBuilder sb = new StringBuilder(current.length() + records.size() * 90);
            sb.append(current, 0, board.shape_end);
            for (int i = 0; i < records.size(); ++i)
            {
                if (i > 0 || !board.records.isEmpty())
                {
                    sb.append(',');
                }
                Json.append_string(sb, records.get(i));
            }
            sb.append(current, board.shape_end, current.length());
            current = sb.toString();
            board = new EeBoard(current, p_board.design_rules_first);
        }
        this.text = current;
        // groups at the end
        List<Copper> copper = p_plan.fill_areas.isEmpty() ? new ArrayList<Copper>() : EeNet.copper_of(board, p_plan, false);
        for (Map.Entry<String, double[]> entry : stats.entrySet())
        {
            double[] s = entry.getValue();
            int groups_after = new EeNet(board, p_plan, entry.getKey(), copper, false).pad_groups().size();
            if (s[0] > 1)
            {
                lines.add(String.format(Locale.ROOT,
                        "%s: after the engine the pads were in %d groups. Added: %d vias in islands of the copper areas, %d tracks on outer layers from a pad to copper of the net or to a new via (%.1f mm, %d vias), %d vias in pads, %d tracks on inner layers (%.1f mm). Groups now: %d.",
                        entry.getKey(), (int) s[0], (int) s[1], (int) s[5], s[6], (int) s[7], (int) s[2], (int) s[3], s[4], groups_after));
            }
        }
    }

    /** One pass over the groups of a net. New records and their copper are added to the lists. */
    private void repair(EeBoard p_board, String p_net, List<String> p_records, List<Copper> p_added, boolean p_first_round)
    {
        List<Copper> copper = EeNet.copper_of(p_board, plan, false);
        copper.addAll(p_added);
        EeNet graph = new EeNet(p_board, plan, p_net, copper, false);
        Map<Integer, List<EeBoard.Pad>> groups = graph.pad_groups();
        double[] s = stats.computeIfAbsent(p_net, k -> new double[8]);
        Map<Integer, boolean[]> main_copper = new HashMap<Integer, boolean[]>();
        if (p_first_round)
        {
            s[0] = groups.size();
        }
        if (groups.size() <= 1)
        {
            return;
        }
        int main = graph.main_group();
        double via_r = plan.via_diameter_mm / 2.0;
        double pad_via_r = plan.rules.pad_via_diameter_mm / 2.0;
        boolean pad_vias_allowed = !plan.rules.via_on_pads.equals("off");

        for (Integer group : groups.keySet())
        {
            if (graph.find(group) == graph.find(main))
            {
                continue;
            }
            int main_root = graph.find(main);
            int group_root = graph.find(group);
            boolean done = false;

            // 1. a standard via in an island of the group
            for (int k = 0; k < graph.planes.size() && !done; ++k)
            {
                EePlane plane = graph.planes.get(k);
                List<int[]> candidates = new ArrayList<int[]>(); // {cell, room in micrometres}
                for (int iy = 0; iy < plane.ny; iy += 2)
                {
                    for (int ix = 0; ix < plane.nx; ix += 2)
                    {
                        int cell = iy * plane.nx + ix;
                        int island = plane.island[cell];
                        if (island >= 0 && graph.find(graph.island_node(k, island)) == group_root)
                        {
                            candidates.add(new int[]
                            {
                                cell, (int) (plane.other[cell] * 1000)
                            });
                        }
                    }
                }
                candidates.sort((a, b) -> Integer.compare(b[1], a[1]));
                // first choice: a position with the full clearance to the pads of the net. Such a via can stay fixed in a further pass.
                for (int choice = 0; choice < 2 && !done; ++choice)
                {
                    double own_gap = choice == 0 ? p_board.clearance_mm + ENGINE_MARGIN_MM : OWN_PAD_GAP_MM;
                    double margin = choice == 0 ? ENGINE_MARGIN_MM : VIA_MARGIN_MM;
                    for (int c = 0; c < candidates.size() && c < MAX_CANDIDATES && !done; ++c)
                    {
                        int cell = candidates.get(c)[0];
                        double x = plane.cell_x(cell % plane.nx), y = plane.cell_y(cell / plane.nx);
                        if (!reaches_main(graph, k, x, y, main_root) || !via_fits(plan, p_board, copper, p_net, x, y, via_r, null, own_gap, margin))
                        {
                            continue;
                        }
                        add_via(p_board, p_net, x, y, plan.via_diameter_mm, plan.via_hole_mm, p_records, p_added, copper, AddedVia.ISLAND, -1);
                        vias.get(vias.size() - 1).roomy = choice == 0;
                        ++island_vias_added;
                        ++s[1];
                        done = true;
                    }
                }
            }

            // 2. a short track on an outer layer, to copper of the main group or to a position for a standard via
            double[] best_end = null;
            List<double[]> best_points = null;
            int best_plane = -1;
            for (int k = 0; k < graph.planes.size() && !done && plan.rules.repair_track_mm > 0; ++k)
            {
                EePlane plane = graph.planes.get(k);
                if (!plan.is_outer(plane.area.layer))
                {
                    continue;
                }
                boolean[] main_here = main_copper.get(k);
                if (main_here == null)
                {
                    main_here = copper_cells(graph, plane, main_root);
                    main_copper.put(k, main_here);
                }
                double[] end = new double[2];
                List<double[]> points = surface_track(p_board, graph, k, group_root, main_root, main_here, copper, p_net, end);
                if (points != null && (best_points == null || end[1] < best_end[1]))
                {
                    best_points = points;
                    best_end = end;
                    best_plane = k;
                }
            }
            if (best_points != null)
            {
                String layer = graph.planes.get(best_plane).area.layer;
                double length = add_track(p_board, p_net, layer, best_points, p_records, p_added, copper);
                ++surface_tracks_added;
                ++s[5];
                s[6] += length;
                length_added += length;
                if (best_end[0] > 0)
                {
                    double[] last = best_points.get(best_points.size() - 1);
                    boolean roomy = via_fits(plan, p_board, copper, p_net, last[0], last[1], via_r, null, p_board.clearance_mm + ENGINE_MARGIN_MM,
                            ENGINE_MARGIN_MM);
                    add_via(p_board, p_net, last[0], last[1], plan.via_diameter_mm, plan.via_hole_mm, p_records, p_added, copper, AddedVia.TRACK_END, -1);
                    vias.get(vias.size() - 1).roomy = roomy;
                    ++track_vias_added;
                    ++s[7];
                }
                done = true;
            }

            // 3. a small via in a pad of the group
            for (int i = 0; i < graph.items.size() && !done && pad_vias_allowed; ++i)
            {
                EeBoard.Pad pad = graph.pad_of.get(i);
                if (pad == null || pad.through || graph.find(i) != group_root)
                {
                    continue;
                }
                for (double[] q : pad_via_positions(pad, pad_via_r))
                {
                    if (reaches_main(graph, -1, q[0], q[1], main_root) && via_fits(p_board, copper, p_net, q[0], q[1], pad_via_r, graph.items.get(i)))
                    {
                        add_via(p_board, p_net, q[0], q[1], plan.rules.pad_via_diameter_mm, plan.rules.pad_via_hole_mm, p_records, p_added, copper,
                                AddedVia.PAD, Math.hypot(q[0] - pad.cx, q[1] - pad.cy) < 1e-6 ? pad.index : -1);
                        ++pad_vias_added;
                        ++s[2];
                        done = true;
                        break;
                    }
                }
            }

            // 4. a track on an inner layer from a via of the group to the main island
            for (int k = 0; k < graph.planes.size() && !done; ++k)
            {
                EePlane plane = graph.planes.get(k);
                if (plan.is_outer(plane.area.layer))
                {
                    continue;
                }
                List<double[]> points = bridge_track(p_board, graph, k, group_root, main_root);
                if (points == null)
                {
                    continue;
                }
                double length = add_track(p_board, p_net, plane.area.layer, points, p_records, p_added, copper);
                ++tracks_added;
                length_added += length;
                ++s[3];
                s[4] += length;
                done = true;
            }
            if (done)
            {
                graph.join(group_root, main_root);
            }
        }
    }

    /** Writes one track record through the points and adds its copper. Returns the length in mm. */
    private double add_track(EeBoard p_board, String p_net, String p_layer, List<double[]> p_points, List<String> p_records, List<Copper> p_added,
            List<Copper> p_copper)
    {
        double half = p_board.track_mm / 2.0;
        StringBuilder text_points = new StringBuilder();
        double length = 0.0;
        for (int i = 0; i < p_points.size(); ++i)
        {
            double[] q = p_points.get(i);
            if (i > 0)
            {
                double[] o = p_points.get(i - 1);
                length += Math.hypot(q[0] - o[0], q[1] - o[1]);
                Copper segment = Copper.segment(o[0], o[1], q[0], q[1], half, p_layer, p_net);
                segment.routed = true;
                p_added.add(segment);
                p_copper.add(segment);
                text_points.append(' ');
            }
            text_points.append(EeResult.unit(p_board.origin_x + q[0] / EeBoard.UNIT_MM)).append(' ')
                    .append(EeResult.unit(p_board.origin_y + q[1] / EeBoard.UNIT_MM));
        }
        p_records.add("TRACK~" + EeResult.unit(p_board.track_mm / EeBoard.UNIT_MM) + "~" + p_layer + "~" + p_net + "~" + text_points + "~" + prefix
                + (next_id++) + "~0");
        return length;
    }

    /** Positions for a via in a pad: the centre first, then points of the pad where the whole via stays on the pad. */
    private static List<double[]> pad_via_positions(EeBoard.Pad p_pad, double p_r)
    {
        List<double[]> result = new ArrayList<double[]>();
        result.add(new double[]
        {
            p_pad.cx, p_pad.cy
        });
        if (p_pad.polygon == null)
        {
            return result;
        }
        double[] box = p_pad.box();
        List<double[]> more = new ArrayList<double[]>();
        for (double y = p_pad.cy - 1.0; y <= p_pad.cy + 1.0; y += 0.05)
        {
            for (double x = p_pad.cx - 1.0; x <= p_pad.cx + 1.0; x += 0.05)
            {
                if (x - p_r < box[0] || x + p_r > box[2] || y - p_r < box[1] || y + p_r > box[3] || Math.hypot(x - p_pad.cx, y - p_pad.cy) < 0.02)
                {
                    continue;
                }
                boolean on_pad = true;
                for (int a = 0; a < 8 && on_pad; ++a)
                {
                    on_pad = EeCheck.inside(p_pad.polygon, x + p_r * Math.cos(a * Math.PI / 4), y + p_r * Math.sin(a * Math.PI / 4));
                }
                if (on_pad)
                {
                    more.add(new double[]
                    {
                        x, y
                    });
                }
            }
        }
        more.sort((a, b) -> Double.compare(Math.hypot(a[0] - p_pad.cx, a[1] - p_pad.cy), Math.hypot(b[0] - p_pad.cx, b[1] - p_pad.cy)));
        result.addAll(more);
        return result;
    }

    /** The cells of the plane grid that lie on copper of the group p_root on the layer of the plane. */
    private static boolean[] copper_cells(EeNet p_graph, EePlane p_plane, int p_root)
    {
        boolean[] result = new boolean[p_plane.nx * p_plane.ny];
        for (int i = 0; i < p_graph.items.size(); ++i)
        {
            Copper item = p_graph.items.get(i);
            if (p_graph.find(i) != p_root || (item.layer != null && !item.layer.equals(p_plane.area.layer)))
            {
                continue;
            }
            int ix0 = Math.max(0, p_plane.cell_of_x(item.bx0)), ix1 = Math.min(p_plane.nx - 1, p_plane.cell_of_x(item.bx1));
            int iy0 = Math.max(0, p_plane.cell_of_y(item.by0)), iy1 = Math.min(p_plane.ny - 1, p_plane.cell_of_y(item.by1));
            for (int iy = iy0; iy <= iy1; ++iy)
            {
                for (int ix = ix0; ix <= ix1; ++ix)
                {
                    if (EePlane.distance(item, p_plane.cell_x(ix), p_plane.cell_y(iy)) <= 0.0)
                    {
                        result[iy * p_plane.nx + ix] = true;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Shortest path on the outer layer of plane p_k from a pad or a via of the group to copper of the main group on
     * that layer, or to a position where a standard via fits and stands in an island of the main group on a different layer.
     *
     * @param p_end result: [0] is 1 when the track ends at a position for a new via, [1] is the number of grid steps
     * @return the corner points of the track, or null
     */
    private List<double[]> surface_track(EeBoard p_board, EeNet p_graph, int p_k, int p_group_root, int p_main_root, boolean[] p_main_here,
            List<Copper> p_copper, String p_net, double[] p_end)
    {
        EePlane plane = p_graph.planes.get(p_k);
        String layer = plane.area.layer;
        double half = p_board.track_mm / 2.0;
        double need = half + p_board.clearance_mm + GRID_MARGIN_MM;
        double edge_need = half + plan.rules.copper_to_edge_mm;
        double via_r = plan.via_diameter_mm / 2.0;
        double via_need = via_r + p_board.clearance_mm + VIA_MARGIN_MM;
        int max_steps = (int) (plan.rules.repair_track_mm / EePlane.STEP);
        int cells = plane.nx * plane.ny;
        Map<Integer, Integer> previous = new HashMap<Integer, Integer>();
        Map<Integer, Integer> steps = new HashMap<Integer, Integer>();
        Map<Integer, Integer> seed_item = new HashMap<Integer, Integer>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<Integer>();
        byte[] edge_ok = new byte[cells];
        for (int i = 0; i < p_graph.items.size(); ++i)
        {
            Copper item = p_graph.items.get(i);
            EeBoard.Pad pad = p_graph.pad_of.get(i);
            if (p_graph.find(i) != p_group_root || (item.layer != null && !item.layer.equals(layer)) || (pad == null && item.kind != Copper.DISC))
            {
                continue;
            }
            int cell = plane.cell_of(pad != null ? pad.cx : item.x0, pad != null ? pad.cy : item.y0);
            if (cell < 0 || !plane.inside[cell] || previous.containsKey(cell))
            {
                continue;
            }
            previous.put(cell, -1);
            steps.put(cell, 0);
            seed_item.put(cell, i);
            queue.add(cell);
            // The track may leave the pad at each point of the pad where it keeps the clearance.
            if (pad != null)
            {
                int ix0 = Math.max(0, plane.cell_of_x(item.bx0)), ix1 = Math.min(plane.nx - 1, plane.cell_of_x(item.bx1));
                int iy0 = Math.max(0, plane.cell_of_y(item.by0)), iy1 = Math.min(plane.ny - 1, plane.cell_of_y(item.by1));
                for (int iy = iy0; iy <= iy1; ++iy)
                {
                    for (int ix = ix0; ix <= ix1; ++ix)
                    {
                        int c = iy * plane.nx + ix;
                        if (!previous.containsKey(c) && EePlane.distance(item, plane.cell_x(ix), plane.cell_y(iy)) <= 0.0
                                && passable(plane, c, need, edge_need, edge_ok, p_board))
                        {
                            previous.put(c, cell);
                            steps.put(c, 1);
                            queue.add(c);
                        }
                    }
                }
            }
        }
        int found = -1;
        boolean with_via = false;
        while (!queue.isEmpty() && found < 0)
        {
            int cell = queue.poll();
            int count = steps.get(cell);
            if (count >= max_steps)
            {
                continue;
            }
            int ix = cell % plane.nx, iy = cell / plane.nx;
            for (int d = 0; d < 8 && found < 0; ++d)
            {
                int jx = ix + DX[d], jy = iy + DY[d];
                if (jx < 0 || jy < 0 || jx >= plane.nx || jy >= plane.ny)
                {
                    continue;
                }
                int next = jy * plane.nx + jx;
                if (previous.containsKey(next) || !passable(plane, next, need, edge_need, edge_ok, p_board))
                {
                    continue;
                }
                if (d >= 4 && (!passable(plane, iy * plane.nx + jx, need, edge_need, edge_ok, p_board)
                        || !passable(plane, jy * plane.nx + ix, need, edge_need, edge_ok, p_board)))
                {
                    continue;
                }
                previous.put(next, cell);
                steps.put(next, count + 1);
                queue.add(next);
                int island = plane.island[next];
                if (p_main_here[next] || (island >= 0 && p_graph.find(p_graph.island_node(p_k, island)) == p_main_root))
                {
                    found = next;
                }
                else if (jx % 2 == 0 && jy % 2 == 0 && plane.other[next] >= via_need)
                {
                    double x = plane.cell_x(jx), y = plane.cell_y(jy);
                    if (reaches_main(p_graph, p_k, x, y, p_main_root) && via_fits(p_board, p_copper, p_net, x, y, via_r, null))
                    {
                        found = next;
                        with_via = true;
                    }
                }
            }
        }
        if (found < 0)
        {
            return null;
        }
        List<Integer> path = new ArrayList<Integer>();
        for (int cell = found; cell >= 0; cell = previous.get(cell))
        {
            path.add(0, cell);
        }
        int item_index = seed_item.get(path.get(0));
        Copper item = p_graph.items.get(item_index);
        EeBoard.Pad pad = p_graph.pad_of.get(item_index);
        List<double[]> points = new ArrayList<double[]>();
        points.add(new double[]
        {
            pad != null ? pad.cx : item.x0, pad != null ? pad.cy : item.y0
        });
        for (int i = 1; i < path.size(); ++i)
        {
            int cell = path.get(i);
            boolean corner = i == 1 || i == path.size() - 1;
            if (!corner)
            {
                int a = path.get(i - 1), b = path.get(i + 1);
                corner = (cell % plane.nx - a % plane.nx) != (b % plane.nx - cell % plane.nx)
                        || (cell / plane.nx - a / plane.nx) != (b / plane.nx - cell / plane.nx);
            }
            if (corner)
            {
                points.add(new double[]
                {
                    plane.cell_x(cell % plane.nx), plane.cell_y(cell / plane.nx)
                });
            }
        }
        // exact test of each segment against the copper of other nets on the layer
        for (int i = 1; i < points.size(); ++i)
        {
            double[] o = points.get(i - 1), q = points.get(i);
            Copper segment = Copper.segment(o[0], o[1], q[0], q[1], half, layer, p_net);
            for (Copper c : p_copper)
            {
                if (c.net.equals(p_net) && !c.net.isEmpty())
                {
                    continue;
                }
                double gap = p_board.clearance_mm;
                double[] special = p_board.special_nets.get(c.net);
                if (special != null && special.length > 1)
                {
                    gap = Math.max(gap, special[1]);
                }
                if (segment.shares_layer(c) && !segment.far_from(c, gap + 0.01) && segment.distance(c) < gap + 0.002)
                {
                    return null;
                }
            }
        }
        p_end[0] = with_via ? 1 : 0;
        p_end[1] = path.size();
        return points;
    }

    /**
     * true: a via at the point stands in an island of the main group on a layer other than the one of plane p_except.
     * The via then joins its own island to the main group.
     */
    static boolean reaches_main(EeNet p_graph, int p_except, double p_x, double p_y, int p_main_root)
    {
        for (int m = 0; m < p_graph.planes.size(); ++m)
        {
            if (m == p_except)
            {
                continue;
            }
            EePlane other = p_graph.planes.get(m);
            int cell = other.cell_of(p_x, p_y);
            if (cell >= 0 && other.island[cell] >= 0 && p_graph.find(p_graph.island_node(m, other.island[cell])) == p_main_root)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * true: a via of the net at the point keeps the clearance to all copper of other nets on all layers, to the
     * keep-outs and to the board edge. A standard via also keeps a gap to surface-mount pads of its own net.
     *
     * @param p_own_pad the pad that the via stands in, or null for a standard via
     */
    private boolean via_fits(EeBoard p_board, List<Copper> p_copper, String p_net, double p_x, double p_y, double p_r, Copper p_own_pad)
    {
        return via_fits(plan, p_board, p_copper, p_net, p_x, p_y, p_r, p_own_pad);
    }

    static boolean via_fits(EePlan plan, EeBoard p_board, List<Copper> p_copper, String p_net, double p_x, double p_y, double p_r, Copper p_own_pad)
    {
        return via_fits(plan, p_board, p_copper, p_net, p_x, p_y, p_r, p_own_pad, OWN_PAD_GAP_MM, VIA_MARGIN_MM);
    }

    /**
     * @param p_own_gap a standard via keeps this distance from the surface-mount pads of its net
     * @param p_margin  added to the clearance to copper of other nets
     */
    static boolean via_fits(EePlan plan, EeBoard p_board, List<Copper> p_copper, String p_net, double p_x, double p_y, double p_r, Copper p_own_pad,
            double p_own_gap, double p_margin)
    {
        if (EePlan.distance_to_edge(p_board.outline, p_x, p_y) < p_r + plan.rules.copper_to_edge_mm + VIA_MARGIN_MM)
        {
            return false;
        }
        double reach = p_r + Math.max(p_board.clearance_mm, 0.3) + 0.05;
        for (Copper c : p_copper)
        {
            if (c.bx0 - reach > p_x || c.bx1 + reach < p_x || c.by0 - reach > p_y || c.by1 + reach < p_y)
            {
                continue;
            }
            double d = EePlane.distance(c, p_x, p_y) - p_r;
            if (!c.net.equals(p_net) || c.net.isEmpty())
            {
                double need = p_board.clearance_mm;
                double[] special = p_board.special_nets.get(c.net);
                if (special != null && special.length > 1)
                {
                    need = Math.max(need, special[1]);
                }
                if (d < need + p_margin)
                {
                    return false;
                }
            }
            else if (p_own_pad == null && c.layer != null && c.label.startsWith("pad ") && d < p_own_gap)
            {
                return false;
            }
            else if (c.kind == Copper.DISC && c.layer == null && d <= -p_r + 1e-9)
            {
                return false; // the centre is inside a via or a hole pad of the net: no second via there
            }
        }
        return true;
    }

    private void add_via(EeBoard p_board, String p_net, double p_x, double p_y, double p_diameter, double p_hole, List<String> p_records,
            List<Copper> p_added, List<Copper> p_copper, int p_kind, int p_pad_index)
    {
        AddedVia record = new AddedVia();
        record.kind = p_kind;
        record.net = p_net;
        record.x = p_x;
        record.y = p_y;
        record.diameter = p_diameter;
        record.pad_index = p_pad_index;
        vias.add(record);
        p_records.add("VIA~" + EeResult.unit(p_board.origin_x + p_x / EeBoard.UNIT_MM) + "~" + EeResult.unit(p_board.origin_y + p_y / EeBoard.UNIT_MM) + "~"
                + EeResult.unit(p_diameter / EeBoard.UNIT_MM) + "~" + p_net + "~" + EeResult.unit(p_hole / 2.0 / EeBoard.UNIT_MM) + "~" + prefix + (next_id++)
                + "~0");
        Copper via = Copper.disc(p_x, p_y, p_diameter / 2.0, null, p_net);
        via.routed = true;
        p_added.add(via);
        p_copper.add(via);
    }

    /**
     * Shortest path on the layer of plane p_k from a via or a pad with a hole of the group to an island of the main group.
     *
     * @return the corner points of the track, or null
     */
    private List<double[]> bridge_track(EeBoard p_board, EeNet p_graph, int p_k, int p_group_root, int p_main_root)
    {
        EePlane plane = p_graph.planes.get(p_k);
        double half = p_board.track_mm / 2.0;
        double need = half + p_board.clearance_mm + GRID_MARGIN_MM;
        double edge_need = half + plan.rules.copper_to_edge_mm;
        int cells = plane.nx * plane.ny;
        boolean[] seen = new boolean[cells];
        int[] previous = new int[cells];
        int[] queue = new int[cells];
        byte[] edge_ok = new byte[cells];
        int head = 0, tail = 0;
        Map<Integer, Integer> seed_item = new HashMap<Integer, Integer>();
        for (int i = 0; i < p_graph.items.size(); ++i)
        {
            Copper item = p_graph.items.get(i);
            if (!p_graph.through.get(i) || p_graph.find(i) != p_group_root)
            {
                continue;
            }
            EeBoard.Pad pad = p_graph.pad_of.get(i);
            double cx = pad != null ? pad.cx : item.x0, cy = pad != null ? pad.cy : item.y0;
            int cell = plane.cell_of(cx, cy);
            if (cell < 0 || !plane.inside[cell] || seen[cell])
            {
                continue;
            }
            seen[cell] = true;
            previous[cell] = -1;
            queue[tail++] = cell;
            seed_item.put(cell, i);
            // Inside its own copper a track adds nothing: cells there are free.
            double free = (item.kind == Copper.DISC ? item.r : 0.0) - half;
            int reach = (int) Math.floor(free / EePlane.STEP);
            for (int dy = -reach; dy <= reach && free > 0; ++dy)
            {
                for (int dx = -reach; dx <= reach; ++dx)
                {
                    int ix = cell % plane.nx + dx, iy = cell / plane.nx + dy;
                    if (ix < 0 || iy < 0 || ix >= plane.nx || iy >= plane.ny || Math.hypot(dx, dy) * EePlane.STEP > free)
                    {
                        continue;
                    }
                    int c = iy * plane.nx + ix;
                    if (!seen[c])
                    {
                        seen[c] = true;
                        previous[c] = cell;
                        queue[tail++] = c;
                    }
                }
            }
        }
        int found = -1;
        while (head < tail && found < 0)
        {
            int cell = queue[head++];
            int ix = cell % plane.nx, iy = cell / plane.nx;
            for (int d = 0; d < 8 && found < 0; ++d)
            {
                int jx = ix + DX[d], jy = iy + DY[d];
                if (jx < 0 || jy < 0 || jx >= plane.nx || jy >= plane.ny)
                {
                    continue;
                }
                int next = jy * plane.nx + jx;
                if (seen[next] || !passable(plane, next, need, edge_need, edge_ok, p_board))
                {
                    continue;
                }
                if (d >= 4 && (!passable(plane, iy * plane.nx + jx, need, edge_need, edge_ok, p_board)
                        || !passable(plane, jy * plane.nx + ix, need, edge_need, edge_ok, p_board)))
                {
                    continue;
                }
                seen[next] = true;
                previous[next] = cell;
                queue[tail++] = next;
                int island = plane.island[next];
                if (island >= 0 && p_graph.find(p_graph.island_node(p_k, island)) == p_main_root)
                {
                    found = next;
                }
            }
        }
        if (found < 0)
        {
            return null;
        }
        List<Integer> path = new ArrayList<Integer>();
        for (int cell = found; cell >= 0; cell = previous[cell])
        {
            path.add(0, cell);
        }
        int item_index = seed_item.get(path.get(0));
        Copper item = p_graph.items.get(item_index);
        EeBoard.Pad pad = p_graph.pad_of.get(item_index);
        List<double[]> points = new ArrayList<double[]>();
        points.add(new double[]
        {
            pad != null ? pad.cx : item.x0, pad != null ? pad.cy : item.y0
        });
        for (int i = 1; i < path.size(); ++i)
        {
            int cell = path.get(i);
            boolean corner = i == 1 || i == path.size() - 1;
            if (!corner)
            {
                int a = path.get(i - 1), b = path.get(i + 1);
                corner = (cell % plane.nx - a % plane.nx) != (b % plane.nx - cell % plane.nx)
                        || (cell / plane.nx - a / plane.nx) != (b / plane.nx - cell / plane.nx);
            }
            if (corner)
            {
                points.add(new double[]
                {
                    plane.cell_x(cell % plane.nx), plane.cell_y(cell / plane.nx)
                });
            }
        }
        return points;
    }

    /** true: a track of the net of the plane may have its centre line on the cell. */
    private static boolean passable(EePlane p_plane, int p_cell, double p_need, double p_edge_need, byte[] p_edge_ok, EeBoard p_board)
    {
        if (!p_plane.inside[p_cell] || p_plane.other[p_cell] < p_need)
        {
            return false;
        }
        if (p_edge_ok[p_cell] == 0)
        {
            double x = p_plane.cell_x(p_cell % p_plane.nx), y = p_plane.cell_y(p_cell / p_plane.nx);
            p_edge_ok[p_cell] = (byte) (EePlan.distance_to_edge(p_board.outline, x, y) >= p_edge_need ? 1 : 2);
        }
        return p_edge_ok[p_cell] == 1;
    }
}
