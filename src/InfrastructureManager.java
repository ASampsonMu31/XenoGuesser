import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import gmaths.Vec3;

public class InfrastructureManager {
    
    private final Map<Integer, Double> nationSignProbabilities;
    private final NationGenerationManager nationManager;
    private final XenoGuesser_GLEventListener mainListener;
    
    public InfrastructureManager(long seed, int numNations, NationGenerationManager nationManager, XenoGuesser_GLEventListener mainListener) {
        this.nationManager = nationManager;
        this.mainListener = mainListener;
        this.nationSignProbabilities = new HashMap<>();
        
        Random rand = new Random(seed + 5555L); 
        
        for (int i = 1; i <= numNations; i++) {
            double prob = 0.005 + (rand.nextDouble() * 0.025);
            nationSignProbabilities.put(i, prob);
        }
    }

    public List<InfrastructureObject> generateForChunk(int cx, int cz, float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        List<InfrastructureObject> objects = new ArrayList<>();
        
        float startX = cx * chunkSize;
        float startZ = cz * chunkSize;
        
        Random chunkRand = new Random((long)cx * 8912L + (long)cz * 4123L);
        int attemptsPerChunk = 12;
        
        for (int i = 0; i < attemptsPerChunk; i++) {
            float worldX = startX + (chunkRand.nextFloat() * chunkSize);
            float worldZ = startZ + (chunkRand.nextFloat() * chunkSize);
            
            float worldY = TerrainMesh.getLayeredHeight(worldX, worldZ, noise);
            
            if (worldY > seaLevelHeight + 0.5f) {

                int nationId = nationManager.getNationAtWorld(worldX, worldZ, totalRegionWidth);

                if (nationId != 0 && nationSignProbabilities.containsKey(nationId)) {
                    if (chunkRand.nextDouble() < nationSignProbabilities.get(nationId)) {
                        float randomRotY = chunkRand.nextFloat() * 360.0f;
                        
                        // --- UPDATED STRING LENGTH GENERATION ---
                        // Generate longer text blocks (e.g., 80 to 200 characters)
                        // Make sure this doesn't exceed your shader's uniform array size!
                        int minLen = 80;
                        int maxLen = 200;
                        int stringLength = minLen + chunkRand.nextInt(maxLen - minLen + 1);
                        
                        int[] textString = new int[stringLength];
                        
                        int maxGlyphs = mainListener.getNationAtlasSize(nationId); 
                        if (maxGlyphs <= 0) maxGlyphs = 1; 
                        
                        // --- NATURAL WORD SPACING LOGIC ---
                        int wordLengthCounter = 0;
                        int targetWordLength = 3 + chunkRand.nextInt(6); // Words of length 3-8
                        
                        for (int c = 0; c < stringLength; c++) {
                            if (wordLengthCounter >= targetWordLength) {
                                // Insert a space (index 0) to separate words
                                textString[c] = 0; 
                                wordLengthCounter = 0;
                                targetWordLength = 3 + chunkRand.nextInt(6);
                            } else {
                                // Pick a random glyph (1 to maxGlyphs - 1)
                                textString[c] = 1 + chunkRand.nextInt(maxGlyphs);
                                wordLengthCounter++;
                            }
                        }
                        
                        objects.add(new InfrastructureObject(InfrastructureObject.Type.SIGN, new Vec3(worldX, worldY, worldZ), nationId, randomRotY, textString));
                    }
                }
            }
        }
        return objects;
    }
}