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

/** Test tool: EeTool <board.json> <out.dsn> [rules.json]. Writes the engine input for an EasyEDA board and prints the plan. */
public final class EeTool
{
    public static void main(String[] p_args) throws Exception
    {
        String json = new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8);
        Rules rules = p_args.length > 2 ? Rules.load(Paths.get(p_args[2])) : new Rules();
        EeBoard board = new EeBoard(json);
        EePlan plan = new EePlan(board, rules);
        EeDsn dsn = new EeDsn(board, plan, rules);
        Files.write(Paths.get(p_args[1]), dsn.text.getBytes(StandardCharsets.UTF_8));
        System.out.println("parts " + board.part_count + ", pads " + board.pads.size() + ", layers " + board.layers + ", records " + board.records.size());
        System.out.println("rules mm: track " + board.track_mm + ", clearance " + board.clearance_mm + ", via " + board.via_mm + "/" + board.via_hole_mm);
        System.out.println("nets to route " + plan.nets.size() + ", skipped " + plan.skipped_nets + ", planes " + plan.planes.keySet() + " on " + plan.plane_layers);
        System.out.println("existing tracks " + board.tracks.size() + ", vias " + board.vias.size() + ", areas " + board.areas.size() + ", holes " + board.holes.size()
                + ", copper regions in footprints " + board.solids.size());
        System.out.println("pad vias " + plan.pad_vias.size() + ", keep-out rectangles " + plan.keepouts.size());
        for (String w : rules.warnings)
        {
            System.out.println("rules: " + w);
        }
        for (String note : plan.notes)
        {
            System.out.println("note: " + note);
        }
    }
}
