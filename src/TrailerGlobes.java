import java.io.File;
import java.util.Random;

/**
 * Developer aid for the trailer (a main class, not part of the game): the planet as the landing
 * pod sees it (PlanetGlobeTexture) for each of several seeds, made as XenoGuesser.generateWorld
 * makes it, written with its name and seed into <out dir>/globes.js for the
 * trailer page, which spins them.
 *
 * Usage: java ... TrailerGlobes <out dir> seed seed ...
 */
public final class TrailerGlobes {

    private TrailerGlobes() {
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        out.mkdirs();
        RunFiles.prepare();
        StringBuilder names = new StringBuilder("// Made by TrailerGlobes: each planet's seed, name and picture (as the landing pod sees it)\nconst GLOBES = [\n");
        for (int i = 1; i < args.length; i++) {
            long seed = Long.parseLong(args[i]);
            PerlinNoise noise = new PerlinNoise(seed);
            Planet.setWidth(150_000f * (0.92f + 0.16f * new Random(seed * 104_729L + 17L).nextFloat()));
            float sea = XenoGuesser_GLEventListener.precalculateSeaLevel(seed, noise);
            // The land shaped for the natural landmarks, before anything else is worked out from it
            noise.landforms = Landforms.shape(noise, seed, sea);
            // (the rivers cut its coasts, as in the game)
            RegionalGenerationManager climate = new RegionalGenerationManager(seed, sea);
            climate.precalculateWaterDistanceField(noise);
            climate.setMoistureMap(climate.createWaterPreference(0f, 8000f));
            noise.rivers = Rivers.generate(noise, sea, climate.rainfallMap::evaluate);
            java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(PlanetGlobeTexture.make(noise, sea, seed), "png", png);
            String name = PlanetName.forSeed(seed).spelling;
            // (the picture inside the script itself, so the page can read its pixels even opened from disk)
            names.append("  { seed: ").append(seed).append(", name: \"").append(name).append("\", picture: \"data:image/png;base64,")
                    .append(java.util.Base64.getEncoder().encodeToString(png.toByteArray())).append("\" },\n");
            System.out.println("[GLOBES] " + seed + ": " + name);
        }
        names.append("];\n");
        java.nio.file.Files.writeString(new File(out, "globes.js").toPath(), names);
        System.exit(0);
    }
}
