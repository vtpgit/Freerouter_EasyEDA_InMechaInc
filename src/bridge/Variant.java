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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One way to run the engine on a board. The bridge starts several variants at the same time
 * and keeps the best result.
 */
final class Variant
{
    /** The line of the board text that lets the engine put a via on a pad of the same net. */
    static final String PAD_VIA_LINE = "    (control (via_at_smd on))\n";
    private static final Pattern SETTINGS_SCOPE = Pattern.compile("(?s)    \\(autoroute_settings\\n.*?\\n    \\)\\n");

    /** Short name for the log. */
    final String name;
    /** 0: the order of the editor. Other values: a different order of pads and nets, see DsnVariant. */
    final long order_seed;
    /** Extra engine arguments. They come after engine.args of the configuration. */
    final List<String> args;
    /**
     * true: the run of version 1.1. The engine gets the board text of the editor without the settings of the rules:
     * no via on pads, the layer costs of the engine, and the first stage with the lengths that version 1.1 gave.
     */
    final boolean classic;

    Variant(String p_name, long p_order_seed, List<String> p_args)
    {
        this(p_name, p_order_seed, p_args, false);
    }

    Variant(String p_name, long p_order_seed, List<String> p_args, boolean p_classic)
    {
        this.name = p_name;
        this.order_seed = p_order_seed;
        this.args = Collections.unmodifiableList(new ArrayList<String>(p_args));
        this.classic = p_classic;
    }

    /** Changes of the engine settings in the board text: pairs of a pattern and its replacement. */
    private final List<String[]> edits = new ArrayList<String[]>();
    /** true: the engine may put the small via into any pad of the net and at any other position. */
    boolean pad_via = false;
    /** true: the engine makes no track narrower than the rule in this run (no first stage, no narrow segments at pads). */
    boolean full_width = false;

    /** The engine gets the small via for all nets, in addition to the standard via. */
    private static final String[][] PAD_VIA_EDITS =
    {
        {
            "\\(via " + EeDsn.VIA_STANDARD + "\\)", "(via " + EeDsn.VIA_STANDARD + " " + EeDsn.VIA_PAD + ")"
        },
        {
            "\\(use_via " + EeDsn.VIA_STANDARD + "\\)", "(use_via " + EeDsn.VIA_STANDARD + " " + EeDsn.VIA_PAD + ")"
        }
    };

    /**
     * A run of a board file job from a list of words, joined with "_":
     * sent (no change), nofan (first stage off), order3 (a different order of pads and nets), swap (the preferred
     * track directions of the layers exchanged), via30 (via cost 30), ripup200 (start cost 200 for taking out a track),
     * padvia (the small via is permitted in any pad).
     */
    static Variant by_key(String p_key)
    {
        long seed = 0;
        boolean pad_via = false;
        boolean full_width = false;
        List<String> args = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        List<String[]> edits = new ArrayList<String[]>();
        for (String word : p_key.trim().toLowerCase(java.util.Locale.ROOT).split("_"))
        {
            if (word.equals("sent") || word.isEmpty())
            {
                continue;
            }
            else if (word.equals("nofan"))
            {
                args.add(NO_FANOUT);
                names.add("no fanout");
            }
            else if (word.startsWith("order"))
            {
                seed = Long.parseLong(word.substring(5));
                names.add("order " + seed);
            }
            else if (word.equals("swap"))
            {
                edits.add(new String[]
                {
                    "\\(preferred_direction horizontal\\)", "(preferred_direction @V@)"
                });
                edits.add(new String[]
                {
                    "\\(preferred_direction vertical\\)", "(preferred_direction horizontal)"
                });
                edits.add(new String[]
                {
                    "\\(preferred_direction @V@\\)", "(preferred_direction vertical)"
                });
                names.add("track directions exchanged");
            }
            else if (word.equals("full"))
            {
                full_width = true;
                names.add("no narrow tracks");
            }
            else if (word.equals("padvia"))
            {
                pad_via = true;
                edits.add(PAD_VIA_EDITS[0]);
                edits.add(PAD_VIA_EDITS[1]);
                names.add("small via permitted in any pad");
            }
            else if (word.startsWith("via"))
            {
                edits.add(new String[]
                {
                    "\\(via_costs \\d+\\)", "(via_costs " + Integer.parseInt(word.substring(3)) + ")"
                });
                names.add("via cost " + word.substring(3));
            }
            else if (word.startsWith("ripup"))
            {
                edits.add(new String[]
                {
                    "\\(start_ripup_costs \\d+\\)", "(start_ripup_costs " + Integer.parseInt(word.substring(5)) + ")"
                });
                names.add("take-out cost " + word.substring(5));
            }
            else
            {
                throw new IllegalArgumentException("unknown word for a run: " + word);
            }
        }
        if (full_width)
        {
            if (!args.contains(NO_FANOUT))
            {
                args.add(NO_FANOUT);
            }
            args.add(NO_NECKDOWN);
        }
        Variant result = new Variant(names.isEmpty() ? "as sent" : String.join(", ", names), seed, args);
        result.edits.addAll(edits);
        result.pad_via = pad_via;
        result.full_width = full_width;
        return result;
    }

