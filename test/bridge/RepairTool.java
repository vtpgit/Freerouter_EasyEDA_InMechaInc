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

/** Test tool: RepairTool <in.dsn> <out.dsn> [reorder]. Prints the repair notes. */
public final class RepairTool
{
    public static void main(String[] p_args) throws Exception
    {
        DsnRepair.Options options = new DsnRepair.Options();
        options.reorder_layers = p_args.length > 2 && p_args[2].equals("reorder");
        DsnRepair.Result r = DsnRepair.repair(new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8), options);
        Files.write(Paths.get(p_args[1]), r.text.getBytes(StandardCharsets.UTF_8));
        System.out.println(String.join(" | ", r.notes) + (r.renamed_nets.isEmpty() ? "" : " | map " + r.renamed_nets));
    }
}
