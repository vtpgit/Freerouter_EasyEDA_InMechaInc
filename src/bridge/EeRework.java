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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Input for a further pass of the engine on a board that it has routed.
 *
 * The engine does not connect a pad that lies in a copper area of its net (a pour pad). Tracks of other nets can
 * cut such a pad off from the fill, and tracks on the other layers can take the place of each via that would
 * repair it. The engine does not move tracks to make room for a via. The bridge does that part:
 * 1. For each group of pads that is not joined to its net after the repair (EeBridge), one pad gets a via in the
 *    pad. The via must keep the clearance to all copper that cannot move (pads, holes, keep-outs, locked tracks).
 * 2. The tracks and vias of other nets that are too near to such a via are left out of the result of the last pass.
 * 3. The engine gets the rest of that result as fixed tracks and routes the connections that are open now in the
 *    free space. On the test board this took 30 s to 130 s and connected 14 to 18 of 16 to 20 open items. With the
 *    tracks not fixed (all of them, or those within 1 mm or 2.5 mm of a place of work) the engine took tracks out
 *    all over the board and had more open connections after 100 s than with fixed tracks. That form is not used.
 * A group where no pad can take a via goes back into its net. The engine then tries to connect it by its own means.
 *
 * Routes of other runs (recombination). The runs of a job leave different connections open. For a net without copper
 * area that is open in this result and that has fewer open connections in the result of another run, the bridge
 * forms a group of nets: the open net, then each net that has a track in the way of the routes taken so far (as
 * they are in the other run), until no track of a net outside the group is in the way. The routes of all nets of
 * the group come from the other run. So the engine has no track to route again, and the count of open connections
 * of the group is the count of the other run. A group is taken when that count is lower.
 * Only tracks of nets with copper areas can be pushed out (MAX_IN_THE_WAY), because such a net cannot change runs:
 * its pads in the copper areas and its repair vias belong to this result.
 * A via in a pad that an earlier pass added for this result stays. A group with a route too near to it is not taken.
 * A via that the repair added after an earlier pass gives way, and the repair after the pass sets its vias again.
 *
 * A first form of this step took the route of the open net only, took out the tracks of other nets in its way, and
 * let the engine route them again. Measured on one board in 10 passes: the engine routed again only a part of these
 * tracks. 2 passes ended with 1 open connection less than the result before. The other 8 ended with 1 to 7 more
 * than the result before or than a pass without routes on the same result.
 * A closed group that took out 2 tracks of the supply net ended with 13 open against 11 without the group.
 * A pass with two closed groups that took out nothing ended with 10 open against 11 without the groups.
 *
 * Wire types of the engine input: "protect" and "route" are both read as fixed. "normal" is not fixed. The engine
 * output marks every fixed item with "protect". So the output of a further pass marks nearly all items. The input of
 * the next pass takes every item of the output that the new input does not hold already.
 */
final class EeRework
{
    /** The engine works with an octagon around each via. A new via in a pad keeps this added distance to copper of other nets. */
    private static final double PAD_VIA_MARGIN_MM = 0.022;

    /** The plan of the next pass. */
    final EePlan plan;
    final EeDsn dsn;
    /** The board text for the engine: the text of dsn with the tracks of the last pass as fixed tracks. */
    final String text;
    /** Tracks and vias of the last pass in the input. */
    int carried = 0;
    int via_pads = 0;
    int net_pads = 0;
    int wires_removed = 0;
    int vias_removed = 0;
    int nets_touched = 0;
    /** Groups of nets that got their routes from another run, and the number of nets in these groups. */
    int routes_taken = 0;
    int nets_taken = 0;
    /** Tracks and vias of other nets that were in the way of these routes. */
    int routes_in_the_way = 0;
    /** Lines for the report. */
    final List<String> lines = new ArrayList<String>();

    private static final boolean DEBUG = Boolean.getBoolean("bridge.test.routes_debug");
    /** A group of nets is taken when this number of tracks and vias of nets with copper areas, or fewer, are in the way of its routes. */
    private static final int MAX_IN_THE_WAY = Integer.getInteger("bridge.test.max_in_the_way", 0);
    /** The largest number of nets in one group. */
    private static final int MAX_GROUP_NETS = Integer.getInteger("bridge.test.max_group_nets", 40);
    /** The largest number of groups in one pass, and of tracks and vias of nets with copper areas that they push out together. */
    private static final int MAX_ROUTES = 6;
    private static final int MAX_IN_THE_WAY_TOTAL = Integer.getInteger("bridge.test.max_in_the_way_total", 0);
    /** Added distance between a route from another run and the copper that stays: track to track, and with a via. */
    private static final double ROUTE_MARGIN_MM = 0.01;
    private static final double ROUTE_VIA_MARGIN_MM = 0.04;

    /** The result of another run of the job. */
    static final class Donor
    {
        final String label;
        final EePlan plan;
        final EeDsn dsn;
        final String ses;
        final EeCheck check;

