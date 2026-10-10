package org.beconscient.category;

import org.beconscient.derive.PhoneSegmenter;

/** Subject keys of contract/api.md: app:&lt;package&gt;, app:&lt;package&gt;#shorts, site:youtube, site:linkedin. */
public final class Subjects {

    private Subjects() {
    }

    public static boolean valid(String key) {
        if (key == null || key.length() > 300) {
            return false;
        }
        if (key.startsWith("site:")) {
            return key.equals("site:youtube") || key.equals("site:linkedin");
        }
        return key.startsWith("app:") && key.length() > 4;
    }

    public static String kind(String key) {
        return key.startsWith("site:") ? "site" : "app";
    }

    /** No app names are ever collected, so the label is the package, except for the well-known subjects. */
    public static String label(String key) {
        if (PhoneSegmenter.SHORTS_SUBJECT.equals(key)) {
            return "YouTube Shorts";
        }
        return switch (key) {
            case "site:youtube" -> "YouTube (computer)";
            case "site:linkedin" -> "LinkedIn (computer)";
            default -> key.startsWith("app:") ? key.substring(4) : key;
        };
    }
}
