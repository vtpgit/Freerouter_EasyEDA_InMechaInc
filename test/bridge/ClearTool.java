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

/** Test tool: ClearTool <board.json> [rules.json]. Runs the checks of the bridge on a board file. */
public final class ClearTool
{
    public static void main(String[] p_args) throws Exception
    {
        String json = new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8);
        Rules rules = p_args.length > 1 ? Rules.load(Paths.get(p_args[1])) : new Rules();
        EeBoard board = new EeBoard(json);
        EePlan plan = new EePlan(board, rules);
        long t0 = System.nanoTime();
        EeClear clear = new EeClear(board, plan);
        EeCheck check = new EeCheck(board, plan);
        System.out.println("clearance errors with routes " + clear.routed_errors + ", placement " + clear.placement_errors + ", edge " + clear.edge_errors
                + ", keep-out " + clear.keepout_errors + ", min track " + clear.min_track_mm + ", vias " + clear.via_sizes + ", duplicate ids "
                + clear.duplicate_ids + ", open " + check.open_connections + ", ms " + (System.nanoTime() - t0) / 1000000);
        for (String e : clear.examples)
        {
            System.out.println("  " + e);
        }
    }
}
