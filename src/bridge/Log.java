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

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Console output with a time stamp. */
final class Log
{
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log()
    {
    }

    static synchronized void info(String p_message)
    {
        System.out.println(LocalTime.now().format(TIME) + "  " + p_message);
    }

    static synchronized void error(String p_message)
    {
        System.out.println(LocalTime.now().format(TIME) + "  ERROR  " + p_message);
    }
}
