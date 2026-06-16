import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.NoopTranslator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Paths;
import java.util.Random;

/**
 * A reusable service class for generating procedurally cohesive writing systems
 * using a trained PyTorch CVAE model exported via TorchScript.
 */
public class GlyphGenerator implements AutoCloseable {

    private final ZooModel<NDList, NDList> model;
    private final Predictor<NDList, NDList> predictor;
    private final NDManager manager;
    
    private static final int LATENT_DIM = 32;
    
    // UPDATED: Synchronized with Python script configurations
    private static final float DRAW_THRESHOLD = 0.30f; 

    public GlyphGenerator(String modelPath) throws Exception {
        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(Paths.get(modelPath))
                .optTranslator(new NoopTranslator())
                .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
        this.manager = NDManager.newBaseManager();
    }

    /**
     * Recursively deletes a directory or file to clean out structural cache completely.
     */
    private void deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        directoryToBeDeleted.delete();
    }

    /**
     * Generates multiple distinct writing systems, each with its own internally cohesive
     * structural appearance, determined strictly via cross-platform seeding.
     */
    public void generateAllSystems(int numAlphabetsTotal, String baseOutputDirectory, long worldSeed) throws Exception {
        File baseDir = new File(baseOutputDirectory);
        
        // Wipe old running systems to eliminate leftover cross-seed file noise
        if (baseDir.exists()) {
            deleteDirectory(baseDir);
        }
        baseDir.mkdirs();

        // Initialize the central Random pipeline with the seed
        Random seedRandom = new Random(worldSeed);

        // Determine the number of writing systems deterministically: 2 to 6 inclusive
        int systemCount = 2 + seedRandom.nextInt(5); 

        for (int sysId = 1; sysId <= systemCount; sysId++) {
            // Determine characters for this specific alphabet deterministically: 10 to 40 inclusive
            int charsPerSystem = 10 + seedRandom.nextInt(31);
            
            // Establish systemic output pathing matching: generated_alphabets/alphabet1/
            File systemDir = new File(baseDir, "alphabet" + sysId);
            systemDir.mkdirs();

            // 1. Construct Deterministic Latent Normal Tensors via Seeded Generator
            float[] zAnchorData = new float[LATENT_DIM];
            for (int i = 0; i < LATENT_DIM; i++) {
                zAnchorData[i] = (float) seedRandom.nextGaussian();
            }
            NDArray zAnchor = manager.create(zAnchorData, new Shape(1, LATENT_DIM));
            
            float[] zIndividualData = new float[charsPerSystem * LATENT_DIM];
            for (int i = 0; i < zIndividualData.length; i++) {
                zIndividualData[i] = (float) seedRandom.nextGaussian();
            }
            NDArray zIndividual = manager.create(zIndividualData, new Shape(charsPerSystem, LATENT_DIM));
            
            // UPDATED: Matched Python structural ratios exactly (32% anchor style, 68% mutation character variation)
            NDArray randomZ = zAnchor.mul(0.32f).add(zIndividual.mul(0.68f));

            // 2. Select Style Parent Alphabets deterministically
            long alpha1 = seedRandom.nextInt(numAlphabetsTotal);
            long alpha2 = seedRandom.nextInt(numAlphabetsTotal);
            while (alpha1 == alpha2) {
                alpha2 = seedRandom.nextInt(numAlphabetsTotal);
            }
            float blendRatio = 0.3f + seedRandom.nextFloat() * 0.4f;

            NDArray a1Nd = manager.create(new long[]{alpha1});
            NDArray a2Nd = manager.create(new long[]{alpha2});
            NDArray blendNd = manager.create(new float[]{blendRatio});

            // 3. Compute Inference Pass via LibTorch Engine
            NDList input = new NDList(randomZ, a1Nd, a2Nd, blendNd);
            try (NDList output = this.predictor.predict(input)) {
                NDArray images = (NDArray) output.singletonOrThrow();
                float[] floatArray = images.toFloatArray();

                // 4. Output standardized names (glyph_0.png, glyph_1.png...) into nested folder
                for (int i = 0; i < charsPerSystem; i++) {
                    BufferedImage img = new BufferedImage(28, 28, BufferedImage.TYPE_BYTE_GRAY);
                    
                    for (int y = 0; y < 28; y++) {
                        for (int x = 0; x < 28; x++) {
                            int flatIndex = i * (28 * 28) + y * 28 + x;
                            float pixelVal = floatArray[flatIndex];
                            
                            int color = (pixelVal > DRAW_THRESHOLD) ? 0 : 255;
                            int rgb = (color << 16) | (color << 8) | color;
                            img.setRGB(x, y, rgb);
                        }
                    }
                    
                    String filename = String.format("glyph_%d.png", i);
                    File outFile = new File(systemDir, filename);
                    ImageIO.write(img, "png", outFile);
                }
            }
            
            // Clean individual system tensors out of active VRAM scope
            randomZ.close();
            a1Nd.close();
            a2Nd.close();
            blendNd.close();
        }
    }

    @Override
    public void close() {
        if (predictor != null) predictor.close();
        if (model != null) model.close();
        if (manager != null) manager.close();
    }
}