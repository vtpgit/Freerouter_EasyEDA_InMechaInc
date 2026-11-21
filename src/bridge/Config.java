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

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Settings from config/main.json. Every key is optional. */
final class Config
{
    Path home;

    // web
    String ip = "127.0.0.1";
    int port = 3579;
    /** Empty: any web page origin may connect, as with the original EasyEDA router. */
    final List<String> allowed_origins = new ArrayList<String>();

    // router
    /** Lower limit of the job budget in ms. The editor sends 300 s. Same key as the original router. */
    int min_timeout_ms = 300000;
    int keep_heartbeat_ms = 5000;
    /** Time kept free at the end of the budget for writing and converting the result. */
    int finish_margin_ms = 10000;
    /**
     * Part of the engine time that the fanout stage (escape vias for SMD pads) may use. The engine
     * has no limit of its own and can spend the whole job there. 0: no limit.
     */
    double fanout_share = 0.25;
    /**
     * Part of the engine time that the last stage (the optimizer, it shortens tracks and removes vias
     * after everything is connected) may use. One test run spent 180 s there and changed nothing. 0: no limit.
     */
    double optimizer_share = 0.2;
    /** Send routingProgress messages (open connection count, no geometry) between heartbeats. */
    boolean send_progress = false;
    /**
     * Engine runs per job. Each run gets the board in a different order or with different engine
     * settings, and the best result goes to the editor. 0: automatic, see runs_for_job().
     */
    int parallel_runs = 0;
    /**
     * When one run has connected everything, the other runs get this multiple of its time (plus 5 s)
     * to finish. Then the bridge stops them and takes the best result so far. 0: always wait for every run.
     */
    double early_stop_factor = 1.5;
    /** Jobs at the same time. Each job starts parallel_runs engines. */
    int max_parallel_jobs = 1;
    /** Number of job folders (input, result, engine log) to keep. 0: delete each job folder at its end. */
    int keep_jobs = 10;

    // engine
    String engine_jar = "engine/freerouting-2.5.0.jar";
    /** Java executable for the engine. Empty: runtime/bin/java in the bridge folder, else the Java that runs the bridge. */
    String java = "";
    /** true: the engine gets no network access (no version check, no usage data). */
    boolean engine_offline = true;
    /** Heap limit of one engine process in MB. 0: automatic (no limit for a single run, RUN_HEAP_MB for parallel runs). */
    int engine_max_heap_mb = 0;
    /**
     * Hexadecimal mask of the logical processors that the engine may use, for example "ff" for
     * processors 0 to 7. Empty: no restriction.
     */
    String engine_affinity_mask = "";
    final List<String> engine_args = new ArrayList<String>();

    final DsnRepair.Options repair = new DsnRepair.Options();

    /** Heap limit of one engine when several run at the same time. The largest test board needs 320 MB of live data. */
    static final int RUN_HEAP_MB = 768;
    /** Memory of one engine process outside its heap, in MB (measured: about 280). */
    static final int RUN_OVERHEAD_MB = 300;
    /** Memory that stays free for the editor and the system, in MB. */
    static final int MEMORY_RESERVE_MB = 1024;

