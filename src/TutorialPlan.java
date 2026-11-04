import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tutorial's world and rounds: always the same planet (SEED), its exercises (Stage) in a set
 * order, and where each is played and in what weather (Site), kept in
 * assets/text/tutorial_rounds.txt. That file is written by TutorialFinder, which builds the
 * planet without GL and looks over it for each exercise's kind of place (see TutorialSites);
 * when the game's generation changes, the places it chose may no longer suit, so the finder is
 * run again (the update-tutorial skill says how). The world's own size and sea level are kept
 * with them, so a file for an older world is known for what it is.
 */
final class TutorialPlan {

    /** The tutorial's planet, every time. */
    static final long SEED = 20_261_104L;

    static final String FILE = GamePaths.HOME + "assets/text/tutorial_rounds.txt";

    /**
     * The exercises, in order: each with the gadgets it gives, and whether it's one of the
     * maps' (the later ones, each showing one sort of map and passed on it).
     */
    enum Stage {
        SUN("sun"), COAST("coast"), TEMPERATURE("temperature"), ALTITUDE("altitude"), WILDLIFE("wildlife"),
        RAINFALL("rainfall"), GROUND("ground"), GRASS("grass"), WEALTH("wealth"), FLAGS("flags"),
        ROADSIDE("roadside"), ARCHITECTURE("architecture"), LANDMARKS("landmarks");

        final String key;

        Stage(String key) {
            this.key = key;
        }

        static Stage of(String key) {
            for (Stage s : values()) if (s.key.equals(key)) return s;
            return null;
        }
    }

    /**
     * Where an exercise is played: the pod's stairs (latitude and longitude in degrees, and the
     * heading down them), the time of day (radians from local noon, the morning negative), the
     * sun's declination (radians: the season), how wet the day is (-3 a clear sky to 3 a wet
     * one), and whatever else the exercise needs (its marker, the species, the landmark...).
     */
    record Site(Stage stage, double latitude, double longitude, double heading, double hour, double declination,
                float wetness, Map<String, String> extra) {

        SpherePosition place() {
            return Planet.position(Math.toRadians(latitude), Math.toRadians(longitude), 0.0);
        }

        Viewpoint viewpoint() {
            return new Viewpoint(place(), heading);
        }

        double number(String key, double otherwise) {
            String v = extra.get(key);
            return v == null ? otherwise : Double.parseDouble(v);
        }

        int integer(String key, int otherwise) {
            String v = extra.get(key);
            return v == null ? otherwise : Integer.parseInt(v);
        }

        /** A place kept with it (as latitude and longitude, under name + "Lat" and name + "Lon"), or null. */
        SpherePosition placeOf(String name) {
            if (!extra.containsKey(name + "Lat")) return null;
            return Planet.position(Math.toRadians(number(name + "Lat", 0)), Math.toRadians(number(name + "Lon", 0)), 0.0);
        }
    }

    /** What the file holds: the world it was found on ({sea level, width}) and each exercise's site. */
    record Rounds(float seaLevel, float width, Map<Stage, Site> sites) {
    }

    /** The file's rounds, or null if there's no file. */
    static Rounds read() {
        File file = new File(FILE);
        if (!file.exists()) return null;
        try {
            float sea = Float.NaN, width = Float.NaN;
            Map<Stage, Site> sites = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\\s+");
                if (parts[0].equals("world")) {
                    Map<String, String> v = pairs(parts);
                    sea = Float.parseFloat(v.get("sea"));
                    width = Float.parseFloat(v.get("width"));
                    continue;
                }
                Stage stage = Stage.of(parts[0]);
                if (stage == null) continue;
                Map<String, String> v = pairs(parts);
                double lat = Double.parseDouble(v.remove("lat")), lon = Double.parseDouble(v.remove("lon"));
                double heading = Double.parseDouble(v.remove("heading")), hour = Double.parseDouble(v.remove("hour"));
                double declination = Double.parseDouble(v.remove("declination"));
                float wet = Float.parseFloat(v.remove("wetness"));
                sites.put(stage, new Site(stage, lat, lon, heading, hour, declination, wet, v));
            }
            return new Rounds(sea, width, sites);
        } catch (Exception e) {
            System.err.println("Tutorial rounds unreadable: " + e);
            return null;
        }
    }

    private static Map<String, String> pairs(String[] parts) {
        Map<String, String> v = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) v.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
        }
        return v;
    }

    /** Writes the rounds found (see TutorialFinder), with a note at the top of what each line is. */
    static void write(File to, float seaLevel, float width, List<Site> sites, List<String> notes) throws java.io.IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# The tutorial's rounds, found by TutorialFinder on seed " + SEED + " (see TutorialPlan, and");
        lines.add("# .claude/skills/update-tutorial). Each: the stage, the pod's stairs (lat, lon in degrees, heading in");
        lines.add("# radians from east towards south), the hour (radians from noon), the sun's declination (radians),");
        lines.add("# how wet the day is (-3 clear to 3 wet), then what the stage needs. Written again whenever the");
        lines.add("# game's generation changes.");
        for (String note : notes) lines.add("# " + note);
        lines.add(String.format(Locale.ROOT, "world sea=%s width=%s", Float.toString(seaLevel), Float.toString(width)));
        for (Site s : sites) {
            StringBuilder b = new StringBuilder(s.stage().key);
            b.append(String.format(Locale.ROOT, " lat=%.6f lon=%.6f heading=%.5f hour=%.5f declination=%.5f wetness=%.2f",
                    s.latitude(), s.longitude(), s.heading(), s.hour(), s.declination(), s.wetness()));
            for (Map.Entry<String, String> e : s.extra().entrySet()) b.append(' ').append(e.getKey()).append('=').append(e.getValue());
            lines.add(b.toString());
        }
        Files.write(to.toPath(), lines, StandardCharsets.UTF_8);
    }

    /** A place as latitude and longitude in degrees, for the file. */
    static void putPlace(Map<String, String> extra, String name, SpherePosition p) {
        extra.put(name + "Lat", String.format(Locale.ROOT, "%.6f", Math.toDegrees(Planet.latitude(p))));
        extra.put(name + "Lon", String.format(Locale.ROOT, "%.6f", Math.toDegrees(Planet.longitude(p))));
    }
}
