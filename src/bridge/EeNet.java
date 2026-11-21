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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The copper of one net of a board file and what is joined to what.
 *
 * Joined are: copper of the net that touches on the same layer, and copper of the net that an island of a plane
 * area of the net reaches (see EePlane). A net is complete when all its pads are in one group.
 */
final class EeNet
{
    private static final double EPS = 1e-6;

    final String net;
    /** Pads, tracks, vias and copper regions of the net. */
    final List<Copper> items = new ArrayList<Copper>();
    /** The pad of an item, or null. Same index as items. */
    final List<EeBoard.Pad> pad_of = new ArrayList<EeBoard.Pad>();
    /** true: the item has copper on all layers (a via or a pad with a hole). Same index as items. */
    final List<Boolean> through = new ArrayList<Boolean>();
    /** The fill models of the plane areas of the net. */
    final List<EePlane> planes = new ArrayList<EePlane>();
    /** First node number of the islands of each plane. */
    private final int[] island_base;
    private final int[] parent;

    /**
     * @param p_copper      all copper of the board and the keep-outs as blockers. Used for the fill of the planes.
     * @param p_locked_only true: tracks and vias that are not locked are left out
     */
    EeNet(EeBoard p_board, EePlan p_plan, String p_net, List<Copper> p_copper, boolean p_locked_only)
    {
        this.net = p_net;
        for (EeBoard.Pad pad : p_board.pads)
        {
            if (!p_net.equals(pad.net))
            {
                continue;
            }
            Copper c = pad.polygon != null ? Copper.polygon(pad.polygon, pad.through ? null : pad.layer, pad.net)
                    : Copper.disc(pad.cx, pad.cy, pad.radius, pad.through ? null : pad.layer, pad.net);
            c.label = "pad " + pad.ref + "." + pad.number;
            add(c, pad, pad.through);
        }
        List<EeBoard.Track> tracks = new ArrayList<EeBoard.Track>();
        for (EeBoard.Track track : p_board.tracks)
        {
            if (track.locked || !p_locked_only)
            {
                tracks.add(track);
            }
        }
        tracks.addAll(p_board.footprint_tracks);
        for (EeBoard.Track track : tracks)
        {
            if (!p_net.equals(track.net) || !p_board.layers.contains(track.layer))
            {
                continue;
            }
            for (int i = 0; i + 3 < track.points.length; i += 2)
            {
                add(Copper.segment(track.points[i], track.points[i + 1], track.points[i + 2], track.points[i + 3], track.width / 2.0, track.layer,
                        track.net), null, false);
            }
        }
        for (EeBoard.Via via : p_board.vias)
        {
            if (p_net.equals(via.net) && (via.locked || !p_locked_only))
            {
                Copper c = Copper.disc(via.x, via.y, via.diameter / 2.0, null, via.net);
                c.label = "via";
                add(c, null, true);
            }
        }
        for (EeBoard.Region solid : p_board.solids)
        {
            if (p_net.equals(solid.net) && solid.points.length >= 6)
            {
                add(Copper.polygon(solid.points, solid.layer, solid.net), null, false);
            }
        }

        List<EeBoard.Region> areas = p_plan.fill_areas.get(p_net);
        int nodes = items.size();
        island_base = new int[areas == null ? 0 : areas.size()];
        if (areas != null)
        {
            for (int k = 0; k < areas.size(); ++k)
            {
                EePlane plane = new EePlane(p_board, areas.get(k), p_copper);
                planes.add(plane);
                island_base[k] = nodes;
                nodes += plane.island_count;
            }
        }
        parent = new int[nodes];
        for (int i = 0; i < nodes; ++i)
        {
            parent[i] = i;
        }
        int n = items.size();
        for (int i = 0; i < n; ++i)
        {
            Copper a = items.get(i);
            for (int j = i + 1; j < n; ++j)
            {
                Copper b = items.get(j);
                if (a.far_from(b, EPS) || !a.shares_layer(b) || find(i) == find(j))
                {
                    continue;
                }
                if (a.distance(b) <= EPS)
                {
                    join(i, j);
                }
            }
            for (int k = 0; k < planes.size(); ++k)
            {
                for (int island : planes.get(k).islands_at(a))
                {
                    join(i, island_base[k] + island);
                }
            }
        }
    }

