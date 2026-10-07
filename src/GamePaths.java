import java.io.File;

/**
 * Where the game's own files (assets and models) are: the working directory when run from the
 * project, or else the folder the game's jar is in (as in the packaged game, wherever it's
 * installed and whatever folder it's started from). Ends with a separator, or is empty.
 */
public final class GamePaths {

    public static final String HOME = findHome();

    private GamePaths() {
    }

    private static String findHome() {
        if (new File("assets").isDirectory()) return "";
        try {
            File code = new File(GamePaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File dir = code.isFile() ? code.getParentFile() : code;
            for (int up = 0; up < 3 && dir != null; up++, dir = dir.getParentFile()) {
                if (new File(dir, "assets").isDirectory()) return dir.getAbsolutePath() + File.separator;
            }
        } catch (Exception e) {
            // Falls back to the working directory
        }
        return "";
    }
}
