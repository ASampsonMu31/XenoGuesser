/** One of the map's overlays: a factor, or (for the animals) one species' population. */
record MapOverlay(FactorName factor, int species) {
    static MapOverlay of(FactorName factor) {
        return new MapOverlay(factor, -1);
    }
}
