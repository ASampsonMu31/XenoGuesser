/**
 * Unit-sized building geometry. Everything spans x and z in [-0.5, 0.5] and
 * y in [0, 1]; InfrastructureObject scales it to each building's dimensions.
 * Pitched roofs run their ridge along local X.
 */
public final class BuildingMeshes {
    private static final float[] WALL_INTERIOR = { 0.0f, 0.5f, 0.0f };
    private static final float[] ROOF_INTERIOR = { 0.0f, 0.25f, 0.0f };

    private BuildingMeshes() {}

    /** The four walls of a building, open at the top and bottom. */
    public static MeshBuilder createWalls() {
        MeshBuilder builder = new MeshBuilder();
        addWalls(builder, WALL_INTERIOR);
        return builder;
    }

    /** A closed box, used for flat roofs and doors. */
    public static MeshBuilder createBox() {
        MeshBuilder builder = new MeshBuilder();
        addWalls(builder, WALL_INTERIOR);
        builder.addConvexFace(WALL_INTERIOR, p(-0.5f, 1, -0.5f), p(0.5f, 1, -0.5f), p(0.5f, 1, 0.5f), p(-0.5f, 1, 0.5f));
        addBase(builder, WALL_INTERIOR);
        return builder;
    }

    public static MeshBuilder createRoof(BuildingStyle style) {
        switch (style.roofType) {
            case GABLE:
                return createGableRoof();
            case HIP:
                return createHipRoof(style.hipRidgeHalfLength());
            case PYRAMID:
                return createPyramidRoof();
            case SHED:
                return createShedRoof();
            case FLAT:
            default:
                return createBox();
        }
    }

    private static MeshBuilder createGableRoof() {
        MeshBuilder builder = new MeshBuilder();
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 0, 0.5f), p(0.5f, 0, 0.5f), p(0.5f, 1, 0), p(-0.5f, 1, 0));
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 0, -0.5f), p(0.5f, 0, -0.5f), p(0.5f, 1, 0), p(-0.5f, 1, 0));
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 0, -0.5f), p(-0.5f, 0, 0.5f), p(-0.5f, 1, 0));
        builder.addConvexFace(ROOF_INTERIOR, p(0.5f, 0, -0.5f), p(0.5f, 0, 0.5f), p(0.5f, 1, 0));
        addBase(builder, ROOF_INTERIOR);
        return builder;
    }

    private static MeshBuilder createHipRoof(float ridgeHalfLength) {
        float r = ridgeHalfLength;
        MeshBuilder builder = new MeshBuilder();
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 0, 0.5f), p(0.5f, 0, 0.5f), p(r, 1, 0), p(-r, 1, 0));
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 0, -0.5f), p(0.5f, 0, -0.5f), p(r, 1, 0), p(-r, 1, 0));
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 0, -0.5f), p(-0.5f, 0, 0.5f), p(-r, 1, 0));
        builder.addConvexFace(ROOF_INTERIOR, p(0.5f, 0, -0.5f), p(0.5f, 0, 0.5f), p(r, 1, 0));
        addBase(builder, ROOF_INTERIOR);
        return builder;
    }

    private static MeshBuilder createPyramidRoof() {
        MeshBuilder builder = new MeshBuilder();
        float[] apex = p(0, 1, 0);
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 0, 0.5f), p(0.5f, 0, 0.5f), apex);
        builder.addConvexFace(ROOF_INTERIOR, p(0.5f, 0, 0.5f), p(0.5f, 0, -0.5f), apex);
        builder.addConvexFace(ROOF_INTERIOR, p(0.5f, 0, -0.5f), p(-0.5f, 0, -0.5f), apex);
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 0, -0.5f), p(-0.5f, 0, 0.5f), apex);
        addBase(builder, ROOF_INTERIOR);
        return builder;
    }

    /** Mono-pitch roof, high at the back (-Z) and sloping down towards the front door. */
    private static MeshBuilder createShedRoof() {
        MeshBuilder builder = new MeshBuilder();
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 1, -0.5f), p(0.5f, 1, -0.5f), p(0.5f, 0, 0.5f), p(-0.5f, 0, 0.5f));
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 0, -0.5f), p(0.5f, 0, -0.5f), p(0.5f, 1, -0.5f), p(-0.5f, 1, -0.5f));
        builder.addConvexFace(ROOF_INTERIOR, p(-0.5f, 0, -0.5f), p(-0.5f, 1, -0.5f), p(-0.5f, 0, 0.5f));
        builder.addConvexFace(ROOF_INTERIOR, p(0.5f, 0, -0.5f), p(0.5f, 1, -0.5f), p(0.5f, 0, 0.5f));
        addBase(builder, ROOF_INTERIOR);
        return builder;
    }

    private static void addWalls(MeshBuilder builder, float[] interior) {
        builder.addConvexFace(interior, p(-0.5f, 0, 0.5f), p(0.5f, 0, 0.5f), p(0.5f, 1, 0.5f), p(-0.5f, 1, 0.5f));
        builder.addConvexFace(interior, p(-0.5f, 0, -0.5f), p(0.5f, 0, -0.5f), p(0.5f, 1, -0.5f), p(-0.5f, 1, -0.5f));
        builder.addConvexFace(interior, p(-0.5f, 0, -0.5f), p(-0.5f, 0, 0.5f), p(-0.5f, 1, 0.5f), p(-0.5f, 1, -0.5f));
        builder.addConvexFace(interior, p(0.5f, 0, -0.5f), p(0.5f, 0, 0.5f), p(0.5f, 1, 0.5f), p(0.5f, 1, -0.5f));
    }

    /** Downward-facing underside, visible beneath roof overhangs. */
    private static void addBase(MeshBuilder builder, float[] interior) {
        builder.addConvexFace(interior, p(-0.5f, 0, -0.5f), p(0.5f, 0, -0.5f), p(0.5f, 0, 0.5f), p(-0.5f, 0, 0.5f));
    }

    private static float[] p(float x, float y, float z) {
        return new float[] { x, y, z };
    }
}
