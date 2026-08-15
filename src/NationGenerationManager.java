import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class NationGenerationManager {
    
    public final int[][] nationMap;
    private final int resolution;
    public final int numNations;
    private final Map<Integer, Color> nationColors;
    
    // --- Per-Country Noise Maps ---
    private final Map<Integer, PerlinNoise> nationNoiseMaps;
    private static final float COUNTRY_NOISE_SCALE = 0.01f; // Scale of favorable/unfavorable expansion terrain
    
    private float savedTotalRegionWidth = 150000.0f; 
    private final float physicalChunkSize = 100.0f;   

    // --- Configuration (No Sea Modifiers) ---
    private static final double P_BASE = 0.60; 
    private static final double P_SEA_MODIFIER = 0.05; // Nations spread at 15% speed over ocean water
    private static final int POINTS_PER_NATION = 6;
    private static final int CLUSTER_SPREAD = 2;
    private float seaLevelHeight;

    // A temporary reference cache to capture the engine's sampler for the overlay drawer
    private PerlinNoise globalHeightSamplerFallback;

    public NationGenerationManager(long seed, int numNations, int mapResolution, float seaLevelHeight, PerlinNoise noise) {
        this.numNations = numNations;
        this.resolution = mapResolution;
        this.seaLevelHeight = seaLevelHeight;
        this.globalHeightSamplerFallback = noise;
        
        this.nationMap = new int[resolution][resolution];
        this.nationColors = new HashMap<>();
        this.nationNoiseMaps = new HashMap<>();
        
        Random colorRand = new Random(seed + 999);
        Random noiseSeedRand = new Random(seed + 404);
        
        for (int i = 1; i <= numNations; i++) {
            nationColors.put(i, new Color(colorRand.nextInt(206) + 50, colorRand.nextInt(206) + 50, colorRand.nextInt(206) + 50, 200));
            
            // Assign a unique Perlin noise generator per country using your class
            nationNoiseMaps.put(i, new PerlinNoise(noiseSeedRand.nextLong()));
        }
    }

    /**
     * Executes Cellular Automata while evaluating world heights to slow down spreading over ocean water.
     */
    public void generateTerritories(long seed, float totalRegionWidth) {
        this.savedTotalRegionWidth = totalRegionWidth;
        float halfRegion = totalRegionWidth / 2.0f;
        
        Random rand = new Random(seed);
        
        int[][] currentGrid = new int[resolution][resolution];
        int[][] newGrid = new int[resolution][resolution];
        ArrayList<int[]> activeFrontier = new ArrayList<>();
        
        // 1. Establish Clustered Starting Seeds (Anywhere on the map)
        for (int k = 1; k <= numNations; k++) {
            int centerI = CLUSTER_SPREAD + rand.nextInt(Math.max(1, resolution - 2 * CLUSTER_SPREAD));
            int centerJ = CLUSTER_SPREAD + rand.nextInt(Math.max(1, resolution - 2 * CLUSTER_SPREAD));
            
            int pointsSpawned = 0;
            int spawnAttempts = 0;
            while (pointsSpawned < POINTS_PER_NATION && spawnAttempts < 100) {
                spawnAttempts++;
                int i = centerI + rand.nextInt(2 * CLUSTER_SPREAD + 1) - CLUSTER_SPREAD;
                int j = centerJ + rand.nextInt(2 * CLUSTER_SPREAD + 1) - CLUSTER_SPREAD;
                
                if (i >= 0 && i < resolution && j >= 0 && j < resolution) {
                    if (currentGrid[i][j] == 0) {
                        currentGrid[i][j] = k;
                        newGrid[i][j] = k;
                        activeFrontier.add(new int[]{i, j});
                        pointsSpawned++;
                    }
                }
            }
        }
        
        // 2. Main Spreading Loop (Calculates layered heights to check for oceans & per-country noise)
        while (!activeFrontier.isEmpty()) {
            ArrayList<int[]> nextFrontier = new ArrayList<>();
            ArrayList<int[]> newClaims = new ArrayList<>();
            
            for (int[] cell : activeFrontier) {
                int cx = cell[0];
                int cz = cell[1];
                int cellVal = currentGrid[cx][cz];
                boolean hasEmptyNeighbor = false;
                
                // Fetch the active nation's unique noise generator
                PerlinNoise countryNoise = nationNoiseMaps.get(cellVal);
                
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dj == 0) continue;
                        
                        int nx = cx + di;
                        int nz = cz + dj;
                        
                        if (nx >= 0 && nx < resolution && nz >= 0 && nz < resolution) {
                            if (currentGrid[nx][nz] == 0 && newGrid[nx][nz] == 0) {
                                hasEmptyNeighbor = true;
                                
                                double prob = P_BASE;
                                
                                // Account for diagonal layout speed decay
                                boolean isDiagonal = (di != 0 && dj != 0);
                                if (isDiagonal) prob *= 0.70710678; 
                                
                                // --- PER-COUNTRY NOISE FRICTION LOOKUP ---
                                if (countryNoise != null) {
                                    // Map eval output (roughly -1.0 to 1.0) to a friction multiplier
                                    float noiseVal = countryNoise.eval(nx * COUNTRY_NOISE_SCALE, nz * COUNTRY_NOISE_SCALE);
                                    // Scale to a range of roughly 0.2x to 1.8x speed
                                    float countryFriction = (float) ((noiseVal + 1.0) * 0.8 + 0.2);
                                    prob *= countryFriction;
                                }
                                
                                // Map simulation cell back to real world physics coordinates
                                float worldX = ((float) nx / resolution) * totalRegionWidth - halfRegion;
                                float worldZ = ((float) nz / resolution) * totalRegionWidth - halfRegion;
                                
                                float terrainHeight = 0.0f;
                                if (globalHeightSamplerFallback != null) {
                                    terrainHeight = TerrainMesh.getLayeredHeight(worldX, worldZ, globalHeightSamplerFallback);
                                }
                                
                                // Reduce spreading probability over sea regions
                                if (terrainHeight <= seaLevelHeight) {
                                    prob *= P_SEA_MODIFIER;
                                }
                                
                                if (rand.nextDouble() < prob) {
                                    newGrid[nx][nz] = cellVal;
                                    newClaims.add(new int[]{nx, nz});
                                }
                            } else if (currentGrid[nx][nz] == 0 && newGrid[nx][nz] != 0) {
                                hasEmptyNeighbor = true;
                            }
                        }
                    }
                }
                
                if (hasEmptyNeighbor) {
                    nextFrontier.add(cell);
                }
            }
            
            for (int[] claim : newClaims) {
                currentGrid[claim[0]][claim[1]] = newGrid[claim[0]][claim[1]];
                nextFrontier.add(claim);
            }
            
            activeFrontier = nextFrontier;
        }

        // 3. Guaranteed Complete Sweep (Fills any mathematically trapped pixels)
        for (int x = 0; x < resolution; x++) {
            for (int z = 0; z < resolution; z++) {
                if (newGrid[x][z] == 0) {
                    newGrid[x][z] = findNearestClaimedNation(newGrid, x, z);
                }
                nationMap[x][z] = newGrid[x][z]; 
            }
        }
    }

    private int findNearestClaimedNation(int[][] grid, int startX, int startZ) {
        int searchRadius = 1;
        while (searchRadius < resolution) {
            for (int dx = -searchRadius; dx <= searchRadius; dx++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    int nx = startX + dx;
                    int nz = startZ + dz;
                    if (nx >= 0 && nx < resolution && nz >= 0 && nz < resolution) {
                        if (grid[nx][nz] != 0) {
                            return grid[nx][nz];
                        }
                    }
                }
            }
            searchRadius += 3;
        }
        return 1; 
    }

    public BufferedImage generateNationOverlay(int outputResolution) {
        return generateNationOverlay();
    }

    /**
     * Restores nation rendering. Renders transparency where no claims reside.
     */
    public BufferedImage generateNationOverlay() {
        float halfRegion = savedTotalRegionWidth / 2.0f;
        int minChunkX = (int) Math.floor(-halfRegion / physicalChunkSize);
        int maxChunkX = (int) Math.floor(halfRegion / physicalChunkSize);
        int minChunkZ = (int) Math.floor(-halfRegion / physicalChunkSize);
        int maxChunkZ = (int) Math.floor(halfRegion / physicalChunkSize);
        
        int width = maxChunkX - minChunkX + 1;
        int height = maxChunkZ - minChunkZ + 1;
        
        BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < height; z++) {
                
                int simX = (int) (((float) x / width) * resolution);
                int simZ = (int) (((float) z / height) * resolution);
                
                simX = Math.max(0, Math.min(simX, resolution - 1));
                simZ = Math.max(0, Math.min(simZ, resolution - 1));
                
                int cellVal = nationMap[simX][simZ];
                
                // Draw nation colors if they exist
                if (cellVal != 0 && nationColors.containsKey(cellVal)) {
                    overlay.setRGB(x, z, nationColors.get(cellVal).getRGB());
                } else {
                    overlay.setRGB(x, z, 0x00000000); // Fully transparent fallback
                }
            }
        }
        
        return overlay;
    }

    /**
     * Maps real-world coordinates back to the CA grid to find the nation ID.
     */
    public int getNationAtWorld(float worldX, float worldZ, float totalRegionWidth) {
        float halfRegion = totalRegionWidth / 2.0f;
        
        int simX = (int) (((worldX + halfRegion) / totalRegionWidth) * resolution);
        int simZ = (int) (((worldZ + halfRegion) / totalRegionWidth) * resolution);
        
        // Clamp to prevent out-of-bounds if the player reaches the absolute edge
        simX = Math.max(0, Math.min(simX, resolution - 1));
        simZ = Math.max(0, Math.min(simZ, resolution - 1));
        
        return nationMap[simX][simZ];
    }

    /**
     * Safely fetches the mapped color for rendering.
     */
    public Color getNationColor(int nationId) {
        return nationColors.getOrDefault(nationId, new Color(150, 150, 150)); // Fallback grey
    }
}