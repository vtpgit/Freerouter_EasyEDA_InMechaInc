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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test tool: NarrowTool <original.json> <result.json> [rules.json]. Lists each track segment of the result that is
 * narrower than the track rule, with the copper of other nets that is nearest to the same segment at the full width.
 */
public final class NarrowTool
{
    public static void main(String[] p_args) throws Exception
    {
        Rules rules = p_args.length > 2 ? Rules.load(Paths.get(p_args[2])) : new Rules();
        EeBoard original = new EeBoard(new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8), rules.design_rules_first());
        EeBoard routed = new EeBoard(new String(Files.readAllBytes(Paths.get(p_args[1])), StandardCharsets.UTF_8), rules.design_rules_first());
        EePlan plan = new EePlan(original, rules);
        List<Copper> copper = EeNet.copper_of(routed, plan, false);
        double rule = routed.track_mm, clearance = routed.clearance_mm;
        int count = 0;
        double total = 0.0;
        for (EeBoard.Track track : routed.tracks)
        {
            if (track.width >= rule - 0.0005)
            {
                continue;
            }
            for (int i = 0; i + 3 < track.points.length; i += 2)
            {
                double x0 = track.points[i], y0 = track.points[i + 1], x1 = track.points[i + 2], y1 = track.points[i + 3];
                double len = Math.hypot(x1 - x0, y1 - y0);
                ++count;
                total += len;
                Copper full = Copper.segment(x0, y0, x1, y1, rule / 2.0, track.layer, track.net);
                List<String> near = new ArrayList<String>();
                double worst = Double.MAX_VALUE;
                String worst_label = "";
                for (Copper c : copper)
                {
                    if (!full.shares_layer(c) || full.far_from(c, clearance + 0.06) || (!c.net.isEmpty() && c.net.equals(track.net)))
                    {
                        continue;
                    }
                    double d = full.distance(c);
                    if (d < clearance + 0.012)
                    {
                        near.add(String.format(Locale.ROOT, "%s %.4f", c.label.isEmpty() ? (c.routed ? "routed " : "") + c.net : c.label, d));
                    }
                    if (d < worst)
                    {
                        worst = d;
                        worst_label = c.label;
                    }
                }
                System.out.println(String.format(Locale.ROOT, "%-11s layer %-2s width %.3f len %.3f at (%.2f, %.2f)-(%.2f, %.2f): smallest gap at full width %.4f (rule %.3f, short by %.4f). Near: %s",
                        track.net, track.layer, track.width, len, x0, y0, x1, y1, worst, clearance, clearance - worst, near));
            }
        }
        System.out.println(String.format(Locale.ROOT, "narrow segments %d, length %.2f mm", count, total));
    }
}
