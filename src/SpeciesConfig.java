import com.xenoguesser.math.Vector3;

/**
 * A plant species as generated for this world (see FloraSpecies.createFloraSpecies): how it
 * grows, its colours alive and dying, the climate it likes, how common it is, and its design.
 */
class SpeciesConfig {
    String name;
    int leafTexNum;
    
    // Base procedural growth boundaries
    float baseBRate, varBRate;
    float baseSWidth, varSWidth;
    float baseWDecl, varWDecl;
    float baseSDist, varSDist;
    float baseBAngle, varBAngle;
    float leafScale; 
    
    // Dynamic phenotype profiles
    Vector3 healthyColor;
    Vector3 dyingColor;
    Vector3 trunkColor; // NEW: Controls the bark/wood color
    
    // Climate niche constraints
    float tempMean, tempStdDev;
    // How much further its tolerance reaches into the heat than the cold (1 for trees; see OrganismSpecies.heatHardiness)
    float tempHotStretch = 1f;
    float patchNoiseScale;
    
    RegionalFactor tempFactor;
    RegionalFactor patchNoiseFactor;
    RegionalFactor abundanceFactor;

    float baseAbundance;

    // What it looks like (see Flora.Design), and its body's colours and markings (see fs_flora)
    Flora.Design design;
    Vector3 bodyColour, patternColour;
    float pattern, patternScale;

    public SpeciesConfig(String name, int leafTexNum, float leafScale,
                         float baseBRate, float varBRate, float baseSWidth, float varSWidth,
                         float baseWDecl, float varWDecl, float baseSDist, float varSDist,
                         float baseBAngle, float varBAngle, Vector3 healthyColor, Vector3 dyingColor, Vector3 trunkColor,
                         float tempMean, float tempStdDev, float patchNoiseScale, float baseAbundance) {
        this.name = name;
        this.leafTexNum = leafTexNum;
        this.leafScale = leafScale; 
        this.baseBRate = baseBRate; this.varBRate = varBRate;
        this.baseSWidth = baseSWidth; this.varSWidth = varSWidth;
        this.baseWDecl = baseWDecl; this.varWDecl = varWDecl;
        this.baseSDist = baseSDist; this.varSDist = varSDist;
        this.baseBAngle = baseBAngle; this.varBAngle = varBAngle;
        this.healthyColor = healthyColor;
        this.dyingColor = dyingColor;
        this.trunkColor = trunkColor; // NEW
        this.tempMean = tempMean;
        this.tempStdDev = tempStdDev;
        this.patchNoiseScale = patchNoiseScale;
        this.baseAbundance = baseAbundance;
    }
}
