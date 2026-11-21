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

/** Test tool: VariantTool <in.dsn> <out.dsn> <seed>. Writes the board text in the order of the seed. */
public final class VariantTool
{
    public static void main(String[] p_args) throws Exception
    {
        String text = new String(Files.readAllBytes(Paths.get(p_args[0])), StandardCharsets.UTF_8);
        Files.write(Paths.get(p_args[1]), DsnVariant.shuffle(text, Long.parseLong(p_args[2])).getBytes(StandardCharsets.UTF_8));
    }
}