        Donor(String p_label, EePlan p_plan, EeDsn p_dsn, String p_ses, EeCheck p_check)
        {
            this.label = p_label;
            this.plan = p_plan;
            this.dsn = p_dsn;
            this.ses = p_ses;
            this.check = p_check;
        }

        /** The positions of the vias that the plan of this run fixed before routing. The routes of the run keep the distance to them. */
        java.util.Set<String> fixed_positions()
        {
            java.util.Set<String> result = new java.util.HashSet<String>();
            for (Copper c : plan.pad_via_copper())
            {
                result.add(position(c.x0, c.y0));
            }
            if (plan.rework != null)
            {
                for (double[] via : plan.rework.fixed_vias)
                {
                    result.add(position(via[0], via[1]));
                }
            }
            return result;
        }
    }

    private static String position(double p_x, double p_y)
    {
        return Math.round(p_x * 1000.0) + "," + Math.round(p_y * 1000.0);
    }

    /** One track or via of an engine output. */
    private static final class Item
    {
        String alias;
        String name;
        boolean via;
        /** Track: the layer word of the engine and the layer id of the board. */
        String layer_word;
        String layer;
        /** Track: half of the width. Via: radius. In mm. */
        double half;
        /** Track: x and y of each point. Via: x, y. In mm, y as in the board file. */
        double[] pts;
        /** For the engine input. */
        String width_text;
        String points_text;
        String padstack;
        /** A via of the plan that the bridge added after an earlier pass. It can give way to a route of another run. */
        boolean soft;
        double x0, y0, x1, y1;

        void bounds()
        {
            x0 = y0 = Double.MAX_VALUE;
            x1 = y1 = -Double.MAX_VALUE;
            for (int i = 0; i + 1 < pts.length; i += 2)
            {
                x0 = Math.min(x0, pts[i] - half);
                x1 = Math.max(x1, pts[i] + half);
                y0 = Math.min(y0, pts[i + 1] - half);
                y1 = Math.max(y1, pts[i + 1] + half);
            }
        }

        /** Smallest distance between the copper of two items in mm. MAX_VALUE: no common layer, or far. */
        double gap(Item p_other, double p_far)
        {
            if (x0 > p_other.x1 + p_far || p_other.x0 > x1 + p_far || y0 > p_other.y1 + p_far || p_other.y0 > y1 + p_far)
            {
                return Double.MAX_VALUE;
            }
            if (!via && !p_other.via && !layer.equals(p_other.layer))
            {
                return Double.MAX_VALUE;
            }
            double best = Double.MAX_VALUE;
            int na = via ? 1 : pts.length / 2 - 1;
            int nb = p_other.via ? 1 : p_other.pts.length / 2 - 1;
            for (int i = 0; i < na; ++i)
            {
                int i1 = via ? 0 : 2 * i + 2;
                for (int j = 0; j < nb; ++j)
                {
                    int j1 = p_other.via ? 0 : 2 * j + 2;
                    best = Math.min(best, EeCheck.segment_distance(pts[2 * i], pts[2 * i + 1], pts[i1], pts[i1 + 1], p_other.pts[2 * j], p_other.pts[2 * j + 1],
                            p_other.pts[j1], p_other.pts[j1 + 1]));
                }
            }
            return best - half - p_other.half;
        }
    }

