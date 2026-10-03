/**
 * The library of body parts organisms are assembled from. Each part is modelled in its own
 * space and placed by the builder's current transform:
 * <ul>
 *   <li>body segments are centred on the origin, lying along Z with the front at +Z;</li>
 *   <li>limbs (leg segments, antennae, trunks, horns, mandibles) start at the origin, where
 *       they join, and run along +Z for their length;</li>
 *   <li>feet sit with the ankle at the origin, the sole at y = 0 and the toes towards +Z.</li>
 * </ul>
 * Species generators choose which parts to use, their proportions and how they join.
 */
public final class OrganismParts {

    private OrganismParts() {}

    public enum FootType { CLAW, PAD, HOOK }
    public enum AntennaType { PLAIN, CLUBBED, FEATHERED }

    /**
     * An armoured body segment. Pointiness above 1 narrows the ends to points; below 1
     * squares them off. Ridges are raised bands across the segment, like the plates of a
     * woodlouse; frontTaper below 1 narrows the front end relative to the back.
     */
    public static void bodySegment(OrganismMesh.Builder b, float length, float width, float height,
                                   float pointiness, int ridges, float ridgeDepth, float frontTaper) {
        shell(b, OrganismMesh.PART_BODY, length, width, height, pointiness, ridges, ridgeDepth, frontTaper);
    }

