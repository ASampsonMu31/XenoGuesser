import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import com.xenoguesser.math.*;


/**
 * The world's plant species: each one generated from the seed (its growth, colours, form and
 * markings), where it grows (its climate, home region and abundance, and its range: see
 * SpeciesRange), and how many grow in a place.
 */
final class FloraSpecies extends GamePart {

    FloraSpecies(XenoGuesser_GLEventListener game) {
        super(game);
    }

    /**
     * The plant species of this world (four trees, four shrubs): each one's growth, colours,
     * form and markings, and where it grows (its climate, its home region and its abundance).
     */
    void createFloraSpecies() {
        // --- SEEDED DYNAMIC SPECIES GENERATION PIPELINE ---
        game.speciesConfigs = new SpeciesConfig[game.NUM_SPECIES];
        
        // Create a Random instance tied strictly to this world's seed
        // This guarantees variety per game, but perfect uniformity on the same seed
        Random speciesSeeder = new Random(game.worldSeed);
        // Each species' form, from a stream of its own (so the rest of each species is as it was):
        // the trees all different forms, and the shrubs
        Flora.Design.Form[][] forms = plantForms(new Random(game.worldSeed * 0x9E3779B97F4A7C15L + 77L));

        for (int s = 0; s < game.NUM_SPECIES; s++) {
            boolean isTree = (s < 4); // First 4 are Trees, last 4 are Shrubs
            String name = isTree ? ("Procedural Tree Type " + (s + 1)) : ("Procedural Shrub Type " + (s - 3));

            // Every species gets its own generated leaf texture
            int leafTexNum = s;
            
            // Randomize organic profiles depending on structural class (Tree vs Shrub)
            float leafScale, baseBRate, varBRate, baseSWidth, varSWidth, baseWDecl, varWDecl, baseSDist, varSDist, baseBAngle, varBAngle;
            float tempMean, tempStdDev, patchNoiseScale, baseAbundance;
            Vector3 healthyColor, dyingColor;

            if (isTree) {
                // Trees: Massive leaves, low branching density, thick trunks, tall structural heights
                leafScale   = 2.2f + speciesSeeder.nextFloat() * 1.5f; // Scale ranges from 2.2 to 3.7
                baseBRate   = 0.05f + speciesSeeder.nextFloat() * 0.08f; // Plenty of middling branches
                varBRate    = 0.01f + speciesSeeder.nextFloat() * 0.02f;
                baseSWidth  = 2.0f + speciesSeeder.nextFloat() * 2.5f;
                varSWidth   = 0.3f + speciesSeeder.nextFloat() * 0.4f;
                baseWDecl   = 0.01f + speciesSeeder.nextFloat() * 0.04f;
                varWDecl    = 0.005f + speciesSeeder.nextFloat() * 0.01f;
                baseSDist   = 80.0f + speciesSeeder.nextFloat() * 90.0f; // Tall
                varSDist    = 10.0f + speciesSeeder.nextFloat() * 15.0f;
                baseBAngle  = 30.0f + speciesSeeder.nextFloat() * 40.0f; // Splay: from fairly upright (30) to nearly level (70), by species
                varBAngle   = 5.0f + speciesSeeder.nextFloat() * 10.0f;
                
                // Ecological layout: Random temperature preferences across the map
                tempMean    = 0.1f + speciesSeeder.nextFloat() * 0.6f; 
                tempStdDev  = 0.2f + speciesSeeder.nextFloat() * 0.2f;
                baseAbundance = 0.02f + speciesSeeder.nextFloat() * 0.08f;


            } else {
                // Shrubs: Short, thin start, dense brush branching rates, ultra wide extension angles
                leafScale   = 0.7f + speciesSeeder.nextFloat() * 0.4f; // Shrub leaf sizes (0.7 to 1.1)
                baseBRate   = 0.2f + speciesSeeder.nextFloat() * 0.15f;
                varBRate    = 0.05f + speciesSeeder.nextFloat() * 0.08f;
                baseSWidth  = 0.3f + speciesSeeder.nextFloat() * 0.4f;
                varSWidth   = 0.1f + speciesSeeder.nextFloat() * 0.2f;
                baseWDecl   = 0.15f + speciesSeeder.nextFloat() * 0.2f;
                varWDecl    = 0.05f + speciesSeeder.nextFloat() * 0.1f;
                baseSDist   = 5.0f + speciesSeeder.nextFloat() * 12.0f; // Short
                varSDist    = 2.0f + speciesSeeder.nextFloat() * 6.0f;
                baseBAngle  = 30.0f + speciesSeeder.nextFloat() * 30.0f; // Wide bushy dispersion
                varBAngle   = 10.0f + speciesSeeder.nextFloat() * 20.0f;
                
                // Ecological layout: Prefer warmer/different niches than trees
                tempMean    = 0.4f + speciesSeeder.nextFloat() * 0.5f;
                tempStdDev  = 0.15f + speciesSeeder.nextFloat() * 0.2f;
                baseAbundance = (0.04f + speciesSeeder.nextFloat() * 0.16f) * 2.5f; // (common: 2.5 times what they were)
            }

            // --- GENERATE PURE GREYSCALE WOOD LUMINANCE (0.15 = Charcoal, 0.95 = Ghost White) ---
            float trunkLuminance = 0.15f + speciesSeeder.nextFloat() * 0.80f;
            
            // Set all channels equal to create a clean greyscale intensity factor
            Vector3 trunkColor = new Vector3(trunkLuminance, trunkLuminance, trunkLuminance);

            patchNoiseScale = 1e-5f;

            // --- 1. GENERATE ANY BRIGHT HEALTHY COLOR (Per-Species) ---
            float rawR = speciesSeeder.nextFloat();
            float rawG = speciesSeeder.nextFloat();
            float rawB = speciesSeeder.nextFloat();

            // Find the strongest channel to scale the color up and guarantee brightness/vibrancy
            float maxChannel = Math.max(rawR, Math.max(rawG, rawB));
            if (maxChannel == 0.0f) { // Edge case fallback to prevent division by zero
                rawR = 1.0f; rawG = 1.0f; rawB = 1.0f; 
                maxChannel = 1.0f;
            }

            // Normalize so the brightest channel hits full capacity (1.0f), making it pop
            float brightR = rawR / maxChannel;
            float brightG = rawG / maxChannel;
            float brightB = rawB / maxChannel;

            // Add a slight minimum floor so it never goes pure black or pure neon white unexpectedly
            healthyColor = new Vector3(
                Math.max(0.15f, brightR),
                Math.max(0.15f, brightG),
                Math.max(0.15f, brightB)
            );

            // --- 2. GENERATE A BROWNER, DEGRADED VERSION OF THAT EXACT COLOR ---
            // To simulate organic decay/browning, we mix the base color with a dark brown/grey tint.
            // This preserves the unique base species hue while pulling it into a muddy, dead state.
            float brownFactor = 0.35f; // Controls how much of the original hue passes through
            float deadR = (healthyColor.x * brownFactor) + 0.15f; 
            float deadG = (healthyColor.y * brownFactor) + 0.10f; // Lower green reduces vitality
            float deadB = (healthyColor.z * brownFactor) + 0.04f; // Lower blue pushes it toward warm mud/earth tones

            dyingColor = new Vector3(
                Math.min(deadR, 1.0f),
                Math.min(deadG, 1.0f),
                Math.min(deadB, 1.0f)
            );

            // What it looks like: its form and what grows on it (the branching forms keep the
            // branching above; every other is as wide at its foot as its design's stem)
            Random looks = new Random(game.worldSeed * 7919L + s * 104729L);
            Flora.Design design = Flora.Design.of(forms[isTree ? 0 : 1][isTree ? s : s - 4], !isTree, looks);
            design.leafScale = leafScale;
            if (design.form != Flora.Design.Form.TREE && design.form != Flora.Design.Form.WEEPING) {
                baseSWidth = design.width;
                varSWidth = 0f;
            }

            // Construct the runtime configuration profile
            game.speciesConfigs[s] = new SpeciesConfig(
                name, leafTexNum, leafScale,
                baseBRate, varBRate, baseSWidth, varSWidth,
                baseWDecl, varWDecl, baseSDist, varSDist,
                baseBAngle, varBAngle, healthyColor, dyingColor, trunkColor,
                tempMean, tempStdDev, patchNoiseScale, baseAbundance
            );
            game.speciesConfigs[s].design = design;
            plantMarkings(game.speciesConfigs[s], looks);
            if (Dev.ENABLED) System.out.println("[FLORA] Plant " + s + ": " + design.kind() + (design.leaves ? ", leaves" : "")
                    + (design.spikes > 0f ? ", spikes" : "") + (design.orbs > 0f ? ", orbs" : "") + (design.strands > 0 ? ", strands" : "")
                    + (design.skin ? ", skin" : "") + ", pattern " + (int) game.speciesConfigs[s].pattern);

            // Compile Ecosystem Pipeline Rules for this dynamic configuration
            SpeciesConfig sc = game.speciesConfigs[s];
            // Some shrubs cope with the heat better than the cold (from a Random of their own, so the rest is as it was)
            if (!isTree) sc.tempHotStretch = OrganismSpecies.heatHardiness(new Random(game.worldSeed * 3301L + s));
            sc.tempFactor = game.regionalManager.createTemperaturePreference(sc.tempMean, sc.tempStdDev, sc.tempHotStretch);
            sc.patchNoiseFactor = game.regionalManager.createNoiseMap(sc.patchNoiseScale);
            // ...and only in its home region, as the animals (see OrganismSpecies): a plant suited
            // to the cold north needn't grow in the cold south too
            RegionalFactor home = game.regionalManager.createNoiseMap(OrganismSpecies.HOME_SCALE_LOW
                    + new Random(game.worldSeed * 613L + s).nextFloat() * OrganismSpecies.HOME_SCALE_RANGE);
            sc.abundanceFactor = plantAbundance(sc, home, OrganismSpecies.homeStrength(new Random(game.worldSeed * 1993L + s)));
        }
    }

