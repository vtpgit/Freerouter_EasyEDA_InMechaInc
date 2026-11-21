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

/** Test tool: EeResultTool <board.json> <output.ses> <out.json> [rules.json]. Puts an engine result into the EasyEDA file. */
public final class EeResultTool
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
        Files.write(Paths.get(p_args[2]), result.text.getBytes(StandardCharsets.UTF_8));
        System.out.println("tracks " + result.track_count + ", vias " + result.via_count + " " + result.vias_by_size + ", removed " + result.removed_records
                + ", areas reset " + result.areas_reset + ", narrow segments " + result.narrow_segments + ", length " + result.length_by_layer);
    }
}
