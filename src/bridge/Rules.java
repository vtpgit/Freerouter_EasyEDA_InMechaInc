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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Routing rules that the EasyEDA editor does not send: config/rules.json.
 *
 * All lengths are real millimetres. The bridge converts them to changes of the board text and to
 * engine settings. The file is read again at the start of each job, so a change needs no restart.
 */
final class Rules
{
    // ---- time
    /** Job time in seconds for the file interface. The editor channel takes the time from the editor. */
    int job_seconds = 600;

    // ---- board edge
    /** Distance of new copper from the board edge. */
    double copper_to_edge_mm = 0.3;

    // ---- vias
    /** off: no via on a pad. needed: the bridge puts a via into each pad that has no exit (file interface only). all: the engine may use any pad. */
    /**
     * off, needed, all, or mixed. mixed: as needed, and each second run of a board file job has the permission of all
     * (the engine may put the small via into any pad of the net). The job takes the result with the fewest open
     * connections, and at an equal count the result with more vias in pads.
     */
    String via_on_pads = "mixed";
    /**
     * Pads of a net with a plane: none: no extra via. fine_pitch: a pad up to fine_pitch_pad_mm wide gets a via in the pad
     * into the plane. all: every surface-mount pad of such a net gets one where it fits. File interface only.
     */
    String plane_pad_vias = "all";
    /**
     * on: before the engine starts, each surface-mount pad of a plane net on a part with up to 4 pads gets a short track
     * to a standard via beside the pad, where there is room. These are fixed for the engine. File interface only.
     */
    String plane_stubs = "off";
    double fine_pitch_pad_mm = 0.3;
    /** Pads by name (part.pad, for example U1.A17) that get a via in the pad. For pads that stay open in a first job. File interface only. */
    final List<String> pad_via_list = new ArrayList<String>();
    /** Via for pads. Used by the file interface. */
    double pad_via_diameter_mm = 0.249;
    double pad_via_hole_mm = 0.15;
    /** Standard via. 0: the value of the board file. */
    double via_diameter_mm = 0.0;
    double via_hole_mm = 0.0;
    /** Engine cost of one via, in units of track length. Engine default 50. */
    int via_cost = 50;
    /** Engine cost of a via of a net that has a plane. Engine default 5. */
    int plane_via_cost = 5;

    // ---- planes
    /** none: copper areas are not used. inner: a copper area on an inner layer makes its net a plane net. all: areas on outer layers too. */
    String planes_from_areas = "inner";
    /**
     * A pad that lies in a copper area of its net on its own layer (a pour), when the net has areas on 2 or more layers.
     * after: the engine does not connect the pad. After routing the bridge models the fill and joins what the
     * signal tracks have cut off, with a via in the island or in the pad. engine: the engine connects every pad
     * of the net with a track and a via to the plane on the inner layer.
     */
    String pours = "after";
    /** Planes by hand: net and layer. For the editor channel, which sends no copper areas. */
    final List<PlaneEntry> planes = new ArrayList<PlaneEntry>();

    // ---- layer use
    /** rules: the cost factors below are sent to the engine. engine: the engine sets its own factors (outer layers cost more on boards with inner layers). */
    String layer_costs = "rules";
    /** Cost factor of a track on a layer. A higher factor moves tracks to other layers. */
    double top_track_cost = 1.0;
    double bottom_track_cost = 1.15;
    double inner_track_cost = 1.6;
    /** Layer with a plane of a supply net. */
    double supply_plane_track_cost = 4.0;
    /** Layer with a plane of a ground net. A high factor keeps the plane whole. */
    double ground_plane_track_cost = 4.0;
    /** Cost factor by layer name (top, bottom, inner1, ...). It replaces the factors above for that layer. */
    final Map<String, Double> track_cost_by_layer = new java.util.LinkedHashMap<String, Double>();
    /**
     * on: after routing, a via or a pad with a hole of a plane net that the fill of its plane does not join to the
     * main piece gets a track on the plane layer to the main piece. off: no such tracks. File interface only.
     */
    String plane_bridges = "on";
    /** A pad in a copper area of its net counts as connected by the area when the fill is beside this part of its outline, 0 to 1. */
    double pour_contact = 0.3;
    /** on: after the first result the engine routes again around vias for pads that tracks cut off from their copper area. */
    String second_pass = "on";
    /** Largest length of a track that the bridge adds on an outer layer from a pad to a via or to copper of its net. 0: none. */
    double repair_track_mm = 4.0;
    /**
     * A copper area of a net that is not ground (a supply net).
     * plane: the engine connects the pads of the net with vias into the area.
     * tracks: the engine routes the net with tracks. The area is not used as a connection. Use this when signal
     * tracks share the layer of the area, because they cut the area into pieces.
     */
    String supply_planes = "plane";
    /** Net names that are ground. */
    Pattern ground_net = Pattern.compile("(?i)^(A|D|P|S)?GND.*|^VSS.*|^0V$|^EARTH$|^GROUND$");