    /**
     * The plants' forms: {the four trees', the four shrubs'}, each four different forms drawn
     * from r (so a world might have a giant mushroom, a palm, a cactus and an orb tree).
     */
    private static Flora.Design.Form[][] plantForms(Random r) {
        Flora.Design.Form[][] forms = new Flora.Design.Form[2][];
        for (int k = 0; k < 2; k++) {
            List<Flora.Design.Form> all = new ArrayList<>(List.of(Flora.Design.Form.values()));
            java.util.Collections.shuffle(all, r);
            forms[k] = all.subList(0, 4).toArray(new Flora.Design.Form[0]);
        }
        return forms;
    }

    /**
     * A species' body colour (anything from a cactus green to a fungal violet), and its markings:
     * spots, stripes, pits or rings (or none) in a second colour, drawn from r.
     */
    private static void plantMarkings(SpeciesConfig sc, Random r) {
        float hue = r.nextFloat(), saturation = 0.3f + r.nextFloat() * 0.5f, brightness = 0.45f + r.nextFloat() * 0.45f;
        sc.bodyColour = hsb(hue, saturation, brightness);
        // (mostly a contrasting hue; sometimes the body's own, much paler or darker)
        if (r.nextFloat() < 0.6f) {
            sc.patternColour = hsb(hue + 0.35f + r.nextFloat() * 0.3f, 0.5f + r.nextFloat() * 0.4f, 0.6f + r.nextFloat() * 0.4f);
        } else {
            sc.patternColour = hsb(hue, saturation * 0.5f, r.nextBoolean() ? Math.min(1f, brightness * 1.7f) : brightness * 0.4f);
        }
        // Which markings, the likelier ones by form (a mushroom's spots, a cactus' stripes down its ribs, a palm's rings)
        float[] odds = switch (sc.design.form) {
            case MUSHROOM -> new float[] { 0.15f, 0.55f, 0.1f, 0.15f, 0.05f };
            case CACTUS -> new float[] { 0.2f, 0.15f, 0.35f, 0.25f, 0.05f };
            case PALM -> new float[] { 0.2f, 0.1f, 0.1f, 0.1f, 0.5f };
            default -> new float[] { 0.25f, 0.25f, 0.2f, 0.2f, 0.1f };
        };
        float pick = r.nextFloat();
        int pattern = 0;
        while (pattern < odds.length - 1 && pick >= odds[pattern]) pick -= odds[pattern++];
        sc.pattern = pattern;
        sc.patternScale = 5f + r.nextInt(8);
    }