    /** The board text for this run. */
    String text_for(String p_dsn)
    {
        String text = order_seed == 0 ? p_dsn : DsnVariant.shuffle(p_dsn, order_seed);
        for (String[] edit : edits)
        {
            text = text.replaceAll(edit[0], edit[1]);
        }
        if (classic)
        {
            text = SETTINGS_SCOPE.matcher(text.replace(PAD_VIA_LINE, "")).replaceFirst("");
        }
        return text;
    }

    private static final String NO_FANOUT = "--router.fanout.enabled=false";
    /** The engine does not make a segment narrower than the rule at a pad or where the full width does not fit. */
    private static final String NO_NECKDOWN = "--router.automatic_neckdown=false";

    /**
     * The first runs of a board file job, in the order of use. See by_key.
     * Measured on the test board with 4 runs at the same time, open connections after the first pass and after the
     * second pass: as sent 10 and 6, no fanout 19 and 14, track directions exchanged 11 and 8, order 1 11 and 8,
     * order 2 14 and 14, via cost 30 14 and 12, via cost 100 18 and 11, take-out cost 300 18 and 11.
     */
    private static final String[] FILE_RUNS =
    {
        "sent", "nofan", "swap", "order1"
    };

    /** A run that goes on with the result of an earlier run. The first engine stage is off. */
    static Variant continued(String p_name)
    {
        return continued(p_name, false);
    }

    /** @param p_pad_via true: the run that it goes on with had the small via permitted in any pad, and this run has it too */
    static Variant continued(String p_name, boolean p_pad_via)
    {
        return continued(p_name, p_pad_via, false);
    }

    /** @param p_full_width true: the run that it goes on with had no narrow tracks from the engine, and this run has none too */
    static Variant continued(String p_name, boolean p_pad_via, boolean p_full_width)
    {
        Variant result = new Variant(p_name, 0, p_full_width ? Arrays.asList(NO_FANOUT, NO_NECKDOWN) : Arrays.asList(NO_FANOUT));
        result.full_width = p_full_width;
        if (p_pad_via)
        {
            result.pad_via = true;
            result.edits.add(PAD_VIA_EDITS[0]);
            result.edits.add(PAD_VIA_EDITS[1]);
        }
        return result;
    }

    /**
     * The runs of a mixed job (rules: via.on_pads = mixed), in pairs: a run, and the same run with the small via
     * permitted in any pad. Pair 1 is "as sent", pair 2 is "order 1", and so on. These two were the best runs of
     * FILE_RUNS in the tests. Measured on one board with the permission for all four runs: as sent 19 open (17
     * without the permission), no fanout 31 (18), track directions exchanged 25 (23), order 1 17 (15).
     */
    static String mixed_key(int p_index)
    {
        int pair = p_index / 2;
        return (pair == 0 ? "sent" : "order" + pair) + (p_index % 2 == 1 ? "_padvia" : "");
    }