    /** true: a piece of fill of an area of the net on the layer reaches the item. */
    boolean fill_reaches(int p_item, String p_layer)
    {
        for (int k = 0; k < planes.size(); ++k)
        {
            if (planes.get(k).area.layer.equals(p_layer) && !planes.get(k).islands_at(items.get(p_item)).isEmpty())
            {
                return true;
            }
        }
        return false;
    }

    private void add(Copper p_c, EeBoard.Pad p_pad, boolean p_through)
    {
        items.add(p_c);
        pad_of.add(p_pad);
        through.add(p_through);
    }

    int find(int p_i)
    {
        while (parent[p_i] != p_i)
        {
            parent[p_i] = parent[parent[p_i]];
            p_i = parent[p_i];
        }
        return p_i;
    }

    void join(int p_a, int p_b)
    {
        parent[find(p_a)] = find(p_b);
    }

    /** Node number of an island of a plane. */
    int island_node(int p_plane, int p_island)
    {
        return island_base[p_plane] + p_island;
    }

    /** Group (root node) to its pads. Groups without a pad are not in the map. */
    Map<Integer, List<EeBoard.Pad>> pad_groups()
    {
        Map<Integer, List<EeBoard.Pad>> groups = new LinkedHashMap<Integer, List<EeBoard.Pad>>();
        for (int i = 0; i < items.size(); ++i)
        {
            if (pad_of.get(i) != null)
            {
                groups.computeIfAbsent(find(i), key -> new ArrayList<EeBoard.Pad>()).add(pad_of.get(i));
            }
        }
        return groups;
    }

    /** The root node of the group with the most pads, or -1. */
    int main_group()
    {
        int best = -1, size = 0;
        for (Map.Entry<Integer, List<EeBoard.Pad>> entry : pad_groups().entrySet())
        {
            if (entry.getValue().size() > size)
            {
                size = entry.getValue().size();
                best = entry.getKey();
            }
        }
        return best;
    }

    /** All copper of a board and the keep-outs of the plan as blockers, for the fill of the planes. */
    static List<Copper> copper_of(EeBoard p_board, EePlan p_plan, boolean p_locked_only)
    {
        List<Copper> all = Copper.of_footprints(p_board);
        for (Copper c : Copper.of_routes(p_board))
        {
            all.add(c);
        }
        if (p_locked_only)
        {
            all = Copper.of_footprints(p_board);
            for (EeBoard.Track track : p_board.tracks)
            {
                if (track.locked && p_board.layers.contains(track.layer))
                {
                    for (int i = 0; i + 3 < track.points.length; i += 2)
                    {
                        all.add(Copper.segment(track.points[i], track.points[i + 1], track.points[i + 2], track.points[i + 3], track.width / 2.0,
                                track.layer, track.net));
                    }
                }
            }
            for (EeBoard.Via via : p_board.vias)
            {
                if (via.locked)
                {
                    all.add(Copper.disc(via.x, via.y, via.diameter / 2.0, null, via.net));
                }
            }
        }
        for (EePlan.Keepout k : p_plan.keepouts)
        {
            Copper box = Copper.polygon(new double[]
            {
                k.x0, k.y0, k.x1, k.y0, k.x1, k.y1, k.x0, k.y1
            }, k.layer, "");
            box.keepout_box = true;
            all.add(box);
        }
        for (EePlan.KeepoutCircle c : p_plan.hole_keepouts)
        {
            all.add(Copper.disc(c.x, c.y, c.radius, null, ""));
        }
        return all;
    }
}
