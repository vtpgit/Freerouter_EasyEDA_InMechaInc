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
import java.util.HashSet;

/**
 * Test tool: PerturbTool <board.json> <rules.json> <output.ses> [out.json]. Builds the result of one engine run, looks
 * for small part moves (rules: parts.move must be on), and counts the open connections of the board with the moves.
 */
public final class PerturbTool
{
    public static void main(String[] p_args) throws Exception
    {
        Rules rules = Rules.load(Paths.get(p_args[1]));
        rules.part_moves = "on";
        if (System.getProperty("shift") != null)
        {
            rules.part_shift_mm = Double.parseDouble(System.getProperty("shift"));
        }
        EeBoard board = new EeBoard(new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8), rules.design_rules_first());
        EePlan plan = new EePlan(board, rules);
        EeDsn dsn = new EeDsn(board, plan, rules);
        String ses = new String(Files.readAllBytes(Paths.get(p_args[2])), StandardCharsets.UTF_8);
        EeResult result = new EeResult(board, plan, dsn, ses);
        EeBoard routed = new EeBoard(result.text, rules.design_rules_first());
        EeBridge repair = new EeBridge(routed, plan);
        EeBoard repaired = new EeBoard(repair.text, rules.design_rules_first());
        EeCheck check = new EeCheck(repaired, plan);
        System.out.println("open before: " + check.open_connections);
        for (EeCheck.NetState state : check.nets.values())
        {
            if (state.groups > 1)
            {
                System.out.println("  " + state.net + ": " + state.loose_pads);
            }
        }
        long t0 = System.nanoTime();
        EePerturb step = EePerturb.via_room(board, plan, rules, repair.text, check, new HashSet<String>());
        System.out.println("search: " + (System.nanoTime() - t0) / 1000000L + " ms");
        if (step == null)
        {
            System.out.println("no loose pad of a net with a copper area");
            return;
        }
        for (String line : step.not_moved)
        {
            System.out.println("  not moved: " + line);
        }
        for (String line : step.lines)
        {
            System.out.println("  " + line);
        }
        if (step.moves.isEmpty())
        {
            return;
        }
        EeBoard moved_board = new EeBoard(step.apply_to(board), rules.design_rules_first());
        EePlan moved_plan = new EePlan(moved_board, rules);
        EeBoard moved_routed = new EeBoard(step.apply_to(routed), rules.design_rules_first());
        EeBridge moved_repair = new EeBridge(moved_routed, moved_plan);
        EeBoard moved_repaired = new EeBoard(moved_repair.text, rules.design_rules_first());
        EeCheck moved_check = new EeCheck(moved_repaired, moved_plan);
        System.out.println("open after: " + moved_check.open_connections + ". Vias in pads added by the repair: " + repair.pad_vias_added + " before, "
                + moved_repair.pad_vias_added + " after");
        for (EeCheck.NetState state : moved_check.nets.values())
        {
            if (state.groups > 1)
            {
                System.out.println("  " + state.net + ": " + state.loose_pads);
            }
        }
        EeClear clear = new EeClear(moved_repaired, moved_plan);
        System.out.println("clearance errors with routed copper " + clear.routed_errors + ", between parts " + clear.placement_errors + ", near edge " + clear.edge_errors + ", in keep-out " + clear.keepout_errors);
        if (p_args.length > 3)
        {
            Files.write(Paths.get(p_args[3]), moved_repair.text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