    // ---- first engine stage (escape tracks with a via for surface-mount pads)
    /** Smallest distance of an escape via from its pad centre. Engine default 2.5. */
    double first_stage_min_via_distance_mm = 0.0;
    /** Largest length of an escape track. Engine default 4.5. */
    double first_stage_max_escape_mm = 3.0;

    // ---- tracks
    /**
     * The engine narrows a track where the full width does not fit between pads.
     * allow: no action. warn: the narrow segments are counted and reported.
     * repair: file interface: after routing each narrow segment gets the full width where the clearance permits. The rest is reported.
     *         Editor channel: the same as warn, because the bridge has no pad shapes there.
     * avoid: repair, and the engine gets no first stage and does not narrow at pads, and a run with fewer narrow segments wins.
     */
    String narrow_tracks = "mixed";
    /** on: the tracks of a board file job are joined and cleaned. join: joined only, no change of copper. off. */
    String tidy_tracks = "on";
    /** The engine tries a failed connection again with this width. 0: off. */
    double retry_width_mm = 0.0;

    // ---- nets by name pattern
    final List<NetEntry> nets = new ArrayList<NetEntry>();

    // ---- keep-out
    /** Margin around copper without net inside a footprint (an antenna). The box with this margin is closed on all layers. Below 0: off. */
    double antenna_margin_mm = 0.3;
    /** hull: the closed area around antenna copper follows the copper. box: the rectangle around the copper. */
    String antenna_shape = "hull";
    /** Distance of new copper from a hole that is not plated. */
    double hole_clearance_mm = 0.2;
    final List<KeepoutEntry> keepouts = new ArrayList<KeepoutEntry>();

    // ---- existing copper of the file
    /** keep: tracks and vias of the file stay and are fixed. remove: tracks and vias that are not locked are removed before routing. */
    String existing = "keep";
    /**
     * Where the track width, the clearance and the via size of a board file come from. design_rules: the values of
     * the Design Rule dialog. auto_router: the values of the Auto Router dialog, which the editor stores in the file.
     */
    String rules_from = "design_rules";
    /**
     * on: in the further passes of a board file job, a net that is open in the best result can get its route from the
     * result of another run. The tracks in the way of that route are routed again. off: no such step.
     */
    String recombine = "on";

    /**
     * Small moves of parts (board file interface). off, or on: a part of the list movable_parts can shift by a part
     * of a millimetre when this makes room for a via in a pad that tracks cut off from its copper area.
     */
    String part_moves = "off";
    /** Regular expressions for the whole reference of a part that can move. */
    final List<String> movable_parts = new ArrayList<String>();
    {
        movable_parts.add("R[0-9]+");
        movable_parts.add("C[0-9]+");
        movable_parts.add("L[0-9]+");
    }
    /** Regular expressions for references that never move. They come before movable_parts. */
    final List<String> fixed_parts = new ArrayList<String>();
    /** A part with more pads does not move. */
    int movable_max_pads = 2;
    /** Largest shift of a part in mm. */
    double part_shift_mm = 0.15;
    /** Gap between the bodies of two parts in mm. A move can make a gap smaller only down to this value. */
    double part_gap_mm = 0.15;

