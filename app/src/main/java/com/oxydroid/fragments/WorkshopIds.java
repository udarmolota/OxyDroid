package com.oxydroid.fragments;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Steam Workshop item ids from what a player typed or pasted: bare numbers, or links with ?id=… */
final class WorkshopIds {
    private WorkshopIds() {}

    private static final Pattern ID = Pattern.compile("(?:[?&]id=)?(\\d{6,})");

    static List<Long> parse(String text) {
        Set<Long> ids = new LinkedHashSet<>();
        Matcher m = ID.matcher(text == null ? "" : text);
        while (m.find()) {
            try { ids.add(Long.parseLong(m.group(1))); } catch (NumberFormatException ignored) { }
        }
        return new ArrayList<>(ids);
    }
}
