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

/**
 * Shows which routes of other runs the bridge would take for each run of a first pass.
 * Usage: RecombineTool <board.json> <rules.json or -> <output.ses of run 1> <output.ses of run 2> ...
 * With the system property out=<file>, the engine input for the first run is written to that file.
 */
public class RecombineTool
{
    public static void main(String[] p_args) throws Exception
    {
        Rules rules = p_args[1].equals("-") ? new Rules() : Rules.load(Paths.get(p_args[1]));
        EeBoard board = new EeBoard(new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8), rules.design_rules_first());
        EePlan plan = new EePlan(board, rules);
        EeDsn dsn = new EeDsn(board, plan, rules);
        int n = p_args.length - 2;
        String[] ses = new String[n];
        String[] text = new String[n];
        EeBridge[] repair = new EeBridge[n];
        EeCheck[] check = new EeCheck[n];
        for (int i = 0; i < n; ++i)
        {
            ses[i] = new String(Files.readAllBytes(Paths.get(p_args[i + 2])), StandardCharsets.UTF_8);
            EeResult result = new EeResult(board, plan, dsn, ses[i]);
            text[i] = result.text;
            EeBoard routed = new EeBoard(text[i], rules.design_rules_first());
            if (!plan.fill_areas.isEmpty())
            {
                repair[i] = new EeBridge(routed, plan);
                text[i] = repair[i].text;
                routed = new EeBoard(text[i], rules.design_rules_first());
            }
            check[i] = new EeCheck(routed, plan);
            System.out.println("run " + (i + 1) + ": open " + check[i].open_connections);
        }
        for (int i = 0; i < n; ++i)
        {
            List<EeRework.Donor> donors = new ArrayList<EeRework.Donor>();
            for (int j = 0; j < n; ++j)
            {
                if (j != i)
                {
                    donors.add(new EeRework.Donor("run " + (j + 1), plan, dsn, ses[j], check[j]));
                }
            }
            long t0 = System.nanoTime();
            EeRework rework = EeRework.build(board, rules, plan, dsn, ses[i], text[i], repair[i], check[i], donors);
            long ms = (System.nanoTime() - t0) / 1000000L;
            if (rework == null)
            {
                System.out.println("base run " + (i + 1) + ": nothing to change (" + ms + " ms)");
                continue;
            }
            System.out.println("base run " + (i + 1) + ": routes taken " + rework.routes_taken + ", in their way " + rework.routes_in_the_way + ", carried "
                    + rework.carried + ", vias in pads " + rework.via_pads + " (" + ms + " ms)");
            for (String line : rework.lines)
            {
                System.out.println("    " + line);
            }
            String out = System.getProperty("out");
            if (out != null && i == Integer.getInteger("base", 1) - 1)
            {
                Files.write(Paths.get(out), rework.text.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