    /** A body segment's shape tagged as any kind of part (packs, boots and the like use trim). */
    public static void shell(OrganismMesh.Builder b, int part, float length, float width, float height,
                             float pointiness, int ridges, float ridgeDepth, float frontTaper) {
        b.part(part);
        b.lathe(18, 16, (t, out) -> {
            float envelope = (float) Math.pow(Math.max(0.0, Math.sin(Math.PI * t)), 0.5 * pointiness);
            float ridge = ridges == 0 ? 1f : 1f - ridgeDepth * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * ridges * t));
            float taper = 1f + (frontTaper - 1f) * t;
            out[0] = 0f;
            out[1] = 0f;
            out[2] = (t - 0.5f) * length;
            out[3] = width * 0.5f * envelope * ridge * taper;
            out[4] = height * 0.5f * envelope * ridge * taper;
        });
    }

    /** A tapered limb section of any kind of part (skin, sleeve, trouser leg), rounded at the joints, along +Z. */
    public static void limb(OrganismMesh.Builder b, int part, float length, float baseRadius, float tipRadius) {
        b.part(part);
        b.lathe(8, 8, (t, out) -> {
            float rounding = (float) Math.pow(Math.max(0.0, 1.0 - Math.pow(2 * t - 1, 8)), 0.5);
            float bulge = 1f + 0.25f * (float) Math.sin(Math.PI * Math.min(1f, t * 1.6f));
            float r = (baseRadius + (tipRadius - baseRadius) * t) * bulge * Math.max(0.35f, rounding);
            out[0] = 0f;
            out[1] = 0f;
            out[2] = t * length;
            out[3] = r;
            out[4] = r;
        });
    }

    /** One section of a leg, rounded at both ends into a ball joint, thicker at the top. Optional spines face down and back. */
    public static void legSegment(OrganismMesh.Builder b, float length, float baseRadius, float tipRadius, int spines) {
        limb(b, OrganismMesh.PART_LEG, length, baseRadius, tipRadius);
        float[] base = b.currentTransform();
        for (int s = 0; s < spines; s++) {
            float along = length * (0.3f + 0.55f * s / Math.max(1, spines - 1));
            float radius = baseRadius + (tipRadius - baseRadius) * along / length;
            // Spines lean back towards the joint below, on the underside
            float[] place = Affine.multiply(Affine.translation(0f, -radius * 0.8f, along), Affine.rotationX(2.3f));
            b.transform(Affine.multiply(base, place));
            cone(b, radius * 2.2f, radius * 0.35f, 0.15f);
        }
        b.transform(base);
    }

    /** A foot: twin claws, a broad pad or a single hook. */
    public static void foot(OrganismMesh.Builder b, FootType type, float size) {
        b.part(OrganismMesh.PART_FOOT);
        float[] base = b.currentTransform();
        switch (type) {
            case PAD -> b.lathe(10, 6, (t, out) -> {
                float envelope = (float) Math.sqrt(Math.max(0.0, Math.sin(Math.PI * t)));
                out[0] = 0f;
                out[1] = size * 0.18f;
                out[2] = (t - 0.3f) * size * 1.4f;
                out[3] = size * 0.45f * envelope;
                out[4] = size * 0.16f * envelope;
            });
            case HOOK -> {
                b.transform(Affine.multiply(base, Affine.rotationX(-0.3f)));
                cone(b, size * 1.3f, size * 0.16f, -0.9f);
            }
            default -> {
                for (int side = -1; side <= 1; side += 2) {
                    b.transform(Affine.multiply(base, Affine.multiply(Affine.rotationY(side * 0.45f), Affine.rotationX(-0.15f))));
                    cone(b, size * 1.0f, size * 0.13f, -0.7f);
                }
            }
        }
        b.transform(base);
    }

    /** A feeler, curving gently upwards, plain, ending in a club, or fringed with side bristles. */
    public static void antenna(OrganismMesh.Builder b, AntennaType type, float length, float radius) {
        b.part(OrganismMesh.PART_ANTENNA);
        b.lathe(6, 12, (t, out) -> {
            out[0] = 0f;
            out[1] = length * 0.18f * t * t;
            out[2] = length * t;
            float r = radius * (1f - 0.6f * t) * (float) Math.pow(Math.max(0.0, 1.0 - Math.pow(t, 12)), 0.5);
            out[3] = Math.max(radius * 0.08f, r);
            out[4] = out[3];
        });
        float[] base = b.currentTransform();
        float tipY = length * 0.18f;
        if (type == AntennaType.CLUBBED) {
            b.transform(Affine.multiply(base, Affine.translation(0f, tipY, length)));
            ellipsoid(b, radius * 2.6f, radius * 2.2f, radius * 4.0f);
        } else if (type == AntennaType.FEATHERED) {
            int bristles = 7;
            for (int i = 0; i < bristles; i++) {
                float t = 0.35f + 0.6f * i / (bristles - 1);
                for (int side = -1; side <= 1; side += 2) {
                    float[] place = Affine.multiply(Affine.translation(0f, length * 0.18f * t * t, length * t),
                            Affine.rotationY(side * 1.1f));
                    b.transform(Affine.multiply(base, place));
                    cone(b, length * 0.22f * (1.1f - t), radius * 0.35f, 0.1f);
                }
            }
        }
        b.transform(base);
    }

    /** A feeding trunk that curls downwards towards its tip. */
    public static void trunk(OrganismMesh.Builder b, float length, float radius, float curl) {
        b.part(OrganismMesh.PART_TRUNK);
        b.lathe(10, 16, (t, out) -> {
            double angle = curl * t * t * 2.2;
            out[0] = 0f;
            out[1] = (float) (-length * 0.6 * (1 - Math.cos(angle)) / Math.max(0.2, curl * 2.2) * 1.4);
            out[2] = length * t * (1f - 0.25f * curl * t);
            float r = radius * (1f - 0.6f * t);
            float rounding = (float) Math.sqrt(Math.max(0.0, 1.0 - Math.pow(t, 10)));
            out[3] = Math.max(0.0001f, r * Math.max(0.3f, rounding));
            out[4] = out[3];
        });
    }

    /** A compound eye. */
    public static void eye(OrganismMesh.Builder b, float radius) {
        b.part(OrganismMesh.PART_EYE);
        ellipsoid(b, radius, radius, radius * 0.9f);
    }

    /** A horn sweeping upwards from its base. */
    public static void horn(OrganismMesh.Builder b, float length, float radius) {
        b.part(OrganismMesh.PART_HORN);
        cone(b, length, radius, 0.5f);
    }

    /** One half of a pair of pincers, curving in towards the other. */
    public static void mandible(OrganismMesh.Builder b, float length, float radius) {
        b.part(OrganismMesh.PART_HORN);
        b.lathe(6, 10, (t, out) -> {
            double angle = t * 1.4;
            out[0] = 0f;
            out[1] = 0f;
            out[2] = (float) (length * Math.sin(angle) / 1.4 * 1.15);
            out[3] = radius * (1f - 0.85f * t);
            out[4] = radius * 0.6f * (1f - 0.85f * t);
        });
    }

    /**
     * A wing section: a thin membrane spanning out along +Z from rootChord wide to tipChord,
     * its chord along X; an outer section rounds off at the tip.
     */
    public static void wing(OrganismMesh.Builder b, float span, float rootChord, float tipChord, boolean roundTip) {
        b.part(OrganismMesh.PART_BODY);
        b.lathe(10, 10, (t, out) -> {
            float chord = rootChord + (tipChord - rootChord) * t;
            if (roundTip) chord *= (float) Math.sqrt(Math.max(0.0, 1.0 - Math.pow(t, 4)));
            out[0] = 0f;
            out[1] = 0f;
            out[2] = span * t;
            out[3] = Math.max(0.001f, chord * 0.5f);
            out[4] = Math.max(0.001f, rootChord * 0.04f);
        });
    }

    /** A tapering spike along +Z that bends up (bend > 0) or down towards its point. */
    private static void cone(OrganismMesh.Builder b, float length, float radius, float bend) {
        b.lathe(6, 6, (t, out) -> {
            out[0] = 0f;
            out[1] = bend * length * 0.3f * t * t;
            out[2] = length * t;
            out[3] = Math.max(0.0001f, radius * (1f - t));
            out[4] = out[3];
        });
    }

    private static void ellipsoid(OrganismMesh.Builder b, float rx, float ry, float rz) {
        b.lathe(10, 8, (t, out) -> {
            float envelope = (float) Math.sin(Math.PI * t);
            out[0] = 0f;
            out[1] = 0f;
            out[2] = (t - 0.5f) * 2f * rz;
            out[3] = rx * envelope;
            out[4] = ry * envelope;
        });
    }
}