    /**
     * true: this run of a job with track.narrow = mixed gets no narrow tracks from the engine. It is each fourth run.
     * Measured on two boards with all runs in this form: one board had 0 open connections and no narrow segment
     * (2 of 4 runs complete, both with the small via in any pad). The other had 12 open connections against 2.
     * So one run of four takes the chance, and the other three keep the result of the job.
     */
    static boolean full_width_run(int p_index)
    {
        return p_index % 4 == 3;
    }

    /**
     * The runs of a job. The order of the list is the order of use: with 4 parallel runs the first 4 entries run.
     *
     * "no fanout" switches off the first engine stage (escape vias for SMD pads). On the test boards
     * that stage helped some boards and cost others most of their time, so some runs go without it.
     *
     * Editor channel: run 2 is the first run of version 1.1. In the test of version 1.1 that run had the fewest vias
     * on the boards that every run completed (556 against 736 on 23 boards). The runs with the rules complete more of the hard boards.
     *
     * @param p_first_stage false: the rules switch the first stage off for all runs, so the runs differ in the order only
     * @param p_channel     true: editor channel
     */
    static List<Variant> built_in(int p_count, boolean p_first_stage, boolean p_channel)
    {
        return built_in(p_count, p_first_stage, p_channel, false);
    }

    /** @param p_mixed true: board file job with via.on_pads = mixed. See mixed_key. */
    static List<Variant> built_in(int p_count, boolean p_first_stage, boolean p_channel, boolean p_mixed)
    {
        return built_in(p_count, p_first_stage, p_channel, p_mixed, false);
    }

    /** @param p_narrow_mixed true: board file job with track.narrow = mixed. See full_width_run. */
    static List<Variant> built_in(int p_count, boolean p_first_stage, boolean p_channel, boolean p_mixed, boolean p_narrow_mixed)
    {
        List<Variant> result = new ArrayList<Variant>();
        List<String> none = Collections.<String>emptyList();
        for (int i = 0; i < p_count; ++i)
        {
            if (p_mixed && !p_channel && System.getProperty("bridge.test.variants") == null)
            {
                result.add(by_key(mixed_key(i) + (p_narrow_mixed && full_width_run(i) ? "_full" : "")));
            }
            else if (!p_first_stage)
            {
                result.add(new Variant(i == 0 ? "as sent" : "order " + i, i, none));
            }
            else if (!p_channel)
            {
                String[] keys = FILE_RUNS;
                String test = System.getProperty("bridge.test.variants");
                if (test != null)
                {
                    keys = test.split(",");
                }
                // after the list: as sent and no fanout in turn, each with a new order
                result.add(by_key((i < keys.length ? keys[i] : (i % 2 == 0 ? "order" : "nofan_order") + (i - keys.length + 2))
                        + (p_narrow_mixed && full_width_run(i) && test == null ? "_full" : "")));
            }
            else if (i == 0)
            {
                result.add(new Variant("as sent", 0, none));
            }
            else if (i == 1)
            {
                result.add(new Variant("basic settings", 0, none, true));
            }
            else if (i == 2)
            {
                result.add(new Variant("no fanout", 0, Arrays.asList(NO_FANOUT)));
            }
            else
            {
                // order 1, no fanout order 2, settings of version 1.1 order 3, order 4, ...
                long seed = i - 2;
                int kind = (i - 3) % 3;
                if (kind == 0)
                {
                    result.add(new Variant("order " + seed, seed, none));
                }
                else if (kind == 1)
                {
                    result.add(new Variant("no fanout, order " + seed, seed, Arrays.asList(NO_FANOUT)));
                }
                else
                {
                    result.add(new Variant("basic settings, order " + seed, seed, none, true));
                }
            }
        }
        return result;
    }
}