    /** The tracks and vias of an engine output. */
    private static List<Item> read_items(String p_ses, EeDsn p_dsn)
    {
        List<Item> items = new ArrayList<Item>();
        List<Object> root = SesConverter.parse(p_ses);
        List<List<Object>> routes = new ArrayList<List<Object>>();
        SesConverter.find_all(root, "routes", routes);
        Object scope = routes.isEmpty() ? root : routes.get(0);
        double per_mm = 10000.0;
        List<List<Object>> resolutions = new ArrayList<List<Object>>();
        SesConverter.find_all(scope, "resolution", resolutions);
        if (!resolutions.isEmpty() && resolutions.get(0).size() >= 3)
        {
            String unit = String.valueOf(resolutions.get(0).get(1));
            double value = SesConverter.number(resolutions.get(0).get(2));
            double unit_mm = unit.equals("um") ? 0.001 : unit.equals("mm") ? 1.0 : unit.equals("mil") ? 0.0254 : unit.equals("inch") ? 25.4 : 0.001;
            if (value > 0)
            {
                per_mm = value / unit_mm;
            }
        }
        List<List<Object>> network = new ArrayList<List<Object>>();
        SesConverter.find_all(scope, "network_out", network);
        for (List<Object> network_out : network)
        {
            for (Object child : network_out)
            {
                if (!(child instanceof List))
                {
                    continue;
                }
                List<?> net = (List<?>) child;
                if (net.size() < 2 || !"net".equals(net.get(0)))
                {
                    continue;
                }
                String alias = String.valueOf(net.get(1));
                String name = p_dsn.net_names.get(alias);
                for (Object entry : net)
                {
                    if (!(entry instanceof List) || ((List<?>) entry).isEmpty())
                    {
                        continue;
                    }
                    List<?> list = (List<?>) entry;
                    if ("wire".equals(list.get(0)))
                    {
                        for (Object part : list)
                        {
                            if (!(part instanceof List) || ((List<?>) part).size() < 7 || !"path".equals(((List<?>) part).get(0)))
                            {
                                continue;
                            }
                            List<?> path = (List<?>) part;
                            Item item = new Item();
                            item.alias = alias;
                            item.name = name;
                            item.layer_word = String.valueOf(path.get(1));
                            item.layer = p_dsn.layer_ids.get(item.layer_word);
                            item.half = SesConverter.number(path.get(2)) / per_mm / 2.0;
                            item.width_text = um(SesConverter.number(path.get(2)), per_mm);
                            int count = (path.size() - 3) / 2;
                            item.pts = new double[2 * count];
                            StringBuilder points = new StringBuilder();
                            for (int i = 0; i < count; ++i)
                            {
                                double x = SesConverter.number(path.get(3 + 2 * i)), y = SesConverter.number(path.get(4 + 2 * i));
                                item.pts[2 * i] = x / per_mm;
                                item.pts[2 * i + 1] = -y / per_mm;
                                points.append(i > 0 ? " " : "").append(um(x, per_mm)).append(' ').append(um(y, per_mm));
                            }
                            item.points_text = points.toString();
                            item.bounds();
                            items.add(item);
                        }
                    }
                    else if ("via".equals(list.get(0)) && list.size() >= 4)
                    {
                        Item item = new Item();
                        item.alias = alias;
                        item.name = name;
                        item.via = true;
                        item.padstack = String.valueOf(list.get(1));
                        double[] size = p_dsn.via_sizes.get(item.padstack);
                        if (size == null)
                        {
                            continue;
                        }
                        item.half = size[0] / 2.0;
                        double x = SesConverter.number(list.get(2)), y = SesConverter.number(list.get(3));
                        item.pts = new double[]
                        {
                            x / per_mm, -y / per_mm
                        };
                        item.points_text = um(x, per_mm) + " " + um(y, per_mm);
                        item.bounds();
                        items.add(item);
                    }
                }
            }
        }
        return items;
    }

