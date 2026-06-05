import java.util.Random;

public class PlanetConfig {
    public static float planetRadius;

    public static void generateNewPlanet() {
        Random rand = new Random();
        float meanRadius = 677.03f;
        float standardDeviation = meanRadius * 0.20f; // 20% variance (~135.41f)
        float hardLowerLimit = 350.0f;

        // Generate from normal distribution (Gaussian)
        float generatedRadius = (float) (rand.nextGaussian() * standardDeviation + meanRadius);

        // Apply hard lower safety floor
        if (generatedRadius < hardLowerLimit) {
            generatedRadius = hardLowerLimit;
        }

        planetRadius = generatedRadius;
        System.out.println("Generated Planet Radius: " + planetRadius + " units.");
    }
}