import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import com.jogamp.opengl.util.texture.Texture;
import com.xenoguesser.math.Vector3;

/**
 * The materials every batch part is drawn in, made once per nation and part name and shared
 * (see NationBatch.build): textured walls, roofs and fences, painted lines, metalwork, glass,
 * and a landmark's or an architecture's own colours.
 */
final class InfraMaterials extends InfraPart {

    InfraMaterials(InfrastructureManager infra) {
        super(infra);
    }

    private static final Material ASPHALT = new Material(
        new Vector3(0.18f, 0.18f, 0.18f),
        new Vector3(0.28f, 0.28f, 0.28f),
        new Vector3(0.01f, 0.01f, 0.01f),
        1.0f
    );

    private final Map<String, Material> materials = new HashMap<>();

        /**
     * A white material carrying the named texture (which holds the colour itself), or a
     * plain coloured one if the texture isn't available.
     */
    private Material texturedOr(String textureName, Vector3 colour, Vector3 specular, float shininess) {
        Texture texture = infra.nationTextures.apply(textureName);
        if (texture == null) {
            return new Material(colour, colour, specular, shininess);
        }
        Vector3 white = new Vector3(1.0f, 1.0f, 1.0f);
        Material material = new Material(white, white, specular, shininess);
        material.setDiffuseMap(texture);
        return material;
    }

