/**
 * The developer switches (-Dxenoguesser.* on the java command line: dev views, test runs,
 * screenshots, fixed seeds, unlocking everything, teleporting by the map, and the rest), and
 * whether they work at all.
 *
 * They work only in development: the game running from its compiled classes (as Maven and an
 * IDE run it), with -Dxenoguesser.dev=true given as well. Run from its packaged jar, as the
 * exe always does, every switch reads as unset whatever's on the command line, in the exe's
 * .cfg file or in JAVA_TOOL_OPTIONS, so none of them can be turned on in the released game
 * without taking the game itself apart and building it again.
 *
 * Every developer switch is read through here, never through System.getProperty and the like.
 */
public final class Dev {

    /** Whether developer switches work in this run (see the class's note). */
    public static final boolean ENABLED = fromClasses() && "true".equals(System.getProperty("xenoguesser.dev"));

    private Dev() {
    }

    /** Whether the game's code is being run from a folder of compiled classes rather than from a jar. */
    private static boolean fromClasses() {
        try {
            java.security.CodeSource source = Dev.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return false;
            java.io.File where = new java.io.File(source.getLocation().toURI());
            return where.isDirectory();
        } catch (Exception unknown) {
            return false;
        }
    }

    /** A switch's value; null when it isn't given (or switches don't work). */
    public static String property(String name) {
        return ENABLED ? System.getProperty(name) : null;
    }

    /** A switch's value, or the default when it isn't given (or switches don't work). */
    public static String property(String name, String otherwise) {
        return ENABLED ? System.getProperty(name, otherwise) : otherwise;
    }

    /** Whether a switch is given as true. */
    public static boolean flag(String name) {
        return ENABLED && Boolean.getBoolean(name);
    }

    /** A whole-number switch; null when it isn't given. */
    public static Integer integer(String name) {
        return ENABLED ? Integer.getInteger(name) : null;
    }

    /** A whole-number switch, or the default. */
    public static int integer(String name, int otherwise) {
        return ENABLED ? Integer.getInteger(name, otherwise) : otherwise;
    }

    /** A long whole-number switch; null when it isn't given. */
    public static Long longValue(String name) {
        return ENABLED ? Long.getLong(name) : null;
    }

    /** A long whole-number switch, or the default. */
    public static long longValue(String name, long otherwise) {
        return ENABLED ? Long.getLong(name, otherwise) : otherwise;
    }
}
