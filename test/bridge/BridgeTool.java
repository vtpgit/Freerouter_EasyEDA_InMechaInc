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

/** Test tool: BridgeTool <routed.json> <out.json> [rules.json]. Adds the bridge tracks of plane nets to a routed board file. */
public final class BridgeTool
{
    public static void main(String[] p_args) throws Exception
    {
        String json = new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8);
        Rules rules = p_args.length > 2 ? Rules.load(Paths.get(p_args[2])) : new Rules();
        EeBoard board = new EeBoard(json);
        EePlan plan = new EePlan(board, rules);
        EeCheck before = new EeCheck(board, plan);
        long t0 = System.nanoTime();
        EeBridge bridge = new EeBridge(board, plan);
        long ms = (System.nanoTime() - t0) / 1000000;
        Files.write(Paths.get(p_args[1]), bridge.text.getBytes(StandardCharsets.UTF_8));
        EeBoard after_board = new EeBoard(bridge.text);
        EeCheck after = new EeCheck(after_board, plan);
        EeClear clear = new EeClear(after_board, plan);
        System.out.println("open before " + before.open_connections + ", island vias " + bridge.island_vias_added + ", pad vias " + bridge.pad_vias_added + ", surface tracks " + bridge.surface_tracks_added + " with " + bridge.track_vias_added + " vias, tracks added " + bridge.tracks_added + String.format(" (%.1f mm)", bridge.length_added)
                + ", open after " + after.open_connections + ", clearance errors " + clear.routed_errors + ", edge " + clear.edge_errors + ", keep-out "
                + clear.keepout_errors + ", duplicate ids " + clear.duplicate_ids + ", ms " + ms);
        for (String line : bridge.lines)
        {
            System.out.println("  " + line);
        }
        for (EeCheck.NetState s : after.nets.values())
        {
            if (s.groups > 1)
            {
                System.out.println("  open: " + s.net + " " + (s.groups - 1) + " " + s.loose_pads);
            }
        }
        for (String e : clear.examples)
        {
            System.out.println("  error: " + e);
        }
    }
}