    private static Vector3 hsb(float hue, float saturation, float brightness) {
        int rgb = java.awt.Color.HSBtoRGB(hue, Math.min(1f, saturation), Math.min(1f, brightness));
        return new Vector3(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f);
    }

    // Each plant species' range: where it's common, and how many there are along it (SpeciesRange)
    SpeciesRange[] floraRanges;

    // The most of one tree or shrub species in a chunk's area, even in its heartland
    private static final float FLORA_PEAK_CAP = 2.0f;

    // How many times its peak a plant stands in its famous grove (see Landmarks)
    static final float GROVE_THICKNESS = 3.0f;

    /** A plant species' abundance before its range is applied, per chunk's area. */
    float floraRaw(int species, GeodesicChunkGrid.Direction d) {
        SpeciesConfig sc = game.speciesConfigs[species];
        return sc.abundanceFactor.evaluate(d) * sc.baseAbundance;
    }

    /**
     * A plant species' abundance over the world: suited to its climate, the ground's moisture and
     * its own patches (a steep blend, so species keep apart), in its home region.
     */
    private RegionalFactor plantAbundance(SpeciesConfig sc, RegionalFactor home, float homeStrength) {
        // Blended factor with prominent 50% unique noise allocation for explicit segregation
        RegionalFactor suited = new RegionalFactor.Builder()
            .setWeight(6.0f)        // Drop this to 1.0f so it NEVER artificially blows up low scores
            .setPowerCurve(6.0f)    // Crank the power curve up to 5.0 or 6.0 to make it brutal
            .addFactor(sc.tempFactor, 0.30f)
            .addFactor(game.grassMoistureFactor, 0.20f)
            .addFactor(sc.patchNoiseFactor, 0.50f)
            .build();
        return new RegionalFactor(1.0f, d -> {
            float t = Math.max(0f, Math.min(1f, (home.evaluate(d) - OrganismSpecies.HOME_FROM)
                    / (OrganismSpecies.HOME_TO - OrganismSpecies.HOME_FROM)));
            return suited.evaluate(d) * (1f - homeStrength + homeStrength * t * t * (3f - 2f * t));
        });
    }

