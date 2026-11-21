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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Runs the track clean-up on a routed board file and prints the counts and the checks before and after it.
 * Usage: TidyTool <routed.json> <out.json> [rules.json] [all|none|layer id,layer id]
 */
public class TidyTool
{
    public static void main(String[] p_args) throws Exception
    {
        EeBoard board = new EeBoard(new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8));
        Rules rules = p_args.length > 2 ? Rules.load(Paths.get(p_args[2])) : new Rules();
        EePlan plan = new EePlan(board, rules);
        Set<String> layers = null;
        if (p_args.length > 3 && !p_args[3].equals("all"))
        {
            layers = new HashSet<String>();
            if (!p_args[3].equals("none"))
            {
                Collections.addAll(layers, p_args[3].split(","));
            }
        }
        long t0 = System.nanoTime();
        EeTidy tidy = new EeTidy(null, board, plan, layers);
        long t1 = System.nanoTime();
        Files.write(Paths.get(p_args[1]), tidy.text.getBytes(StandardCharsets.UTF_8));
        for (String line : tidy.narrow_lines)
        {
            System.out.println("  stays narrow: " + line);
        }
        EeBoard after = new EeBoard(tidy.text);
        EeCheck check0 = new EeCheck(board, plan);
        EeCheck check1 = new EeCheck(after, plan);
        EeClear clear0 = new EeClear(board, plan);
        EeClear clear1 = new EeClear(after, plan);
        System.out.println(String.format(java.util.Locale.ROOT,
                "track records %d -> %d. points on a line removed %d, steps removed %d, side steps removed %d. full width in place %d, after a shift %d (largest %.3f mm). "
                        + "narrow segments now %d (smallest %.3f mm, %.1f mm). open %d -> %d. clearance errors %d -> %d, edge %d -> %d, keep-out %d -> %d. "
                        + "smallest track width %.4f -> %.4f mm. clean-up %d ms",
                tidy.records_before, tidy.records_after, tidy.points_removed, tidy.steps_removed, tidy.side_steps_removed, tidy.widened_in_place, tidy.widened_after_shift,
                tidy.largest_shift_mm, tidy.narrow_segments, tidy.narrow_segments > 0 ? tidy.narrow_min_mm : 0.0, tidy.narrow_length_mm,
                check0.open_connections, check1.open_connections, clear0.routed_errors, clear1.routed_errors, clear0.edge_errors, clear1.edge_errors,
                clear0.keepout_errors, clear1.keepout_errors, clear0.min_track_mm, clear1.min_track_mm, (t1 - t0) / 1000000L));
    }
}
