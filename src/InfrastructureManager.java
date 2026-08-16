import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import gmaths.Vec3;

public class InfrastructureManager {
    
    private final Map<Integer, Double> nationSignProbabilities;
    private final NationGenerationManager nationManager;
    
    public InfrastructureManager(long seed, int numNations, NationGenerationManager nationManager) {
        this.nationManager = nationManager;
        this.nationSignProbabilities = new HashMap<>();
        
        // Use a unique offset so infrastructure generation is deterministic but independent from flora
        Random rand = new Random(seed + 5555L); 
        
        for (int i = 1; i <= numNations; i++) {
            // Assign a random probability between 0.5% and 3.0% per attempt for each specific nation
            double prob = 0.005 + (rand.nextDouble() * 0.025);
            nationSignProbabilities.put(i, prob);
        }
    }

    public List<InfrastructureObject> generateForChunk(int cx, int cz, float chunkSize, float totalRegionWidth, float seaLevelHeight, PerlinNoise noise) {
        List<InfrastructureObject> objects = new ArrayList<>();
        
        float startX = cx * chunkSize;
        float startZ = cz * chunkSize;
        
        Random chunkRand = new Random((long)cx * 8912L + (long)cz * 4123L);
        int attemptsPerChunk = 12; // Adjust this to make signs more/less dense globally
        
        for (int i = 0; i < attemptsPerChunk; i++) {
            float worldX = startX + (chunkRand.nextFloat() * chunkSize);
            float worldZ = startZ + (chunkRand.nextFloat() * chunkSize);
            
            float worldY = TerrainMesh.getLayeredHeight(worldX, worldZ, noise);
            
            // Only spawn on dry land
            if (worldY > seaLevelHeight + 0.5f) {

                // Probe the nation grid
                int nationId = nationManager.getNationAtWorld(worldX, worldZ, totalRegionWidth);

                if (nationId != 0 && nationSignProbabilities.containsKey(nationId)) {
                    // Check against this specific nation's spawn probability
                    if (chunkRand.nextDouble() < nationSignProbabilities.get(nationId)) {
                        // Generate a random rotation so signs face different directions
                        float randomRotY = chunkRand.nextFloat() * 360.0f;
                        objects.add(new InfrastructureObject(InfrastructureObject.Type.SIGN, new Vec3(worldX, worldY, worldZ), nationId, randomRotY));
                    }
                }
            }
        }
        return objects;
    }
}