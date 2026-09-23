package com.planj.phone;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class Fmt {
    private Fmt() {}

    static String duration(long ms) {
        long minutes = ms / 60000;
        if (minutes < 1) return "<1m";
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    static String shortDuration(long ms) {
        long minutes = ms / 60000;
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "m");
    }

    static String longDate(LocalDate day) {
        return day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH));
    }

    static String shortDate(LocalDate day) {
        return day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH));
    }

    static String clock(long ms) {
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new java.util.Date(ms));
    }
}