    /** true: the rules permit a move of the part with this reference. */
    boolean part_movable(String p_ref)
    {
        for (String pattern : fixed_parts)
        {
            if (matches(pattern, p_ref))
            {
                return false;
            }
        }
        for (String pattern : movable_parts)
        {
            if (matches(pattern, p_ref))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String p_pattern, String p_text)
    {
        try
        {
            return p_text.matches(p_pattern);
        }
        catch (java.util.regex.PatternSyntaxException e)
        {
            return false;
        }
    }

    static final class PlaneEntry
    {
        String net;
        /** top, bottom, inner1, inner2, ... */
        String layer;
    }

    static final class NetEntry
    {
        Pattern match;
        String source;
        /** 0: no change. */
        double width_mm = 0.0;
        double clearance_mm = 0.0;
        /** Layers that the net may use. Empty: all. */
        final List<String> layers = new ArrayList<String>();
        boolean skip = false;
        /** After routing each track segment of the net gets the largest width up to this value that keeps the clearance. 0: off. */
        double widen_to_mm = 0.0;
    }

    static final class KeepoutEntry
    {
        /** Layer names. Empty: all layers. */
        final List<String> layers = new ArrayList<String>();
        double x0, y0, x1, y1;
    }

    /** Messages about entries that were not understood. */
    final List<String> warnings = new ArrayList<String>();

    /** true: a board file is read with the design rules first. */
    boolean design_rules_first()
    {
        return !rules_from.equals("auto_router");
    }

    static Rules load(Path p_file)
    {
        Rules rules = new Rules();
        if (!Files.isRegularFile(p_file))
        {
            return rules;
        }
        try
        {
            String text = new String(Files.readAllBytes(p_file), StandardCharsets.UTF_8);
            rules.read(Json.parse_object(text));
        }
        catch (IOException | RuntimeException e)
        {
            rules.warnings.add("config/rules.json could not be read (" + e.getMessage() + "). Default rules are used.");
        }
        return rules;
    }

    private static Map<String, Object> section(Map<String, Object> p_root, String p_key)
    {
        Object value = p_root.get(p_key);
        if (value instanceof Map)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return map;
        }
        return new java.util.HashMap<String, Object>();
    }

    private static double as_double(Object p_value, double p_default)
    {
        return p_value instanceof Number ? ((Number) p_value).doubleValue() : p_default;
    }

    private String one_of(Object p_value, String p_default, String p_key, String... p_allowed)
    {
        if (p_value == null)
        {
            return p_default;
        }
        String text = String.valueOf(p_value).trim().toLowerCase();
        for (String allowed : p_allowed)
        {
            if (allowed.equals(text))
            {
                return text;
            }
        }
        warnings.add("rules.json: " + p_key + " = \"" + p_value + "\" is not one of " + String.join(", ", p_allowed) + ". Using \"" + p_default + "\".");
        return p_default;
    }

