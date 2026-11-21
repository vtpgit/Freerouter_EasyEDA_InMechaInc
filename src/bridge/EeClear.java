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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks of a board file that do not use the engine:
 * - exact clearance between copper of different nets on each layer
 * - distance of tracks and vias from the board edge
 * - tracks and vias inside a keep-out of the plan
 * - via sizes, smallest track width, ids that occur two times
 *
 * The bridge runs them on the result file after it was written, not on the data in memory.
 * Copper areas are not checked: the editor builds their fill.
 */
final class EeClear
{
    /** The engine and the editor round to different grids. A gap that is this much too small is not an error. */
    static final double TOLERANCE_MM = 0.002;

    /** Clearance errors that involve a track or a via of the board. */
    int routed_errors = 0;
    /** Clearance errors between pads or copper regions of footprints. They come from the footprints or the placement. */
    int placement_errors = 0;
    int edge_errors = 0;
    int keepout_errors = 0;
    int duplicate_ids = 0;
    double min_track_mm = Double.MAX_VALUE;
    final Set<String> via_sizes = new TreeSet<String>();
    /** The first errors as text. */
    final List<String> examples = new ArrayList<String>();

    EeClear(EeBoard p_board, EePlan p_plan)
    {
        double clearance = p_board.clearance_mm;
        List<Copper> items = Copper.of_footprints(p_board);
        List<Copper> routes = Copper.of_routes(p_board);
        items.addAll(routes);
        // Pads that lie on copper without net of their own footprint, for example the feed pad of an antenna.
        // A track to such a pad touches that copper. This is the connection and not an error.
        List<Copper> footprint_copper = new ArrayList<Copper>(items.subList(0, items.size() - routes.size()));
        List<Copper[]> contacts = new ArrayList<Copper[]>(); // {pad, copper without net}
        for (Copper pad : footprint_copper)
        {
            if (pad.net.isEmpty() || !pad.label.startsWith("pad "))
            {
                continue;
            }
            for (Copper bare : footprint_copper)
            {
                if (bare.net.isEmpty() && bare.ref.equals(pad.ref) && pad.shares_layer(bare) && !pad.far_from(bare, 0.0) && pad.distance(bare) <= 1e-6)
                {
                    contacts.add(new Copper[]
                    {
                        pad, bare
                    });
                }
            }
        }
        Collections.sort(items, (a, b) -> Double.compare(a.bx0, b.bx0));
        int n = items.size();
        for (int i = 0; i < n; ++i)
        {
            Copper a = items.get(i);
            for (int j = i + 1; j < n; ++j)
            {
                Copper b = items.get(j);
                if (b.bx0 > a.bx1 + clearance)
                {
                    break;
                }
                if (!a.routed && !b.routed && a.ref.equals(b.ref))
                {
                    continue; // inside one footprint: a fact of the library
                }
                if (a.net.equals(b.net) && !a.net.isEmpty())
                {
                    continue;
                }
                if (a.net.isEmpty() && b.net.isEmpty() && !a.routed && !b.routed)
                {
                    continue;
                }
                if (!a.shares_layer(b) || a.far_from(b, clearance))
                {
                    continue;
                }
                double need = clearance;
                double[] special_a = p_board.special_nets.get(a.net);
                double[] special_b = p_board.special_nets.get(b.net);
                if (special_a != null && special_a.length > 1)
                {
                    need = Math.max(need, special_a[1]);
                }
                if (special_b != null && special_b.length > 1)
                {
                    need = Math.max(need, special_b[1]);
                }
                double d = a.distance(b);
                if (d < need - TOLERANCE_MM && (a.routed != b.routed))
                {
                    Copper routed = a.routed ? a : b;
                    Copper fixed = a.routed ? b : a;
                    boolean contact = false;
                    for (Copper[] c : contacts)
                    {
                        contact |= c[1] == fixed && c[0].net.equals(routed.net) && routed.distance(c[0]) <= need;
                    }
                    if (contact)
                    {
                        continue;
                    }
                }
                if (d < need - TOLERANCE_MM)
                {
                    if (a.routed || b.routed)
                    {
                        ++routed_errors;
                        if (examples.size() < 12)
                        {
                            examples.add(String.format(Locale.ROOT, "%s and %s are %.3f mm apart at x %.2f y %.2f. The rule is %.3f mm.", a.label,
                                    b.label.equals(a.label) ? "a second one" : b.label, d, (Math.max(a.bx0, b.bx0) + Math.min(a.bx1, b.bx1)) / 2.0,
                                    (Math.max(a.by0, b.by0) + Math.min(a.by1, b.by1)) / 2.0, need));
                        }
                    }
                    else
                    {
                        ++placement_errors;
                    }
                }
            }
        }

        // board edge and keep-outs
        double edge = p_plan.rules.copper_to_edge_mm;
        int outline_points = p_board.outline.length / 2;
        for (Copper c : routes)
        {
            double to_edge = Double.MAX_VALUE;
            for (int i = 0; i < outline_points; ++i)
            {
                int j = (i + 1) % outline_points;
                to_edge = Math.min(to_edge, EeCheck.segment_distance(p_board.outline[2 * i], p_board.outline[2 * i + 1], p_board.outline[2 * j],
                        p_board.outline[2 * j + 1], c.x0, c.y0, c.x1, c.y1));
            }
            boolean outside = outline_points >= 3 && !EeCheck.inside(p_board.outline, c.x0, c.y0);
            if (outside || to_edge - c.r < edge - TOLERANCE_MM)
            {
                ++edge_errors;
                if (examples.size() < 12)
                {
                    examples.add(String.format(Locale.ROOT, "%s is %.3f mm from the board edge at x %.2f y %.2f. The rule is %.3f mm.", c.label,
                            outside ? 0.0 : Math.max(0.0, to_edge - c.r), c.x0, c.y0, edge));
                }
            }
            for (EePlan.Keepout k : p_plan.keepouts)
            {
                if (c.layer != null && !c.layer.equals(k.layer))
                {
                    continue;
                }
                if (c.bx1 < k.x0 || c.bx0 > k.x1 || c.by1 < k.y0 || c.by0 > k.y1)
                {
                    continue;
                }
                Copper box = Copper.polygon(new double[]
                {
                    k.x0, k.y0, k.x1, k.y0, k.x1, k.y1, k.x0, k.y1
                }, k.layer, "");
                if (box.centre_distance(c) - c.r < -TOLERANCE_MM)
                {
                    ++keepout_errors;
                    if (examples.size() < 12)
                    {
                        examples.add(String.format(Locale.ROOT, "%s is inside a keep-out at x %.2f y %.2f.", c.label, c.x0, c.y0));
                    }
                    break;
                }
            }
        }

        for (EeBoard.Track track : p_board.tracks)
        {
            if (p_board.layers.contains(track.layer))
            {
                min_track_mm = Math.min(min_track_mm, track.width);
            }
        }
        for (EeBoard.Via via : p_board.vias)
        {
            via_sizes.add(String.format(Locale.ROOT, "%.3f mm with %.2f mm hole", via.diameter, via.hole));
        }
        Set<String> ids = new HashSet<String>();
        for (String record : p_board.records)
        {
            String id = null;
            String[] f = record.split("#@\\$", 2)[0].split("~", -1);
            if (record.startsWith("TRACK~") && f.length > 5)
            {
                id = f[5];
            }
            else if (record.startsWith("VIA~") && f.length > 6)
            {
                id = f[6];
            }
            else if (record.startsWith("LIB~") && f.length > 6)
            {
                id = f[6];
            }
            else if (record.startsWith("COPPERAREA~") && f.length > 7)
            {
                id = f[7];
            }
            if (id != null && !id.isEmpty() && !ids.add(id))
            {
                ++duplicate_ids;
            }
        }
    }
}