    static Config load() throws IOException
    {
        Config config = new Config();
        config.home = find_home();
        Path file = config.home.resolve("config").resolve("main.json");
        if (!Files.isRegularFile(file))
        {
            Log.info("No config/main.json found in " + config.home + ". Using the built-in defaults.");
            return config;
        }
        Map<String, Object> root = Json.parse_object(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        Map<String, Object> web = section(root, "web");
        config.ip = Json.as_string(web.get("ip"), config.ip);
        config.port = Json.as_int(web.get("port"), config.port);
        add_strings(web.get("allowed_origins"), config.allowed_origins);

        Map<String, Object> router = section(root, "router");
        config.min_timeout_ms = Json.as_int(router.get("min_timeout"), config.min_timeout_ms);
        config.keep_heartbeat_ms = Math.max(1000, Json.as_int(router.get("keep_heartbeat"), config.keep_heartbeat_ms));
        config.finish_margin_ms = Math.max(5000, Json.as_int(router.get("finish_margin"), config.finish_margin_ms));
        config.send_progress = Json.as_bool(router.get("send_progress"), config.send_progress);
        Object share = router.get("fanout_share");
        if (share instanceof Number)
        {
            config.fanout_share = Math.max(0.0, Math.min(0.9, ((Number) share).doubleValue()));
        }
        Object optimizer = router.get("optimizer_share");
        if (optimizer instanceof Number)
        {
            config.optimizer_share = Math.max(0.0, Math.min(0.9, ((Number) optimizer).doubleValue()));
        }
        config.max_parallel_jobs = Math.max(1, Json.as_int(router.get("max_parallel_jobs"), config.max_parallel_jobs));
        config.parallel_runs = Math.max(0, Math.min(64, Json.as_int(router.get("parallel_runs"), config.parallel_runs)));
        Object factor = router.get("early_stop_factor");
        if (factor instanceof Number)
        {
            double value = ((Number) factor).doubleValue();
            config.early_stop_factor = value <= 0.0 ? 0.0 : Math.max(1.0, value);
        }
        config.keep_jobs = Math.max(0, Json.as_int(router.get("keep_jobs"), config.keep_jobs));

        Map<String, Object> engine = section(root, "engine");
        config.engine_jar = Json.as_string(engine.get("jar"), config.engine_jar);
        config.java = Json.as_string(engine.get("java"), config.java);
        config.engine_offline = Json.as_bool(engine.get("offline"), config.engine_offline);
        config.engine_max_heap_mb = Json.as_int(engine.get("max_heap_mb"), config.engine_max_heap_mb);
        String mask = Json.as_string(engine.get("affinity_mask"), "").trim();
        if (mask.startsWith("0x") || mask.startsWith("0X"))
        {
            mask = mask.substring(2);
        }
        if (mask.matches("[0-9a-fA-F]{1,16}") && !mask.matches("0+"))
        {
            config.engine_affinity_mask = mask.toLowerCase();
        }
        else if (!mask.isEmpty())
        {
            Log.error("engine.affinity_mask \"" + mask + "\" is not a hexadecimal number. Ignored.");
        }
        add_strings(engine.get("args"), config.engine_args);

        Map<String, Object> repair = section(root, "repair");
        config.repair.join_outline = Json.as_bool(repair.get("join_outline"), true);
        config.repair.fix_zero_size_pads = Json.as_bool(repair.get("fix_zero_size_pads"), true);
        config.repair.rename_nets = Json.as_bool(repair.get("rename_nets"), true);
        config.repair.drop_protected_nets = Json.as_bool(repair.get("drop_protected_nets"), true);
        config.repair.reorder_layers = Json.as_bool(repair.get("reorder_layers"), false);
        return config;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> p_root, String p_name)
    {
        Object value = p_root.get(p_name);
        if (value instanceof Map)
        {
            return (Map<String, Object>) value;
        }
        return new java.util.LinkedHashMap<String, Object>();
    }

    private static void add_strings(Object p_value, List<String> p_target)
    {
        if (p_value instanceof List)
        {
            for (Object item : (List<?>) p_value)
            {
                if (item instanceof String && !((String) item).isEmpty())
                {
                    p_target.add((String) item);
                }
            }
        }
    }

    /** The folder that holds bridge.jar, config/ and engine/. Override with -Dbridge.home=<folder>. */
    private static Path find_home()
    {
        String property = System.getProperty("bridge.home");
        if (property != null && !property.isEmpty())
        {
            return Paths.get(property).toAbsolutePath().normalize();
        }
        try
        {
            File location = new File(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (location.isFile())
            {
                return location.getParentFile().toPath().toAbsolutePath().normalize();
            }
        }
        catch (Exception e)
        {
            // fall through to the working directory
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    Path engine_jar_path()
    {
        Path p = Paths.get(engine_jar);
        return p.isAbsolute() ? p : home.resolve(p).normalize();
    }

    static boolean is_windows()
    {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Free physical memory in MB, or -1 when the system does not tell. */
    static long free_memory_mb()
    {
        // Test hook: -Dbridge.test_free_memory_mb=<n> replaces the figure of the system.
        String test_value = System.getProperty("bridge.test_free_memory_mb");
        if (test_value != null && test_value.matches("\\d+"))
        {
            return Long.parseLong(test_value);
        }
        try
        {
            Path meminfo = Paths.get("/proc/meminfo");
            if (Files.isReadable(meminfo))
            {
                for (String line : Files.readAllLines(meminfo, StandardCharsets.UTF_8))
                {
                    if (line.startsWith("MemAvailable:"))
                    {
                        return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
                    }
                }
            }
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean)
            {
                return ((com.sun.management.OperatingSystemMXBean) os).getFreeMemorySize() / (1024L * 1024L);
            }
        }
        catch (Exception | LinkageError e)
        {
            // no figure
        }
        return -1;
    }

    /** Heap limit for one engine of a job with p_runs runs. 0: no limit. */
    int heap_mb_for(int p_runs)
    {
        if (engine_max_heap_mb > 0)
        {
            return engine_max_heap_mb;
        }
        return p_runs > 1 ? RUN_HEAP_MB : 0;
    }

    /** Number of runs that the setting or the processor count asks for, before the memory check. */
    int wanted_runs()
    {
        if (parallel_runs > 0)
        {
            return parallel_runs;
        }
        // One run per 4 logical processors, at most 4. A laptop processor slows down when all cores work,
        // and slow cores finish fewer routing passes. More runs than this is a choice for the user.
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
    }

    /**
     * Number of engine runs for a job that starts now. The free memory can lower the wanted number.
     *
     * @param p_note receives one sentence when the memory lowers the number, else stays empty
     */
    int runs_for_job(StringBuilder p_note)
    {
        int wanted = wanted_runs();
        if (wanted <= 1)
        {
            return 1;
        }
        long free = free_memory_mb();
        if (free < 0)
        {
            return wanted;
        }
        int per_run = heap_mb_for(wanted) + RUN_OVERHEAD_MB;
        int by_memory = (int) Math.max(1L, (free - MEMORY_RESERVE_MB) / per_run);
        if (by_memory < wanted)
        {
            p_note.append("Free memory is ").append(free).append(" MB. That is enough for ").append(by_memory)
                    .append(" of the ").append(wanted).append(" runs (").append(per_run).append(" MB each, ")
                    .append(MEMORY_RESERVE_MB).append(" MB kept free).");
            return by_memory;
        }
        return wanted;
    }

    /** The Java executable that runs the engine. */
    String engine_java()
    {
        if (java != null && !java.isEmpty())
        {
            return java;
        }
        boolean windows = is_windows();
        Path bundled = home.resolve("runtime").resolve("bin").resolve(windows ? "java.exe" : "java");
        if (Files.isRegularFile(bundled))
        {
            return bundled.toString();
        }
        return ProcessHandle.current().info().command().orElse("java");
    }
}
