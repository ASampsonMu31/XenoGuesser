import java.util.ArrayList;
import java.util.List;

public class RegionalFactor {

    @FunctionalInterface
    public interface Sampler {
        float sample(int cx, int cz, float worldX, float worldZ);
    }

    // Stores a sub-factor alongside its specific weight for this composition
    private static class FactorEntry {
        final RegionalFactor factor;
        final float compositionWeight;

        FactorEntry(RegionalFactor factor, float compositionWeight) {
            this.factor = factor;
            this.compositionWeight = compositionWeight;
        }
    }

    private final float weight;
    private final float powerCurve;
    private final Sampler baseSampler;
    private final List<FactorEntry> subFactors;

    /**
     * Constructor for an ATOMIC Leaf Factor.
     */
    public RegionalFactor(float weight, Sampler baseSampler) {
        this.weight = weight;
        this.powerCurve = 1.0f;
        this.baseSampler = baseSampler;
        this.subFactors = null;
    }

    /**
     * Private constructor used by the Builder for a COMPOSED Blended Factor.
     */
    private RegionalFactor(float weight, float powerCurve, List<FactorEntry> subFactors) {
        this.weight = weight;
        this.powerCurve = powerCurve;
        this.subFactors = subFactors;
        this.baseSampler = null;
    }

    /**
     * Builder class to define sub-factors and their composition weights cleanly.
     */
    public static class Builder {
        private float weight = 1.0f;
        private float powerCurve = 1.0f;
        private final List<FactorEntry> subFactors = new ArrayList<>();

        public Builder setWeight(float weight) {
            this.weight = weight;
            return this;
        }

        public Builder setPowerCurve(float powerCurve) {
            this.powerCurve = powerCurve;
            return this;
        }

        public Builder addFactor(RegionalFactor factor, float compositionWeight) {
            if (factor != null) {
                this.subFactors.add(new FactorEntry(factor, compositionWeight));
            }
            return this;
        }

        public RegionalFactor build() {
            return new RegionalFactor(weight, powerCurve, subFactors);
        }
    }

    /**
     * Recursive Evaluation Process. Evaluates itself if atomic, 
     * or dynamically computes a weighted, normalized blended value across its children.
     */
    public float evaluate(int cx, int cz, float worldX, float worldZ) {
        // Path A: Evaluation loop encounters a baseline Leaf sampler node
        if (baseSampler != null) {
            float rawSample = baseSampler.sample(cx, cz, worldX, worldZ);
            return Math.max(0.0f, Math.min(1.0f, rawSample * weight));
        }

        // Path B: Evaluation loop processes a composite grouping tree node
        if (subFactors != null && !subFactors.isEmpty()) {
            float totalValueAccumulator = 0.0f;
            float totalWeightAccumulator = 0.0f;

            for (FactorEntry entry : subFactors) {
                float val = entry.factor.evaluate(cx, cz, worldX, worldZ);
                // Apply the composition-specific weight to this sub-factor's output
                totalValueAccumulator += val * entry.compositionWeight;
                totalWeightAccumulator += entry.compositionWeight;
            }

            if (totalWeightAccumulator == 0.0f) return 0.0f;
            
            // Perform weighted normalized blending arithmetic 
            float blendedAverage = totalValueAccumulator / totalWeightAccumulator;
            
            // Factor-level structural shaping transformations
            float transformedValue = (float) Math.pow(blendedAverage, powerCurve);
            return Math.max(0.0f, Math.min(1.0f, transformedValue * weight));
        }

        return 0.0f;
    }
}