    /** The name of a net in an engine input, for a net that is not a group of pads in a copper area. null: not there. */
    private static String alias_of(EeDsn p_dsn, String p_net)
    {
        for (Map.Entry<String, String> entry : p_dsn.net_names.entrySet())
        {
            if (entry.getValue().equals(p_net) && !p_dsn.cluster_pins.containsKey(entry.getKey()))
            {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * The routes of a group of nets from another run: an open net, and each net that has a track in the way of the
     * routes taken so far. The group is closed: no track of a net outside the group is in the way of its routes,
     * so the engine has no track to route again for it. Only tracks of nets with copper areas can be in the way.
     */
    private static final class Taken
    {
        /** The open net that the group started with. */
        String net;
        final java.util.Set<String> nets = new java.util.LinkedHashSet<String>();
        Donor donor;
        final List<Item> items = new ArrayList<Item>();
        /** Tracks and vias of nets with copper areas in this result that are in the way. They are taken out. */
        final List<Integer> in_the_way = new ArrayList<Integer>();
        /** Open connections of the nets of the group: in this result, and in the other run. */
        int open_here;
        int open_there;
        /** Positions of vias that the bridge added after an earlier pass and that are in the way of the routes of the group. */
        final java.util.Set<String> gives_way = new java.util.LinkedHashSet<String>();
    }

    private EeRework(EePlan p_plan, EeDsn p_dsn, String p_text)
    {
        this.plan = p_plan;
        this.dsn = p_dsn;
        this.text = p_text;
    }

    /**
     * @param p_board      the board of the job (not routed)
     * @param p_last_plan  the plan of the last pass
     * @param p_last_dsn   the engine input of the last pass
     * @param p_ses        the engine output of the last pass
     * @param p_result     the board file of the last pass, with the repair of the bridge
     * @param p_repair     the repair of the last pass, or null
     * @return null: no pad can get a via and no pad can go back into its net. A further pass would change nothing.
     */
    static EeRework build(EeBoard p_board, Rules p_rules, EePlan p_last_plan, EeDsn p_last_dsn, String p_ses, String p_result, EeBridge p_repair)
    {
        return build(p_board, p_rules, p_last_plan, p_last_dsn, p_ses, p_result, p_repair, null, null);
    }

    /**
     * @param p_check  the connections of the result of the last pass. Needed with p_donors.
     * @param p_donors the results of other runs that can give routes. null or empty: no route from another run.
     * @return null: nothing to change. With donors: also null when no route of another run can be taken.
     */
    static EeRework build(EeBoard p_board, Rules p_rules, EePlan p_last_plan, EeDsn p_last_dsn, String p_ses, String p_result, EeBridge p_repair,
            EeCheck p_check, List<Donor> p_donors)
    {
        return build(p_board, p_rules, p_last_plan, p_last_dsn, p_ses, p_result, p_repair, p_check, p_donors, MAX_ROUTES);
    }

    /** @param p_max_routes the largest number of routes of other runs. 1: only the route with the fewest tracks in its way. */
    static EeRework build(EeBoard p_board, Rules p_rules, EePlan p_last_plan, EeDsn p_last_dsn, String p_ses, String p_result, EeBridge p_repair,
            EeCheck p_check, List<Donor> p_donors, int p_max_routes)
    {
        boolean with_routes = p_check != null && p_donors != null && !p_donors.isEmpty();
        if (p_last_plan.fill_areas.isEmpty() && !with_routes)
        {
            return null;
        }
        EeBoard routed = new EeBoard(p_result, p_board.design_rules_first);
        List<Copper> copper = EeNet.copper_of(routed, p_last_plan, false);
        List<Copper> fixed = EeNet.copper_of(p_board, p_last_plan, p_rules.existing.equals("remove"));
        fixed.addAll(p_last_plan.pad_via_copper());
        EePlan.Rework rework = new EePlan.Rework();
        if (p_last_plan.rework != null)
        {
            rework.via_pads.addAll(p_last_plan.rework.via_pads);
            rework.net_pads.addAll(p_last_plan.rework.net_pads);
            rework.fixed_vias.addAll(p_last_plan.rework.fixed_vias);
            rework.fixed_via_nets.addAll(p_last_plan.rework.fixed_via_nets);
        }
        // The vias of the repair stay, so that the next pass does not route through their positions.
        int kept_vias = 0;
        for (EeBridge.AddedVia via : p_repair == null ? new ArrayList<EeBridge.AddedVia>() : p_repair.vias)
        {
            if (via.kind == EeBridge.AddedVia.PAD)
            {
                if (via.pad_index >= 0 && via.pad_index < p_board.pads.size() && !rework.via_pads.contains(p_board.pads.get(via.pad_index)))
                {
                    rework.via_pads.add(p_board.pads.get(via.pad_index));
                    ++kept_vias;
                }
                continue;
            }
            // For the engine the pads in the copper area have no net. A fixed via must keep the clearance of the engine to them and to all other copper.
            if (via.roomy && Math.abs(via.diameter - p_last_plan.via_diameter_mm) < 1e-6)
            {
                rework.fixed_vias.add(new double[]
                {
                    via.x, via.y
                });
                rework.fixed_via_nets.add(via.net);
                ++kept_vias;
            }
        }
        double pad_via_r = p_rules.pad_via_diameter_mm / 2.0;
        List<String> route_lines = new ArrayList<String>();
        List<String> via_names = new ArrayList<String>();
        List<String> net_names = new ArrayList<String>();
        List<double[]> new_vias = new ArrayList<double[]>(); // x, y in mm
        List<String> new_via_nets = new ArrayList<String>();
        // Only nets with copper areas. A via in a pad of a net without copper area that the engine did not connect was
        // tried: on the test board the job then ended with 10 open connections in place of 5.
        for (String net : p_last_plan.fill_areas.keySet())
        {
            EeNet graph = new EeNet(routed, p_last_plan, net, copper, false);
            Map<Integer, List<EeBoard.Pad>> groups = graph.pad_groups();
            if (groups.size() <= 1)
            {
                continue;
            }
            int main_root = graph.find(graph.main_group());
            for (Map.Entry<Integer, List<EeBoard.Pad>> group : groups.entrySet())
            {
                if (graph.find(group.getKey()) == main_root)
                {
                    continue;
                }

                EeBoard.Pad chosen = null;
                for (EeBoard.Pad result_pad : group.getValue())
                {
                    EeBoard.Pad pad = original_pad(p_board, result_pad);
                    if (pad == null || pad.through || p_rules.via_on_pads.equals("off") || has_via(p_last_plan, pad))
                    {
                        continue;
                    }
                    Copper own = pad.polygon != null ? Copper.polygon(pad.polygon, pad.layer, pad.net) : Copper.disc(pad.cx, pad.cy, pad.radius, pad.layer, pad.net);
                    own.label = "pad " + pad.ref + "." + pad.number;
                    if (EeBridge.reaches_main(graph, -1, pad.cx, pad.cy, main_root)
                            && EeBridge.via_fits(p_last_plan, p_board, fixed, net, pad.cx, pad.cy, pad_via_r, own, 0.0, PAD_VIA_MARGIN_MM))
                    {
                        chosen = pad;
                        break;
                    }
                }
                if (chosen != null)
                {
                    rework.via_pads.add(chosen);
                    via_names.add(chosen.ref + "." + chosen.number);
                    new_vias.add(new double[]
                    {
                        chosen.cx, chosen.cy
                    });
                    new_via_nets.add(net);
                    continue;
                }
                for (EeBoard.Pad result_pad : group.getValue())
                {
                    EeBoard.Pad pad = original_pad(p_board, result_pad);
                    if (pad != null && p_last_plan.pour_pads.contains(pad) && rework.net_pads.add(pad))
                    {
                        net_names.add(pad.ref + "." + pad.number);
                    }
                }
            }
        }
        if (via_names.isEmpty() && net_names.isEmpty() && !with_routes)
        {
            return null;
        }
        double gap = p_board.clearance_mm + PAD_VIA_MARGIN_MM + 0.006;
        EePlan plan = null;
        EeDsn dsn = null;
        int at = -1;
        java.util.Set<String> have = null;
        List<Item> base = null;
        List<Taken> taken = null;
        boolean[] pushed_out = null;
        int gave_way = 0;
        // Round 1 finds the routes of other runs. A via that the bridge added after an earlier pass can be in the way of
        // such a route. Round 2 makes the plan again without these vias. The repair after the pass sets its vias again.
        for (int round = 0; round < 2; ++round)
        {
            plan = new EePlan(p_board, p_rules, rework);
            dsn = new EeDsn(p_board, plan, p_rules);
            String marker = "  (wiring\n";
            at = dsn.text.indexOf(marker);
            if (at < 0)
            {
                return null;
            }
            at += marker.length();
            // The fixed items that the new input holds already (vias in pads, fixed vias, locked tracks). The engine output has them too.
            have = new java.util.HashSet<String>();
            java.util.regex.Matcher have_via = java.util.regex.Pattern.compile("\\(via \\S+ (-?[0-9.]+ -?[0-9.]+)[ )]").matcher(dsn.text);
            have_via.region(at, dsn.text.length());
            while (have_via.find())
            {
                have.add("via " + have_via.group(1));
            }
            java.util.regex.Matcher have_wire = java.util.regex.Pattern.compile("\\(wire \\(path (\\S+) \\S+ ([-0-9. ]+)\\)").matcher(dsn.text);
            have_wire.region(at, dsn.text.length());
            while (have_wire.find())
            {
                have.add("wire " + have_wire.group(1) + " " + have_wire.group(2).trim());
            }

            // The items of the last pass that the new input can carry.
            base = new ArrayList<Item>();
            for (Item item : read_items(p_ses, p_last_dsn))
            {
                // The name of a net must mean the same pads in the two inputs.
                if (item.name == null || !item.name.equals(dsn.net_names.get(item.alias)) || !same_pins(p_last_dsn, dsn, item.alias))
                {
                    continue;
                }
                if (item.via ? !dsn.via_sizes.containsKey(item.padstack) || have.contains("via " + item.points_text)
                        : have.contains("wire " + item.layer_word + " " + item.points_text))
                {
                    continue; // the new input has this item already
                }
                base.add(item);
            }

            // Routes of other runs for nets that are open in this result.
            taken = new ArrayList<Taken>();
            pushed_out = new boolean[base.size()];
            java.util.Set<String> gives_way = new java.util.HashSet<String>();
            if (with_routes)
            {
                choose_routes(p_board, p_last_plan, plan, dsn, p_check, p_donors, base, have, taken, pushed_out, p_max_routes, round == 0);
                for (Taken route : taken)
                {
                    gives_way.addAll(route.gives_way);
                }
            }
            if (round > 0 || gives_way.isEmpty())
            {
                break;
            }
            for (int i = rework.fixed_vias.size() - 1; i >= 0; --i)
            {
                if (gives_way.contains(position(rework.fixed_vias.get(i)[0], rework.fixed_vias.get(i)[1])))
                {
                    rework.fixed_vias.remove(i);
                    rework.fixed_via_nets.remove(i);
                    ++gave_way;
                    --kept_vias;
                }
            }
        }
        if (via_names.isEmpty() && net_names.isEmpty() && taken.isEmpty())
        {
            return null;
        }
        java.util.Set<String> replaced = new java.util.HashSet<String>();
        for (Taken route : taken)
        {
            replaced.addAll(route.nets);
        }

        StringBuilder wiring = new StringBuilder();
        int carried = 0;
        java.util.Set<String> touched = new java.util.TreeSet<String>();
        int wires_removed = 0, vias_removed = 0;
        for (int k = 0; k < base.size(); ++k)
        {
            Item item = base.get(k);
            if (replaced.contains(item.name) && !dsn.cluster_pins.containsKey(item.alias))
            {
                continue; // the route of this net comes from another run
            }
            if (pushed_out[k])
            {
                continue; // in the way of a route from another run
            }
            boolean in_the_way = false;
            for (int v = 0; v < new_vias.size() && !in_the_way; ++v)
            {
                double[] via = new_vias.get(v);
                if (item.via)
                {
                    double d = Math.hypot(item.pts[0] - via[0], item.pts[1] - via[1]);
                    in_the_way = new_via_nets.get(v).equals(item.name) ? d < 0.01 : d < pad_via_r + item.half + gap;
                }
                else if (item.layer != null && !new_via_nets.get(v).equals(item.name))
                {
                    for (int i = 0; i + 3 < item.pts.length && !in_the_way; i += 2)
                    {
                        double d = EeCheck.segment_distance(item.pts[i], item.pts[i + 1], item.pts[i + 2], item.pts[i + 3], via[0], via[1], via[0], via[1]);
                        in_the_way = d < pad_via_r + item.half + gap;
                    }
                }
            }
            if (in_the_way)
            {
                if (item.via)
                {
                    ++vias_removed;
                }
                else
                {
                    ++wires_removed;
                }
                touched.add(item.name);
                continue;
            }
            append(wiring, item, item.alias);
            ++carried;
        }
        int in_the_way_of_routes = 0;
        int nets_taken = 0;
        for (Taken route : taken)
        {
            for (Item item : route.items)
            {
                append(wiring, item, alias_of(dsn, item.name));
                ++carried;
            }
            in_the_way_of_routes += route.in_the_way.size();
            nets_taken += route.nets.size();
            List<String> names = new ArrayList<String>(route.nets);
            route_lines.add(String.format(Locale.ROOT,
                    "Net %s is open in this result and has a route in the result of %s. The routes of %d nets came from that result: %s. These nets have %d open"
                            + " connections in this result and %d in that result.%s",
                    route.net, route.donor.label, route.nets.size(), short_list(names), route.open_here, route.open_there,
                    route.in_the_way.isEmpty() ? "" : " " + route.in_the_way.size() + " tracks and vias of nets with copper areas were in the way and were taken out."));
        }
        EeRework result = new EeRework(plan, dsn, dsn.text.substring(0, at) + wiring + dsn.text.substring(at));
        result.carried = carried;
        result.via_pads = via_names.size();
        result.net_pads = net_names.size();
        result.wires_removed = wires_removed;
        result.vias_removed = vias_removed;
        result.nets_touched = touched.size();
        result.routes_taken = taken.size();
        result.nets_taken = nets_taken;
        result.routes_in_the_way = in_the_way_of_routes;
        if (!via_names.isEmpty())
        {
            result.lines.add(String.format(Locale.ROOT, "%d pads that tracks cut off from the copper area of their net got a via of %.3f mm in the pad: %s.",
                    via_names.size(), p_rules.pad_via_diameter_mm, short_list(via_names)));
        }
        if (wires_removed + vias_removed > 0)
        {
            result.lines.add(String.format(Locale.ROOT,
                    "Tracks of other nets were in the way of these vias: %d tracks and %d vias of %d nets were taken out. The engine routed them again between the other tracks, which stayed fixed.",
                    wires_removed, vias_removed, touched.size()));
        }
        if (!net_names.isEmpty())
        {
            result.lines.add(net_names.size() + " pads cannot take a via in the pad. They went back into their net for the engine: " + short_list(net_names) + ".");
        }
        if (kept_vias > 0)
        {
            result.lines.add(kept_vias + " vias that the bridge added after the pass before stayed as fixed vias.");
        }
        if (gave_way > 0)
        {
            result.lines.add(gave_way + " vias that the bridge added after the pass before were in the way of routes of other runs. They are set again after the pass.");
        }
        result.lines.addAll(route_lines);

        return result;
    }

    private static void append(StringBuilder p_wiring, Item p_item, String p_alias)
    {
        if (p_item.via)
        {
            p_wiring.append("    (via ").append(p_item.padstack).append(' ').append(p_item.points_text).append(" (net ").append(p_alias).append(") (type protect))\n");
        }
        else
        {
            p_wiring.append("    (wire (path ").append(p_item.layer_word).append(' ').append(p_item.width_text).append(' ').append(p_item.points_text)
                    .append(") (net ").append(p_alias).append(") (type protect))\n");
        }
    }

    /**
     * Chooses the routes of other runs.
     *
     * @param p_base       the tracks and vias of this result that the new input can carry
     * @param p_have       the fixed items of the new input
     * @param p_taken      out: the groups of nets that get their routes from another run
     * @param p_pushed_out out: true for each item of p_base that is in the way of such a route
     */
    private static void choose_routes(EeBoard p_board, EePlan p_last_plan, EePlan p_plan, EeDsn p_dsn, EeCheck p_check, List<Donor> p_donors, List<Item> p_base,
            java.util.Set<String> p_have, List<Taken> p_taken, boolean[] p_pushed_out, int p_max_routes, boolean p_vias_give_way)
    {
        double clearance = p_board.clearance_mm;
        double far = clearance + ROUTE_VIA_MARGIN_MM + 0.01;
        // Copper of the plan that cannot move: the vias in pads and the fixed vias. As items, for the distance test.
        List<Item> planned = new ArrayList<Item>();
        for (Copper c : p_plan.pad_via_copper())
        {
            Item item = new Item();
            item.via = true;
            item.name = c.net;
            item.half = c.r;
            item.pts = new double[]
            {
                c.x0, c.y0
            };
            item.bounds();
            planned.add(item);
        }
        if (p_plan.rework != null)
        {
            for (int i = 0; i < p_plan.rework.fixed_vias.size(); ++i)
            {
                Item item = new Item();
                item.via = true;
                item.name = p_plan.rework.fixed_via_nets.get(i);
                item.half = p_plan.via_diameter_mm / 2.0;
                item.pts = p_plan.rework.fixed_vias.get(i);
                item.soft = p_vias_give_way;
                item.bounds();
                planned.add(item);
            }
        }
        Map<Donor, List<Item>> donor_items = new java.util.HashMap<Donor, List<Item>>();
        Map<Donor, java.util.Set<String>> donor_fixed = new java.util.HashMap<Donor, java.util.Set<String>>();
        List<Taken> offers = new ArrayList<Taken>();
        for (EeCheck.NetState state : p_check.nets.values())
        {
            if (state.groups <= 1 || p_last_plan.fill_areas.containsKey(state.net) || !p_plan.nets.containsKey(state.net))
            {
                continue;
            }
            Taken best = null;
            for (Donor donor : p_donors)
            {
                EeCheck.NetState other = donor.check.nets.get(state.net);
                if (other == null || other.groups >= state.groups)
                {
                    continue;
                }
                List<Item> all = donor_items.get(donor);
                if (all == null)
                {
                    all = read_items(donor.ses, donor.dsn);
                    donor_items.put(donor, all);
                }
                java.util.Set<String> known = donor_fixed.get(donor);
                if (known == null)
                {
                    known = donor.fixed_positions();
                    donor_fixed.put(donor, known);
                }
                String[] why = new String[1];
                Taken offer = group(state.net, donor, all, known, planned, p_last_plan, p_plan, p_dsn, p_check, p_base, p_have, p_pushed_out, clearance, far, why);
                if (DEBUG)
                {
                    System.out.println("  routes for " + state.net + " from " + donor.label + ": " + (offer == null ? why[0] : offer.nets.size() + " nets "
                            + offer.nets + ", open " + offer.open_here + " here and " + offer.open_there + " there, in the way " + offer.in_the_way.size()));
                }
                if (offer == null)
                {
                    continue;
                }
                if (best == null || better(offer, best))
                {
                    best = offer;
                }
            }
            if (best != null)
            {
                offers.add(best);
            }
        }
        offers.sort((a, b) -> better(a, b) ? -1 : better(b, a) ? 1 : 0);
        int total = 0;
        for (Taken offer : offers)
        {
            if (p_taken.size() >= Math.min(MAX_ROUTES, p_max_routes))
            {
                break;
            }
            // A group must not share a net with a group that is taken already, and its routes must keep the distance to the routes of that group.
            boolean clash = false;
            for (Taken done : p_taken)
            {
                for (String net : offer.nets)
                {
                    clash |= done.nets.contains(net);
                }
                for (int i = 0; i < offer.items.size() && !clash; ++i)
                {
                    Item a = offer.items.get(i);
                    for (Item b : done.items)
                    {
                        if (a.gap(b, far) < clearance + (a.via || b.via ? ROUTE_VIA_MARGIN_MM : ROUTE_MARGIN_MM))
                        {
                            clash = true;
                            break;
                        }
                    }
                }
            }
            if (clash)
            {
                continue;
            }
            int added = 0;
            for (int k : offer.in_the_way)
            {
                added += p_pushed_out[k] ? 0 : 1;
            }
            if (total + added > MAX_IN_THE_WAY_TOTAL)
            {
                continue;
            }
            for (int k : offer.in_the_way)
            {
                p_pushed_out[k] = true;
            }
            total += added;
            p_taken.add(offer);
        }
    }

    /** true: the first group is the better choice: more open connections closed, then fewer tracks in the way, then fewer nets. */
    private static boolean better(Taken p_a, Taken p_b)
    {
        int gain_a = p_a.open_here - p_a.open_there, gain_b = p_b.open_here - p_b.open_there;
        if (gain_a != gain_b)
        {
            return gain_a > gain_b;
        }
        if (p_a.in_the_way.size() != p_b.in_the_way.size())
        {
            return p_a.in_the_way.size() < p_b.in_the_way.size();
        }
        return p_a.nets.size() < p_b.nets.size();
    }

    /**
     * The closed group of nets for the open net p_net with the routes of p_donor.
     *
     * @param p_why out: the reason when there is no group
     * @return null: no group with a gain within the limits
     */
    private static Taken group(String p_net, Donor p_donor, List<Item> p_donor_items, java.util.Set<String> p_known, List<Item> p_planned, EePlan p_last_plan,
            EePlan p_plan, EeDsn p_dsn, EeCheck p_check, List<Item> p_base, java.util.Set<String> p_have, boolean[] p_pushed_out, double p_clearance,
            double p_far, String[] p_why)
    {
        Taken offer = new Taken();
        offer.net = p_net;
        offer.donor = p_donor;
        List<String> queue = new ArrayList<String>();
        queue.add(p_net);
        java.util.Set<Integer> pushed = new java.util.LinkedHashSet<Integer>();
        while (!queue.isEmpty())
        {
            String net = queue.remove(0);
            if (offer.nets.contains(net))
            {
                continue;
            }
            String donor_alias = alias_of(p_donor.dsn, net);
            if (alias_of(p_dsn, net) == null || donor_alias == null || p_last_plan.fill_areas.containsKey(net) || !p_plan.nets.containsKey(net))
            {
                p_why[0] = "net " + net + " cannot be taken from the other run";
                return null;
            }
            offer.nets.add(net);
            if (offer.nets.size() > MAX_GROUP_NETS)
            {
                p_why[0] = "more than " + MAX_GROUP_NETS + " nets";
                return null;
            }
            List<Item> route = new ArrayList<Item>();
            for (Item item : p_donor_items)
            {
                if (!item.alias.equals(donor_alias))
                {
                    continue;
                }
                if (item.via ? p_have.contains("via " + item.points_text) : p_have.contains("wire " + item.layer_word + " " + item.points_text))
                {
                    continue; // the new input has this item already
                }
                if (item.via ? !p_dsn.via_sizes.containsKey(item.padstack) : item.layer == null || !p_dsn.layer_ids.containsKey(item.layer_word))
                {
                    p_why[0] = "an item of net " + net + " cannot be written";
                    return null;
                }
                route.add(item);
            }
            // The routes must keep the distance to the vias of the new plan. They keep the distance to the vias of the plan
            // of their own run, so only the other vias are tested.
            for (Item item : route)
            {
                for (Item fixed : p_planned)
                {
                    if (!net.equals(fixed.name) && !p_known.contains(position(fixed.pts[0], fixed.pts[1]))
                            && item.gap(fixed, p_far) < p_clearance + ROUTE_VIA_MARGIN_MM)
                    {
                        if (fixed.soft)
                        {
                            offer.gives_way.add(position(fixed.pts[0], fixed.pts[1]));
                            continue;
                        }
                        p_why[0] = "the route of net " + net + " is too near to a via in a pad of net " + fixed.name + String.format(Locale.ROOT, " at %.2f, %.2f",
                                fixed.pts[0], fixed.pts[1]);
                        return null;
                    }
                }
            }
            offer.items.addAll(route);
            for (int k = 0; k < p_base.size(); ++k)
            {
                Item other = p_base.get(k);
                if (offer.nets.contains(other.name) || pushed.contains(k))
                {
                    continue;
                }
                boolean near = false;
                for (Item item : route)
                {
                    if (item.gap(other, p_far) < p_clearance + (item.via || other.via ? ROUTE_VIA_MARGIN_MM : ROUTE_MARGIN_MM))
                    {
                        near = true;
                        break;
                    }
                }
                if (!near)
                {
                    continue;
                }
                if (p_last_plan.fill_areas.containsKey(other.name) || p_dsn.cluster_pins.containsKey(other.alias))
                {
                    pushed.add(k); // a net with copper areas: its track is taken out, and the engine and the repair connect it again
                }
                else if (!queue.contains(other.name))
                {
                    queue.add(other.name);
                }
            }
        }
        for (String net : offer.nets)
        {
            EeCheck.NetState here = p_check.nets.get(net), there = p_donor.check.nets.get(net);
            offer.open_here += here == null ? 0 : here.groups - 1;
            offer.open_there += there == null ? 0 : there.groups - 1;
        }
        offer.in_the_way.addAll(pushed);
        if (offer.open_there >= offer.open_here)
        {
            p_why[0] = offer.nets.size() + " nets, no gain: open " + offer.open_here + " here and " + offer.open_there + " there";
            return null;
        }
        if (offer.in_the_way.size() > MAX_IN_THE_WAY)
        {
            p_why[0] = offer.in_the_way.size() + " tracks of nets with copper areas are in the way";
            return null;
        }
        return offer;
    }

    private static boolean same_pins(EeDsn p_a, EeDsn p_b, String p_alias)
    {
        List<Integer> a = p_a.cluster_pins.get(p_alias), b = p_b.cluster_pins.get(p_alias);
        return a == null ? b == null : a.equals(b);
    }

    private static boolean has_via(EePlan p_plan, EeBoard.Pad p_pad)
    {
        for (EePlan.PadVia via : p_plan.pad_vias)
        {
            if (via.pad == p_pad)
            {
                return true;
            }
        }
        return false;
    }

    /** The pad of the job board that is the same pad as a pad of the result board. */
    private static EeBoard.Pad original_pad(EeBoard p_board, EeBoard.Pad p_pad)
    {
        if (p_pad.index < 0 || p_pad.index >= p_board.pads.size())
        {
            return null;
        }
        EeBoard.Pad pad = p_board.pads.get(p_pad.index);
        return String.valueOf(pad.ref).equals(String.valueOf(p_pad.ref)) && String.valueOf(pad.number).equals(String.valueOf(p_pad.number))
                && Math.abs(pad.cx - p_pad.cx) < 1e-6 && Math.abs(pad.cy - p_pad.cy) < 1e-6 ? pad : null;
    }

    /** A length of the engine output in the unit of the engine input (micrometres with one decimal). */
    private static String um(double p_value, double p_per_mm)
    {
        return String.format(Locale.ROOT, "%.1f", p_value / p_per_mm * 1000.0);
    }

    private static String short_list(List<String> p_names)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p_names.size() && i < 30; ++i)
        {
            sb.append(i > 0 ? ", " : "").append(p_names.get(i));
        }
        if (p_names.size() > 30)
        {
            sb.append(", and ").append(p_names.size() - 30).append(" more");
        }
        return sb.toString();
    }
}