    private void read(Map<String, Object> p_root)
    {
        Map<String, Object> time = section(p_root, "time");
        job_seconds = Math.max(30, Json.as_int(time.get("job_s"), job_seconds));

        Map<String, Object> edge = section(p_root, "edge");
        copper_to_edge_mm = Math.max(0.0, as_double(edge.get("copper_to_edge_mm"), copper_to_edge_mm));

        Map<String, Object> via = section(p_root, "via");
        via_on_pads = one_of(via.get("on_pads"), via_on_pads, "via.on_pads", "off", "needed", "all", "mixed");
        plane_pad_vias = one_of(via.get("plane_pads"), plane_pad_vias, "via.plane_pads", "none", "fine_pitch", "all");
        fine_pitch_pad_mm = Math.max(0.0, as_double(via.get("fine_pitch_pad_mm"), fine_pitch_pad_mm));
        plane_stubs = one_of(via.get("plane_stubs"), plane_stubs, "via.plane_stubs", "off", "on");
        Object pad_list = via.get("pads");
        if (pad_list instanceof List)
        {
            for (Object item : (List<?>) pad_list)
            {
                pad_via_list.add(String.valueOf(item).trim());
            }
        }
        pad_via_diameter_mm = as_double(via.get("pad_via_diameter_mm"), pad_via_diameter_mm);
        pad_via_hole_mm = as_double(via.get("pad_via_hole_mm"), pad_via_hole_mm);
        via_diameter_mm = as_double(via.get("diameter_mm"), via_diameter_mm);
        via_hole_mm = as_double(via.get("hole_mm"), via_hole_mm);
        via_cost = Math.max(1, Json.as_int(via.get("cost"), via_cost));
        plane_via_cost = Math.max(1, Json.as_int(via.get("plane_cost"), plane_via_cost));

        Map<String, Object> plane_section = section(p_root, "planes");
        planes_from_areas = one_of(plane_section.get("from_copper_areas"), planes_from_areas, "planes.from_copper_areas", "none", "inner", "all");
        Object list = plane_section.get("list");
        if (list instanceof List)
        {
            for (Object item : (List<?>) list)
            {
                if (item instanceof Map)
                {
                    Map<?, ?> map = (Map<?, ?>) item;
                    PlaneEntry entry = new PlaneEntry();
                    entry.net = Json.as_string(map.get("net"), "");
                    entry.layer = Json.as_string(map.get("layer"), "").toLowerCase();
                    if (!entry.net.isEmpty() && !entry.layer.isEmpty())
                    {
                        planes.add(entry);
                    }
                }
            }
        }

        Map<String, Object> layers = section(p_root, "layers");
        layer_costs = one_of(layers.get("costs"), layer_costs, "layers.costs", "rules", "engine");
        top_track_cost = Math.max(0.1, as_double(layers.get("top_track_cost"), top_track_cost));
        bottom_track_cost = Math.max(0.1, as_double(layers.get("bottom_track_cost"), bottom_track_cost));
        inner_track_cost = Math.max(0.1, as_double(layers.get("inner_track_cost"), inner_track_cost));
        supply_plane_track_cost = Math.max(0.1, as_double(layers.get("supply_plane_track_cost"), supply_plane_track_cost));
        ground_plane_track_cost = Math.max(0.1, as_double(layers.get("ground_plane_track_cost"), ground_plane_track_cost));
        Object by_layer = layers.get("by_layer");
        if (by_layer instanceof Map)
        {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) by_layer).entrySet())
            {
                if (entry.getValue() instanceof Number)
                {
                    track_cost_by_layer.put(String.valueOf(entry.getKey()).toLowerCase(), Math.max(0.1, ((Number) entry.getValue()).doubleValue()));
                }
            }
        }
        pours = one_of(plane_section.get("pours"), pours, "planes.pours", "after", "engine");
        supply_planes = one_of(plane_section.get("supply"), supply_planes, "planes.supply", "plane", "tracks");
        plane_bridges = one_of(plane_section.get("bridge_tracks"), plane_bridges, "planes.bridge_tracks", "on", "off");
        pour_contact = Math.max(0.0, Math.min(1.0, as_double(plane_section.get("pour_contact"), pour_contact)));
        second_pass = one_of(plane_section.get("second_pass"), second_pass, "planes.second_pass", "on", "off");
        repair_track_mm = Math.max(0.0, Math.min(50.0, as_double(plane_section.get("repair_track_mm"), repair_track_mm)));
        Object ground = plane_section.get("ground_net");
        if (ground != null)
        {
            try
            {
                ground_net = Pattern.compile(String.valueOf(ground));
            }
            catch (PatternSyntaxException e)
            {
                warnings.add("rules.json: planes.ground_net is not a valid pattern. The default is used.");
            }
        }

        Map<String, Object> first = section(p_root, "first_stage");
        first_stage_min_via_distance_mm = Math.max(0.0, as_double(first.get("min_via_distance_mm"), first_stage_min_via_distance_mm));
        first_stage_max_escape_mm = Math.max(0.1, as_double(first.get("max_escape_mm"), first_stage_max_escape_mm));

        Map<String, Object> track = section(p_root, "track");
        narrow_tracks = one_of(track.get("narrow"), narrow_tracks, "track.narrow", "allow", "warn", "repair", "mixed", "avoid");
        tidy_tracks = one_of(track.get("tidy"), tidy_tracks, "track.tidy", "on", "join", "off");
        retry_width_mm = Math.max(0.0, as_double(track.get("retry_width_mm"), retry_width_mm));

        Object net_list = p_root.get("nets");
        if (net_list instanceof List)
        {
            for (Object item : (List<?>) net_list)
            {
                if (!(item instanceof Map))
                {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) item;
                NetEntry entry = new NetEntry();
                entry.source = Json.as_string(map.get("match"), "");
                try
                {
                    entry.match = Pattern.compile(entry.source);
                }
                catch (PatternSyntaxException e)
                {
                    warnings.add("rules.json: nets: the pattern \"" + entry.source + "\" is not valid. Entry ignored.");
                    continue;
                }
                entry.width_mm = Math.max(0.0, as_double(map.get("width_mm"), 0.0));
                entry.clearance_mm = Math.max(0.0, as_double(map.get("clearance_mm"), 0.0));
                entry.skip = Json.as_bool(map.get("skip"), false);
                entry.widen_to_mm = Math.max(0.0, as_double(map.get("widen_to_mm"), 0.0));
                Object entry_layers = map.get("layers");
                if (entry_layers instanceof List)
                {
                    for (Object layer : (List<?>) entry_layers)
                    {
                        entry.layers.add(String.valueOf(layer).toLowerCase());
                    }
                }
                if (!entry.source.isEmpty())
                {
                    nets.add(entry);
                }
            }
        }

        Map<String, Object> keepout = section(p_root, "keepout");
        antenna_margin_mm = as_double(keepout.get("antenna_margin_mm"), antenna_margin_mm);
        antenna_shape = one_of(keepout.get("antenna_shape"), antenna_shape, "keepout.antenna_shape", "hull", "box");
        hole_clearance_mm = Math.max(0.0, as_double(keepout.get("hole_clearance_mm"), hole_clearance_mm));
        Object boxes = keepout.get("boxes");
        if (boxes instanceof List)
        {
            for (Object item : (List<?>) boxes)
            {
                if (!(item instanceof Map))
                {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) item;
                Object box = map.get("box_mm");
                if (!(box instanceof List) || ((List<?>) box).size() != 4)
                {
                    warnings.add("rules.json: keepout.boxes: box_mm needs 4 numbers. Entry ignored.");
                    continue;
                }
                List<?> numbers = (List<?>) box;
                KeepoutEntry entry = new KeepoutEntry();
                entry.x0 = Math.min(as_double(numbers.get(0), 0), as_double(numbers.get(2), 0));
                entry.x1 = Math.max(as_double(numbers.get(0), 0), as_double(numbers.get(2), 0));
                entry.y0 = Math.min(as_double(numbers.get(1), 0), as_double(numbers.get(3), 0));
                entry.y1 = Math.max(as_double(numbers.get(1), 0), as_double(numbers.get(3), 0));
                Object entry_layers = map.get("layers");
                if (entry_layers instanceof List)
                {
                    for (Object layer : (List<?>) entry_layers)
                    {
                        entry.layers.add(String.valueOf(layer).toLowerCase());
                    }
                }
                keepouts.add(entry);
            }
        }

        existing = one_of(p_root.get("existing"), existing, "existing", "keep", "remove");
        rules_from = one_of(p_root.get("rules_from"), rules_from, "rules_from", "design_rules", "auto_router");
        recombine = one_of(p_root.get("recombine"), recombine, "recombine", "on", "off");
        Object parts_block = p_root.get("parts");
        if (parts_block instanceof Map)
        {
            Map<?, ?> parts = (Map<?, ?>) parts_block;
            part_moves = one_of(parts.get("move"), part_moves, "parts.move", "off", "on");
            movable_max_pads = Math.max(1, Json.as_int(parts.get("max_pads"), movable_max_pads));
            part_shift_mm = Math.max(0.0, Math.min(1.0, as_double(parts.get("max_shift_mm"), part_shift_mm)));
            part_gap_mm = Math.max(0.0, as_double(parts.get("gap_mm"), part_gap_mm));
            for (int k = 0; k < 2; ++k)
            {
                Object entries = parts.get(k == 0 ? "movable" : "fixed");
                if (entries instanceof List)
                {
                    List<String> target = k == 0 ? movable_parts : fixed_parts;
                    target.clear();
                    for (Object item : (List<?>) entries)
                    {
                        String pattern = String.valueOf(item).trim();
                        try
                        {
                            java.util.regex.Pattern.compile(pattern);
                            target.add(pattern);
                        }
                        catch (java.util.regex.PatternSyntaxException e)
                        {
                            warnings.add("rules.json: parts." + (k == 0 ? "movable" : "fixed") + ": \"" + pattern + "\" is not a regular expression. Entry ignored.");
                        }
                    }
                }
            }
        }
    }

    /** The first entry of the net list that matches the whole name, or null. */
    NetEntry net_entry(String p_net)
    {
        for (NetEntry entry : nets)
        {
            if (entry.match.matcher(p_net).matches())
            {
                return entry;
            }
        }
        return null;
    }
}
