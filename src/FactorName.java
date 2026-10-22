/** The overlays the map can colour the land by. */
public enum FactorName {
    TEMPERATURE("Temperature"),
    RAINFALL("Rainfall"),
    SOIL_COLOUR("Soil colour"),
    ROCKS("Rock frequency"),
    ROCK_SIZE("Rock size"),
    ANIMAL_POPULATION("Animal population"),
    FLORA("Flora"),
    GRASS_ABUNDANCE("Grass abundance"),
    GRASS_HEIGHT("Grass height"),
    GRASS_COLOUR("Grass colour"),
    WEALTH("Wealth"),
    NATIONS("Nations"),
    BOLLARDS("Bollards"),
    ROAD_LINES("Road lines"),
    UTILITY_POLES("Utility poles"),
    ARCHITECTURE("Architecture"),
    LANDMARKS("Landmarks");

    private final String label;

    FactorName(String label) {
        this.label = label;
    }

    /** As the map's choices list it. */
    public String label() {
        return label;
    }
}
