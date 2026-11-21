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

/** Makes the engine input of a further pass from a board file and the engine output of a pass. Arguments: board.json, output.ses, out.dsn, [rules.json]. */
public final class ReworkTool
{
    public static void main(String[] p_args) throws Exception
    {
        String json = new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8);
        String ses = new String(Files.readAllBytes(Paths.get(p_args[1])), StandardCharsets.UTF_8);
        Rules rules = p_args.length > 3 ? Rules.load(Paths.get(p_args[3])) : new Rules();
        EeBoard board = new EeBoard(json);
        EePlan plan = new EePlan(board, rules);
        EeDsn dsn = new EeDsn(board, plan, rules);
        EeResult result = new EeResult(board, plan, dsn, ses);
        EeBridge bridge = new EeBridge(new EeBoard(result.text), plan);
        EeCheck check = new EeCheck(new EeBoard(bridge.text), plan);
        System.out.println("open after the repair: " + check.open_connections);
        EeRework rework = EeRework.build(board, rules, plan, dsn, ses, bridge.text, bridge);
        if (rework == null)
        {
            System.out.println("no further pass");
            return;
        }
        Files.write(Paths.get(p_args[2]), rework.text.getBytes(StandardCharsets.UTF_8));
        if (p_args.length > 4)
        {
            // a third pass: the engine output of the second pass
            String ses2 = new String(Files.readAllBytes(Paths.get(p_args[4])), StandardCharsets.UTF_8);
            EeResult result2 = new EeResult(board, rework.plan, rework.dsn, ses2);
            EeBridge bridge2 = new EeBridge(new EeBoard(result2.text), rework.plan);
            EeCheck check2 = new EeCheck(new EeBoard(bridge2.text), rework.plan);
            System.out.println("second pass: tracks " + result2.track_count + ", vias " + result2.via_count + ", open after the repair: " + check2.open_connections);
            EeRework next = EeRework.build(board, rules, rework.plan, rework.dsn, ses2, bridge2.text, bridge2);
            if (next == null)
            {
                System.out.println("no third pass");
                return;
            }
            Files.write(Paths.get(p_args[2] + ".pass3.dsn"), next.text.getBytes(StandardCharsets.UTF_8));
            System.out.println("third pass: via pads " + next.via_pads + ", net pads " + next.net_pads + ", wires removed "
                    + next.wires_removed + ", vias removed " + next.vias_removed + ", carried " + next.carried);
            for (String line : next.lines)
            {
                System.out.println("  " + line);
            }
        }
        System.out.println("via pads " + rework.via_pads + ", net pads " + rework.net_pads + ", wires removed " + rework.wires_removed + ", vias removed "
                + rework.vias_removed + ", nets " + rework.nets_touched + ", carried " + rework.carried);
        for (String line : rework.lines)
        {
            System.out.println("  " + line);
        }
    }
}
