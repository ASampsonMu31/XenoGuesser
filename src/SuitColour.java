/**
 * The colours a player's suit can be trimmed in (M58): the boots, gloves, pack and helmet collar,
 * their pin and line on the map, and their name on the HUD and the verdicts. Each with the trim
 * as seen first-person and a darker one as lit in the world (the partner's spaceman). Blue and
 * red are the colours the host and joiner always wore before (M6).
 */
public enum SuitColour {
    BLUE("Blue", new float[] { 0.18f, 0.36f, 0.85f }, new float[] { 0.08f, 0.18f, 0.55f }),
    RED("Red", new float[] { 0.82f, 0.16f, 0.14f }, new float[] { 0.55f, 0.07f, 0.06f }),
    GREEN("Green", new float[] { 0.16f, 0.66f, 0.26f }, new float[] { 0.06f, 0.38f, 0.12f }),
    ORANGE("Orange", new float[] { 0.94f, 0.48f, 0.10f }, new float[] { 0.62f, 0.27f, 0.04f }),
    YELLOW("Yellow", new float[] { 0.92f, 0.80f, 0.16f }, new float[] { 0.60f, 0.50f, 0.06f }),
    PURPLE("Purple", new float[] { 0.54f, 0.24f, 0.82f }, new float[] { 0.31f, 0.11f, 0.52f }),
    PINK("Pink", new float[] { 0.94f, 0.40f, 0.68f }, new float[] { 0.62f, 0.19f, 0.41f }),
    TEAL("Teal", new float[] { 0.10f, 0.68f, 0.68f }, new float[] { 0.04f, 0.40f, 0.42f });

    public final String label;
    private final float[] trim, litTrim;

    SuitColour(String label, float[] trim, float[] litTrim) {
        this.label = label;
        this.trim = trim;
        this.litTrim = litTrim;
    }

    /** The trim as seen first-person, and on the map and HUD. */
    public float[] trimColour() {
        return trim.clone();
    }

    /** The trim as lit in the world, on the partner's spaceman. */
    public float[] litTrimColour() {
        return litTrim.clone();
    }

    public java.awt.Color awt() {
        return new java.awt.Color(trim[0], trim[1], trim[2]);
    }

    /** The colour with this ordinal, or blue for one this game doesn't know (sent by a partner, or saved). */
    public static SuitColour of(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : BLUE;
    }

    /** The next colour round, for a partner who chose the same colour as the host. */
    public SuitColour next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
