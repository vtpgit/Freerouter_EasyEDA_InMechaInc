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

/**
 * Test tool: SesTool <original.dsn> <output.ses> <result.json>.
 * Converts a session file of the engine to the answer that the editor gets, with the converter of the bridge.
 */
public final class SesTool
{
    public static void main(String[] p_args) throws Exception
    {
        String dsn = new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8);
        String ses = new String(Files.readAllBytes(Paths.get(p_args[1])), StandardCharsets.UTF_8);
        DsnRepair.Result repaired = DsnRepair.repair(dsn, new DsnRepair.Options());
        SesConverter.Result result = SesConverter.convert(ses, repaired.renamed_nets);
        String json = "{\"a\": \"routingResult\", \"inCompleteNetNum\": -1, \"complete\": 1, \"data\": " + result.json + "}";
        Files.write(Paths.get(p_args[2]), json.getBytes(StandardCharsets.UTF_8));
        System.out.println("nets " + result.net_count + " wires " + result.wire_count + " vias " + result.via_count
                + " length " + result.wire_length + " repair notes " + repaired.notes);
    }
}