    /**
     * Draws a plant species' range again (the temperature it likes, its patches and its home
     * region), the attempt'th time, for one that came out with almost nowhere to grow (see
     * SpeciesRange.findable); its look stays. From the world's seed alone, the same on every PC,
     * and leaving every other map as it was.
     */
    private void redrawPlantRange(int s, int attempt) {
        SpeciesConfig sc = game.speciesConfigs[s];
        Random r = new Random(game.worldSeed * 4241L + s * 7717L + attempt * 1_000_003L);
        boolean tree = s < 4;
        sc.tempMean = tree ? 0.1f + r.nextFloat() * 0.6f : 0.4f + r.nextFloat() * 0.5f;
        // (a little more tolerant each time, so a fussy species finds somewhere)
        sc.tempStdDev = (tree ? 0.2f + r.nextFloat() * 0.2f : 0.15f + r.nextFloat() * 0.2f) + attempt * 0.01f;
        long salt = (game.worldSeed * 17L + s) * 64L + attempt;
        sc.tempFactor = game.regionalManager.createTemperaturePreference(sc.tempMean, sc.tempStdDev, sc.tempHotStretch);
        sc.patchNoiseFactor = game.regionalManager.createNoiseMap(sc.patchNoiseScale, salt * 2L);
        RegionalFactor home = game.regionalManager.createNoiseMap(OrganismSpecies.HOME_SCALE_LOW + r.nextFloat() * OrganismSpecies.HOME_SCALE_RANGE, salt * 2L + 1L);
        sc.abundanceFactor = plantAbundance(sc, home, OrganismSpecies.homeStrength(r));
    }

    SpeciesRange[] floraRanges(List<GeodesicChunkGrid.Direction> land) {
        SpeciesRange[] ranges = new SpeciesRange[game.NUM_SPECIES];
        int[] attempts = new int[game.NUM_SPECIES];
        // (any with almost nowhere to grow has its range drawn again until it has somewhere)
        java.util.stream.IntStream.range(0, game.NUM_SPECIES).parallel().forEach(s -> {
            while (true) {
                float[] raw = new float[land.size()];
                for (int i = 0; i < raw.length; i++) raw[i] = floraRaw(s, land.get(i));
                ranges[s] = SpeciesRange.of(raw, FLORA_PEAK_CAP);
                if (attempts[s] >= SpeciesRange.MOST_REDRAWS || ranges[s].findable(raw)) break;
                redrawPlantRange(s, ++attempts[s]);
            }
        });
        for (int s = 0; s < game.NUM_SPECIES; s++) {
            if (attempts[s] > 0) System.out.printf("[FLORA] %s had almost nowhere to grow: its range drawn again %d time(s)%n", game.speciesConfigs[s].name, attempts[s]);
            System.out.printf(java.util.Locale.ROOT, "[FLORA] %s: heartland from %.4f, peak %.3f a chunk%n",
                    game.speciesConfigs[s].name, ranges[s].high, ranges[s].peak);
        }
        return ranges;
    }

    /** A geodesic cell's area in chunks' areas (a chunk being PHYSICAL_CHUNK_SIZE square). */
    float areaInChunks(GeodesicChunkGrid.Cell cell) {
        return (float) (cell.area() * Planet.radius() * Planet.radius() / (game.PHYSICAL_CHUNK_SIZE * game.PHYSICAL_CHUNK_SIZE));
    }
}