    /** Materials are created once per nation and part, and shared by every batch. */
    Material material(String part, int nationId) {
        if (part.equals("asphalt")) {
            return ASPHALT;
        }
        return materials.computeIfAbsent(part + "_" + nationId, ignored -> {
                        Vector3 specular = new Vector3(0.03f, 0.03f, 0.03f);
            BuildingStyle building = infra.buildingStyles.get(nationId);
            if (part.startsWith(BorderCrossings.BOOTH_PART)) {
                int v = Integer.parseInt(part.substring(BorderCrossings.BOOTH_PART.length()));
                return texturedOr(InfrastructureManager.wallVariantTextureName(v), infra.wallVariants.get(v).colour, specular, 2.0f);
            }
            if (part.startsWith("wall_variant")) {
                int v = Integer.parseInt(part.substring("wall_variant".length()));
                return texturedOr(InfrastructureManager.wallVariantTextureName(v), infra.wallVariants.get(v).colour, specular, 2.0f);
            }
            // A landmark's colours ("landmark:which:slot")
            if (part.startsWith(LANDMARK_PART)) {
                String[] which = part.substring(LANDMARK_PART.length()).split(":");
                Landmark landmark = infra.landmarks.get(Integer.parseInt(which[0]));
                int slot = Integer.parseInt(which[1]);
                Vector3 colour = landmark.colours[slot];
                float shine = landmark.shine[slot];
                if (slot == Landmark.GLASS) {
                    return new Material(colour, colour, new Vector3(0.7f, 0.7f, 0.7f), shine);
                }
                Vector3 glint = shine > 30f ? new Vector3(0.6f, 0.58f, 0.5f) : specular;
                return new Material(colour, colour, glint, shine);
            }
            // An architecture's ornaments, spikes and neon, by which of the world's designs ("part@design")
            int at = part.indexOf('@');
            if (at > 0) {
                ArchitectureStyle arch = infra.architectureWorld.style(Integer.parseInt(part.substring(at + 1)));
                return switch (part.substring(0, at)) {
                    case "orn" -> {
                        float shine = arch.ornamentShine > 30f ? 0.6f : 0.2f;
                        yield new Material(arch.ornamentColour, arch.ornamentColour, new Vector3(shine, shine, shine), arch.ornamentShine);
                    }
                    case "spike" -> new Material(arch.spikeColour, arch.spikeColour, new Vector3(0.4f, 0.4f, 0.4f), 32.0f);
                    default -> {
                        // Neon: lit by itself, bright by day and glowing at night
                        Vector3 colour = part.startsWith("neonb") ? arch.neonB : arch.neonA;
                        Vector3 dim = Vector3.multiply(colour, 0.35f);
                        yield new Material(dim, dim, new Vector3(0.3f, 0.3f, 0.3f), Vector3.multiply(colour, 0.95f), 16.0f);
                    }
                };
            }
            GuardRailStyle rail = infra.guardRailStyles.get(nationId);
            // A bollard's or a pole's parts, by which of the world's designs it is ("part#design")
            int hash = part.indexOf('#');
            if (hash > 0) {
                int design = Integer.parseInt(part.substring(hash + 1));
                boolean isBollard = part.startsWith("bollard") || part.startsWith("reflector");
                BollardStyle bollard = isBollard ? infra.bollardWorld.design(nationId, design) : null;
                UtilityPoleStyle pole = isBollard ? null : infra.poleWorld.design(nationId, design);
                Vector3 shine = new Vector3(0.8f, 0.8f, 0.8f), dull = new Vector3(0.15f, 0.15f, 0.15f);
                return switch (part.substring(0, hash)) {
                    case "bollard" -> new Material(bollard.bodyColour(), bollard.bodyColour(), dull, 12.0f);
                    case "bollardband" -> new Material(bollard.bandColour(), bollard.bandColour(), dull, 12.0f);
                    // (reflectors: bright even in shade, and shining)
                    case "reflectora" -> new Material(bollard.colourA, bollard.colourA, shine, 48.0f);
                    case "reflectorb" -> new Material(bollard.colourB, bollard.colourB, shine, 48.0f);
                    case "pole" -> new Material(pole.colour, pole.colour, new Vector3(0.08f, 0.08f, 0.08f), 6.0f);
                    case "polearm" -> new Material(pole.armColour, pole.armColour, new Vector3(0.12f, 0.12f, 0.12f), 8.0f);
                    default -> new Material(pole.insulatorColour, pole.insulatorColour, new Vector3(0.6f, 0.6f, 0.6f), 40.0f);
                };
            }
            switch (part) {
                case "line": {
                    Vector3 colour = infra.roadLineStyles.get(nationId).colour;
                    return new Material(Vector3.multiply(colour, 0.9f), colour, new Vector3(0.05f, 0.05f, 0.05f), 8.0f);
                }
                case "rail":
                    return new Material(rail.railColour, rail.railColour, new Vector3(0.35f, 0.35f, 0.35f), 24.0f);
                case "band":
                    return new Material(rail.bandColour, rail.bandColour, new Vector3(0.35f, 0.35f, 0.35f), 24.0f);
                case "post":
                    return new Material(rail.postColour, rail.postColour, specular, 4.0f);
                case "wire": {
                    Vector3 wire = new Vector3(0.07f, 0.07f, 0.08f);
                    return new Material(wire, wire, new Vector3(0.2f, 0.2f, 0.2f), 16.0f);
                }
                                                case "wall":
                    return new Material(building.wallColour, building.wallColour, specular, 2.0f);
                case "roof":
                    return texturedOr(InfrastructureManager.nationTextureName("roof", nationId), building.roofColour, specular, 4.0f);
                case "door":
                    return new Material(building.doorColour, building.doorColour, specular, 2.0f);
                case "glass":
                    return new Material(building.glassColour, building.glassColour, new Vector3(0.6f, 0.6f, 0.6f), 64.0f);
                case "flag":
                    return texturedOr(InfrastructureManager.nationTextureName("flag", nationId), new Vector3(0.8f, 0.8f, 0.8f), new Vector3(0.05f, 0.05f, 0.05f), 4.0f);
                case "goods":
                    return texturedOr(InfrastructureManager.nationTextureName("packaging", nationId), new Vector3(0.8f, 0.6f, 0.4f), new Vector3(0.15f, 0.15f, 0.15f), 12.0f);
                case "shopinterior":
                    // A brightly lit shop seen through the glass
                    return new Material(new Vector3(1.1f, 1.05f, 0.92f), new Vector3(0.95f, 0.92f, 0.82f), new Vector3(0f, 0f, 0f), 2.0f);
                case "shelf":
                    return new Material(new Vector3(0.75f, 0.72f, 0.66f), new Vector3(0.7f, 0.67f, 0.6f), new Vector3(0.1f, 0.1f, 0.1f), 8.0f);
                case "shopwall0": case "shopwall1": case "shopwall2": case "shopwall3": {
                    Vector3 paint = infra.shopColours.get(nationId)[part.charAt(8) - '0'];
                    return new Material(paint, paint, new Vector3(0.08f, 0.08f, 0.08f), 6.0f);
                }
                case "flagpole":
                    return new Material(new Vector3(0.72f, 0.73f, 0.75f), new Vector3(0.72f, 0.73f, 0.75f), new Vector3(0.5f, 0.5f, 0.5f), 32.0f);
                case "interior":
                    return new Material(new Vector3(0.025f, 0.025f, 0.03f), new Vector3(0.03f, 0.03f, 0.035f), new Vector3(0f, 0f, 0f), 2.0f);
                case "curtain0": case "curtain1": case "curtain2": case "curtain3": {
                    int k = part.charAt(7) - '0';
                    return texturedOr(CURTAIN_TEXTURE + k, infra.curtainColour(k), new Vector3(0.02f, 0.02f, 0.02f), 2.0f);
                }
                case "blind0": case "blind1": case "blind2": case "blind3": {
                    // (a tower's upper windows: the same curtains, seen from far below)
                    int k = part.charAt(5) - '0';
                    return texturedOr(CURTAIN_TEXTURE + k, infra.curtainColour(k), new Vector3(0.02f, 0.02f, 0.02f), 2.0f);
                }
                case "frame":
                    return new Material(building.frameColour, building.frameColour, specular, 2.0f);
                case "security": {
                    Vector3 metal = infra.fenceStyles.get(nationId).securityColour;
                    return new Material(metal, metal, new Vector3(0.3f, 0.3f, 0.3f), 24.0f);
                }
                case "dirt":
                    // The ground's own soil texture: the track is the ground, worn bare
                    return texturedOr("dirt_diffuse", new Vector3(0.5f, 0.4f, 0.3f), new Vector3(0.1f, 0.1f, 0.1f), 4.0f);
                case "finery": {
                    Vector3 stone = dressingStone(nationId);
                    return new Material(stone, stone, new Vector3(0.35f, 0.33f, 0.3f), 32.0f);
                }
                case "driveway": {
                    // Paving: a little of the nation's stone in it
                    Random tint = infra.nationRandom(59L, nationId);
                    float grey = 0.36f + tint.nextFloat() * 0.2f;
                    Vector3 paving = new Vector3(grey + tint.nextFloat() * 0.08f, grey + tint.nextFloat() * 0.05f, grey);
                    return new Material(paving, paving, new Vector3(0.05f, 0.05f, 0.05f), 2.0f);
                }
                case "techmetal": {
                    // A tower's brushed alloy fittings
                    Vector3 alloy = new Vector3(0.74f, 0.76f, 0.8f);
                    return new Material(alloy, alloy, new Vector3(0.65f, 0.65f, 0.65f), 48.0f);
                }
                case "wood": {
                    // Weathered timber
                    Vector3 timber = new Vector3(0.42f, 0.31f, 0.2f);
                    return new Material(timber, timber, specular, 2.0f);
                }
                case "steel": {
                    // Painted ironwork, each nation's own colour
                    Vector3[] paints = { new Vector3(0.14f, 0.26f, 0.2f), new Vector3(0.36f, 0.1f, 0.09f), new Vector3(0.12f, 0.15f, 0.28f),
                        new Vector3(0.17f, 0.17f, 0.18f), new Vector3(0.45f, 0.42f, 0.36f) };
                    Vector3 paint = paints[Math.floorMod(nationId * 7 + 3, paints.length)];
                    return new Material(paint, paint, new Vector3(0.3f, 0.3f, 0.3f), 24.0f);
                }
                case "lamp":
                    // A lantern's glass, lit
                    return new Material(new Vector3(1.2f, 1.1f, 0.85f), new Vector3(1.0f, 0.92f, 0.7f), new Vector3(0.4f, 0.4f, 0.4f), 32.0f);
                case "roadwall": {
                    // Weathered concrete
                    Vector3 concrete = new Vector3(0.55f, 0.54f, 0.52f);
                    return new Material(concrete, concrete, specular, 2.0f);
                }
                case "secwall": {
                    // Plain rendered concrete, a little tinted by the nation's metalwork
                    Vector3 metal = infra.fenceStyles.get(nationId).securityColour;
                    Vector3 concrete = new Vector3(0.62f + metal.x * 0.15f, 0.6f + metal.y * 0.15f, 0.57f + metal.z * 0.15f);
                    return new Material(concrete, concrete, specular, 2.0f);
                }
                case "borderfence": {
                    // Galvanised steel
                    Vector3 steel = new Vector3(0.56f, 0.58f, 0.6f);
                    return new Material(steel, steel, new Vector3(0.45f, 0.45f, 0.45f), 30.0f);
                }
                case "barbed": {
                    Vector3 wire = new Vector3(0.32f, 0.31f, 0.3f);
                    return new Material(wire, wire, new Vector3(0.5f, 0.5f, 0.5f), 40.0f);
                }
                case "borderpost": {
                    // Heavy concrete gateposts, with a band of warning paint on them
                    Vector3 concrete = new Vector3(0.6f, 0.59f, 0.56f);
                    return new Material(concrete, concrete, specular, 2.0f);
                }
                case "fence":
                default:
                    return texturedOr(InfrastructureManager.nationTextureName("fence", nationId), infra.fenceStyles.get(nationId).colour, specular, 2.0f);
            }
        });
    }

    /** A nation's dressing stone: marble, cream stone, gilt or dark granite. */
    Vector3 dressingStone(int nationId) {
        Random pick = infra.nationRandom(83L, nationId);
        Vector3[] stones = { new Vector3(0.9f, 0.89f, 0.86f), new Vector3(0.9f, 0.82f, 0.64f),
                new Vector3(0.86f, 0.66f, 0.22f), new Vector3(0.22f, 0.22f, 0.25f) };
        return stones[pick.nextInt(stones.length)];
    }
}
