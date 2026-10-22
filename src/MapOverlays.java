import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;
import com.xenoguesser.math.*;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * What the map can show and what the player owns of it: the bird's-eye map and its layers
 * (drawn in the background once the world is made), the heatmaps and maps of pictures laid
 * over it (worked out the first time each is wanted), which of them the player has bought,
 * the choices beside the enlarged map, and the shop's offers of the rest.
 */
final class MapOverlays extends GamePart {

    MapOverlays(XenoGuesser_GLEventListener game) {
        super(game);
    }

    MapOverlay currentDebugFactor;

    // Bird's-eye minimap layers, rendered on a background thread at startup
    private static final int BIRDS_EYE_MAP_RESOLUTION = 750;

    private volatile BirdsEyeMaps birdsEyeMaps;

    /**
     * A nation's name in its own writing, as the map shows it: two to four of its letters
     * (the same each time for the same world) in white with a dark edge, laid out in its
     * direction of writing. Null if its letters can't be read.
     */
    private BufferedImage nationNameImage(int nationId) {
        int alphabetId = game.infraManager.scripts().alphabet(nationId);
        java.io.File dir = new java.io.File(RunFiles.ALPHABETS_DIR, "alphabet" + alphabetId);
        java.io.File[] files = dir.listFiles((d, name) -> name.startsWith("glyph_") && name.endsWith(".png"));
        if (files == null || files.length < 2) return null;
        java.util.Random rand = new java.util.Random(game.worldSeed * 131L + nationId * 7919L);
        int letters = 2 + rand.nextInt(3);
        int direction = game.infraManager.scripts().direction(nationId);
        boolean vertical = direction >= 2, backwards = direction == 1 || direction == 3;
        int cell = MapPanel.NATION_NAME_GLYPH, pad = 3, step = cell - 2;
        int width = (vertical ? cell : step * (letters - 1) + cell) + pad * 2;
        int height = (vertical ? step * (letters - 1) + cell : cell) + pad * 2;
        // The letters' ink, then a dark edge round it and the ink in white on top
        boolean[] ink = new boolean[width * height];
        try {
            for (int i = 0; i < letters; i++) {
                // Glyph 0 is a space
                int letter = 1 + rand.nextInt(files.length - 1);
                BufferedImage glyph = javax.imageio.ImageIO.read(new java.io.File(dir, "glyph_" + letter + ".png"));
                if (glyph == null) return null;
                int slot = backwards ? letters - 1 - i : i;
                int ox = pad + (vertical ? 0 : slot * step), oy = pad + (vertical ? slot * step : 0);
                for (int y = 0; y < cell; y++) {
                    for (int x = 0; x < cell; x++) {
                        int gx = x * glyph.getWidth() / cell, gy = y * glyph.getHeight() / cell;
                        if ((glyph.getRGB(gx, gy) & 0xFF) < 110) ink[(oy + y) * width + ox + x] = true;
                    }
                }
            }
        } catch (java.io.IOException e) {
            return null;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (ink[y * width + x]) {
                    image.setRGB(x, y, 0xFFFAF8F0);
                    continue;
                }
                boolean edge = false;
                for (int dy = -2; dy <= 2 && !edge; dy++) {
                    for (int dx = -2; dx <= 2 && !edge; dx++) {
                        int nx = x + dx, ny = y + dy;
                        edge = nx >= 0 && ny >= 0 && nx < width && ny < height && ink[ny * width + nx];
                    }
                }
                if (edge) image.setRGB(x, y, 0xD8141218);
            }
        }
        return image;
    }

    void startBirdsEyeMapRendering() {
        Thread mapThread = new Thread(() -> {
            BirdsEyeMaps maps = new BirdsEyeMaps(BIRDS_EYE_MAP_RESOLUTION, game.TOTAL_REGION_WIDTH, game.seaLevelHeight, game.worldNoise);
            birdsEyeMaps = maps;
            long startTime = System.currentTimeMillis();

            BufferedImage[] baseMap = maps.renderBaseMap();
            // (nothing's handed over, nor the overlays made, until there's a map to take them: it's
            // only made once the window opens, and anything handed over before then was lost)
            awaitMinimap();
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (game.minimap != null) {
                    game.minimap.setBaseMapDetail(baseMap[1]);
                }
            });

            

            // The nations' names, in their own writing, where their largest land is, with their flags
            List<NationLabel> labels = new ArrayList<>();
            Map<Integer, BufferedImage> flags = new HashMap<>();
            for (float[] place : maps.nationLabelPlaces(game.nationManager)) {
                int nation = (int) place[0];
                BufferedImage name = nationNameImage(nation);
                BufferedImage flag = flags.computeIfAbsent(nation, game.infraManager::flagImage);
                if (name != null) labels.add(new NationLabel(name, place[1], place[2], place[4] > 0.5f, flag));
            }
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (game.minimap != null) game.minimap.setNationLabels(labels);
            });
            // The picture maps' pictures: each nation's bollard, road markings or utility pole where
            // its main land's name goes
            java.util.Map<FactorName, List<MapPicture>> pictures = new java.util.EnumMap<>(FactorName.class);
            for (FactorName f : PICTURE_MAPS) pictures.put(f, new ArrayList<>());
            // (a nation building more than one bollard or pole: its main one where its name goes,
            // each other one part way towards the neighbour whose it is, on the side it's built)
            java.util.Map<Integer, float[]> middles = new HashMap<>();
            for (float[] place : maps.nationLabelPlaces(game.nationManager)) if (place[4] > 0.5f) middles.put((int) place[0], place);
            List<MapPicture> architectureMain = new ArrayList<>(), architectureOthers = new ArrayList<>();
            for (float[] place : middles.values()) {
                int nation = (int) place[0];
                for (NationDesign<BollardStyle> design : game.infraManager.bollardDesigns(nation)) {
                    float[] at = towardsNeighbour(place, middles.get(design.from()));
                    pictures.get(FactorName.BOLLARDS).add(new MapPicture(design.design().picture(MAP_PICTURE_HEIGHT), at[0], at[1]));
                }
                RoadLineStyle lines = game.infraManager.roadLineStyle(nation);
                if (lines != null) pictures.get(FactorName.ROAD_LINES).add(new MapPicture(lines.picture(MAP_PICTURE_HEIGHT, RoadPath.RoadClass.STREET.width), place[1], place[2]));
                for (NationDesign<UtilityPoleStyle> design : game.infraManager.poleDesigns(nation)) {
                    float[] at = towardsNeighbour(place, middles.get(design.from()));
                    pictures.get(FactorName.UTILITY_POLES).add(new MapPicture(design.design().picture(MAP_PICTURE_HEIGHT), at[0], at[1]));
                }
                // (and a nation's province, with its own take on the nation's architecture, in the
                // middle of the province: gathered first, then thinned out, see spaceOut)
                for (InfrastructureManager.ArchitecturePicture design : game.infraManager.architecturePictures(nation, MAP_PICTURE_HEIGHT)) {
                    float[] at;
                    if (design.from() < 0) {
                        double[] spot = design.spot();
                        double[] map = MapProjection.toMap(new GeodesicChunkGrid.Direction(spot[0], spot[1], spot[2]));
                        at = new float[] { (float) map[0], (float) map[1] };
                    } else {
                        at = towardsNeighbour(place, middles.get(design.from()));
                    }
                    (design.from() == 0 ? architectureMain : architectureOthers).add(new MapPicture(design.picture(), at[0], at[1]));
                }
            }
            pictures.get(FactorName.ARCHITECTURE).addAll(spaceOut(architectureMain, architectureOthers));
            mapPictures = pictures;
            // (a picture map already chosen gets its pictures now)
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (game.minimap != null && currentPictureMap != null) choosePictureMap(currentPictureMap);
            });
            // Drawn in tiles when zoomed in close
            java.util.Map<MapPanel.Layer, MapTiles.Renderer> layerTiles = new java.util.EnumMap<>(java.util.Map.of(
                    MapPanel.Layer.CONTOURS, maps.contourTiles(), MapPanel.Layer.ROADS, maps.roadTiles(game.infraManager),
                    MapPanel.Layer.BUILDINGS, maps.buildingTiles(game.infraManager), MapPanel.Layer.SHOPS, maps.shopTiles(game.infraManager)));
            if (game.worldNoise.rivers != null) layerTiles.put(MapPanel.Layer.RIVERS, maps.riverTiles(game.worldNoise.rivers));
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (game.minimap != null) game.minimap.setTileRenderers(maps.baseTiles(), layerTiles);
            });
            showLayer(MapPanel.Layer.CONTOURS, maps.renderContourLayer());
            if (game.worldNoise.rivers != null) showLayer(MapPanel.Layer.RIVERS, maps.renderRiverLayer(game.worldNoise.rivers));
            showLayer(MapPanel.Layer.ROADS, maps.renderRoadLayer(game.infraManager));
            showLayer(MapPanel.Layer.BUILDINGS, maps.renderBuildingLayer(game.infraManager));
            showLayer(MapPanel.Layer.SHOPS, maps.renderShopLayer(game.infraManager));

            // Then every overlay, side by side, so choosing one later is instant (the round doesn't
            // start until they're done: see advanceLoading)
            allOverlays().parallelStream().forEach(this::overlayImage);
            mapsReady = true;
            System.out.printf("[MINIMAP] Bird's-eye maps ready in %d ms%n", System.currentTimeMillis() - startTime);

            // The landmarks: the natural landforms found now the land's known, then each one's
            // picture, stood where it is (after the rest, as the round needn't wait for them)
            Landmarks landmarks = game.infraManager.landmarks();
            landmarks.findLandforms(game.worldSeed, game.landPoints, p -> TerrainMesh.getLayeredHeight(p, game.worldNoise), game.seaLevelHeight, game.nationManager::getNationAt);
            WorldPalette palette = game.worldArt.palette();
            java.util.function.Function<float[], Vector3> v = c -> new Vector3(c[0], c[1], c[2]);
            landmarks.setLook(new Landmark.Look(v.apply(palette.skyZenith), v.apply(palette.skyHorizon), v.apply(palette.grassLush),
                    v.apply(palette.grassDry), v.apply(palette.soilBase), v.apply(palette.seaLight)));
            List<MapPicture> landmarkPictures = new ArrayList<>();
            for (Landmark landmark : landmarks.all()) {
                BufferedImage picture = landmark.render(LANDMARK_PICTURE_HEIGHT);
                landmark.picture = picture;
                double[] at = MapProjection.toMap(landmark.place());
                landmarkPictures.add(new MapPicture(picture, (float) at[0], (float) at[1], true, landmark.name, landmark.caption, landmark.kind.label));
            }
            java.util.Map<FactorName, List<MapPicture>> withLandmarks = new java.util.EnumMap<>(mapPictures);
            withLandmarks.put(FactorName.LANDMARKS, landmarkPictures);
            mapPictures = withLandmarks;
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (game.minimap != null && currentPictureMap == FactorName.LANDMARKS) choosePictureMap(currentPictureMap);
            });
        }, "birds-eye-map-renderer");
        mapThread.setDaemon(true);
        mapThread.start();
    }

    /** Waits (on the map's own thread) until the map panel's been given to the game (see setMinimap). */
    private void awaitMinimap() {
        while (game.minimap == null) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Hands a finished layer's images to the map. */
    private void showLayer(MapPanel.Layer layer, BufferedImage[] images) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (game.minimap != null) game.minimap.setLayerImages(layer, images);
        });
    }

    /**
     * Advances GL-side loading by one step per display() call: uploads and shaders, one
     * flora species at a time, then the first terrain around the spawn point, then a first
     * real frame. Returns true once the game is ready to render normally.
     */
    // The bird's-eye map and all its overlays are done; when the loading screen began waiting for them
    volatile boolean mapsReady;

    // Layers the developer aid asks to start ticked
    final java.util.Set<MapPanel.Layer> startingLayers = java.util.EnumSet.noneOf(MapPanel.Layer.class);

    // Gradient maps already worked out (they take a few seconds each)
    private final Map<MapOverlay, BufferedImage> gradientCache = new java.util.concurrent.ConcurrentHashMap<>();

    final java.util.Set<MapPanel.Layer> ownedLayers = java.util.Collections.synchronizedSet(java.util.EnumSet.noneOf(MapPanel.Layer.class));

    final java.util.Set<MapOverlay> ownedOverlays = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /**
     * The collections' achievements (M53), for what's been got this game: every infrastructure
     * map (the buildings, shops and the maps of pictures), every plant's and every animal's
     * heatmap. (Not with the developer's everything unlocked. Every gadget, Technician, is had
     * from the start now, so is never earned by collecting.)
     */
    private void checkCollections() {
        if (Dev.flag("xenoguesser.unlockall")) return;
        boolean infrastructure = ownedLayers.contains(MapPanel.Layer.BUILDINGS) && ownedLayers.contains(MapPanel.Layer.SHOPS);
        for (FactorName f : PICTURE_MAPS) infrastructure &= ownedOverlays.contains(MapOverlay.of(f));
        if (infrastructure) Achievements.unlock(Achievements.Achievement.INFRASTRUCTURE_EXPERT);
        boolean flora = true, fauna = game.organismManager.speciesCount() > 0;
        for (int s = 0; s < game.NUM_SPECIES; s++) flora &= ownedOverlays.contains(new MapOverlay(FactorName.FLORA, s));
        for (int i = 0; i < game.organismManager.speciesCount(); i++) fauna &= ownedOverlays.contains(new MapOverlay(FactorName.ANIMAL_POPULATION, i));
        if (flora) Achievements.unlock(Achievements.Achievement.BOTANIST);
        if (fauna) Achievements.unlock(Achievements.Achievement.ZOOLOGIST);
    }

    /** Lists, beside the enlarged map, the layers and overlays the player has. */
    void refreshMapChoices() {
        if (game.minimap == null) return;
        checkCollections();
        List<MapPanel.Layer> layers = new ArrayList<>();
        for (MapPanel.Layer layer : MapPanel.Layer.values()) if (ownedLayers.contains(layer)) layers.add(layer);
        java.util.LinkedHashMap<String, List<String>> groups = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, List<MapOverlay>> group : overlayGroups().entrySet()) {
            List<String> labels = new ArrayList<>();
            for (MapOverlay o : group.getValue()) if (ownedOverlays.contains(o)) labels.add(overlayLabel(o));
            groups.put(group.getKey(), labels);
        }
        // (the maps of pictures go in the markings' Infrastructure folder: M47)
        List<String> pictureMaps = new ArrayList<>();
        for (FactorName f : PICTURE_MAPS) if (ownedOverlays.contains(MapOverlay.of(f))) pictureMaps.add(f.label());
        groups.get("").removeIf(label -> pictureMaps.contains(label));
        MapOverlay shown = currentDebugFactor;
        FactorName pictures = currentPictureMap;
        game.minimap.setLayerChoices(layers, groups, shown != null && ownedOverlays.contains(shown) ? overlayLabel(shown) : null, label -> {
            MapOverlay chosen = null;
            for (MapOverlay o : allOverlays()) if (overlayLabel(o).equals(label)) chosen = o;
            chooseGradient(chosen);
        }, pictureMaps, pictures != null ? pictures.label() : null, label -> {
            FactorName chosen = null;
            for (FactorName f : PICTURE_MAPS) if (f.label().equals(label)) chosen = f;
            choosePictureMap(chosen);
        }, this::choiceIcon);
    }

    /** A map's price factor: for one of what people have built (roads, buildings, shops, wealth, nations), as the kind of map makes it (see MapKind). */
    private static float builtPriceFactor(boolean built) {
        return built ? MapKind.current().builtPriceFactor : 1f;
    }

    /**
     * Three maps for the shop after this round (counting from 1), shuffled from everything not
     * yet bought (the gadgets are had from the start, so never offered), each with its price
     * (drawn at random round what the player earns a round, see ShopPanel.price; nothing in multiplayer) and what buying it does.
     */
    public List<ShopPanel.Card> shopOffers(int round) {
        java.util.Map<String, String> descriptions = ShopPanel.loadDescriptions();
        java.util.Random rand = new java.util.Random();
        // (prices keep pace with what the player's been paid lately)
        float pay = game.gameHUD != null ? game.gameHUD.recentPay() : GameHUD.SALARY;
        List<ShopPanel.Card> deck = new ArrayList<>();
        for (MapPanel.Layer layer : MapPanel.Layer.values()) {
            if (ownedLayers.contains(layer)) continue;
            deck.add(new ShopPanel.Card(layer.label, "Map marking", choiceIcon(layer.label), null, describe(descriptions, layer.label, layer.label),
                    ShopPanel.price(round, pay, builtPriceFactor(layer == MapPanel.Layer.ROADS || layer == MapPanel.Layer.BUILDINGS
                            || layer == MapPanel.Layer.SHOPS), rand), () -> {
                ownedLayers.add(layer);
                // (shown straight away)
                game.minimap.setLayerShown(layer, true);
                refreshMapChoices();
            }));
        }
        for (List<MapOverlay> group : overlayGroups().values()) {
            for (MapOverlay o : group) {
                if (ownedOverlays.contains(o)) continue;
                String name = overlayLabel(o);
                boolean plant = o.factor() == FactorName.FLORA, animal = o.factor() == FactorName.ANIMAL_POPULATION;
                String key = plant ? "Flora" : animal ? "Fauna" : name;
                String kind = plant ? "Plant heatmap" : animal ? "Animal heatmap" : PICTURE_MAPS.contains(o.factor()) ? "Map" : "Heatmap";
                deck.add(new ShopPanel.Card(name, kind, choiceIcon(name), null, describe(descriptions, key, name),
                        ShopPanel.price(round, pay, plant || animal ? ShopPanel.SPECIES_PRICE_FACTOR
                                : builtPriceFactor(o.factor() == FactorName.WEALTH || o.factor() == FactorName.NATIONS || PICTURE_MAPS.contains(o.factor())), rand), () -> {
                    ownedOverlays.add(o);
                    refreshMapChoices();
                }));
            }
        }
        java.util.Collections.shuffle(deck, rand);
        List<ShopPanel.Card> dealt = new ArrayList<>();
        for (ShopPanel.Card card : deck) {
            if (dealt.size() < 3 && !dealt.contains(card)) dealt.add(card);
        }
        return dealt;
    }

    /** A card's description from the file, by key, with {name} filled in; a plain one if the file has none. */
    private static String describe(java.util.Map<String, String> descriptions, String key, String name) {
        String text = descriptions.get(key);
        return text != null ? text.replace("{name}", name) : "A map of the planet: " + name + ".";
    }

    /**
     * Colours the map's land by an overlay, or by nothing given null. Every overlay is
     * worked out in the background soon after the world is made, so it's normally ready at
     * once; one asked for before then is finished off in the background and shown when ready,
     * if it's still the one wanted.
     */
    // The dropdowns at the foot of the map's overlay choices, in order
    private static final String ROCK_GROUP = "Rocks", GRASS_GROUP = "Grass", FLORA_GROUP = "Flora", FAUNA_GROUP = "Fauna";

    /**
     * The map's overlays by where they're listed: "" for those listed on their own, then the
     * grass's maps, each plant species' range (trees, then shrubs), and each animal species' range.
     */
    private java.util.LinkedHashMap<String, List<MapOverlay>> overlayGroups() {
        java.util.LinkedHashMap<String, List<MapOverlay>> groups = new java.util.LinkedHashMap<>();
        List<MapOverlay> top = new ArrayList<>();
        for (FactorName f : new FactorName[] { FactorName.TEMPERATURE, FactorName.RAINFALL, FactorName.SOIL_COLOUR, FactorName.WEALTH, FactorName.NATIONS,
                FactorName.BOLLARDS, FactorName.ROAD_LINES, FactorName.UTILITY_POLES, FactorName.ARCHITECTURE, FactorName.LANDMARKS }) {
            top.add(MapOverlay.of(f));
        }
        groups.put("", top);
        groups.put(ROCK_GROUP, List.of(MapOverlay.of(FactorName.ROCKS), MapOverlay.of(FactorName.ROCK_SIZE)));
        groups.put(GRASS_GROUP, List.of(MapOverlay.of(FactorName.GRASS_ABUNDANCE), MapOverlay.of(FactorName.GRASS_HEIGHT), MapOverlay.of(FactorName.GRASS_COLOUR)));
        List<MapOverlay> flora = new ArrayList<>();
        for (int s = 0; s < game.NUM_SPECIES; s++) flora.add(new MapOverlay(FactorName.FLORA, s));
        groups.put(FLORA_GROUP, flora);
        List<MapOverlay> fauna = new ArrayList<>();
        for (int i = 0; i < game.organismManager.speciesCount(); i++) fauna.add(new MapOverlay(FactorName.ANIMAL_POPULATION, i));
        groups.put(FAUNA_GROUP, fauna);
        return groups;
    }

    /** Every overlay the map offers. */
    List<MapOverlay> allOverlays() {
        List<MapOverlay> overlays = new ArrayList<>();
        for (List<MapOverlay> group : overlayGroups().values()) overlays.addAll(group);
        return overlays;
    }

    /** An overlay's name on the map: its factor's, or for a species, the species' own name. */
    String overlayLabel(MapOverlay o) {
        if (o.species() >= 0 && o.factor() == FactorName.FLORA) return game.floraName(o.species());
        return o.species() >= 0 ? game.organismManager.speciesName(o.species()) : o.factor().label();
    }

    // The icons for the map's choices (assets/icons), by file name, and each species' picture
    // once drawn (see drawSpeciesPortraits)
    private final Map<String, java.awt.Image> choiceIcons = new java.util.concurrent.ConcurrentHashMap<>();

    /** The icon for one of the map's choices, by its name (a layer's or an overlay's); null if none. */
    // The maps of pictures rather than colours: each nation's bollard, road markings or utility
    // pole stood on its main land, over the plain map, no borders (see chooseGradient); their
    // pictures ({picture, worldX, worldZ}), once drawn, and how tall
    static final java.util.Set<FactorName> PICTURE_MAPS = java.util.EnumSet.of(FactorName.BOLLARDS, FactorName.ROAD_LINES, FactorName.UTILITY_POLES,
            FactorName.ARCHITECTURE, FactorName.LANDMARKS);

    private volatile java.util.Map<FactorName, List<MapPicture>> mapPictures = java.util.Map.of();

    // The map of pictures shown (over any heatmap: M47), or null
    volatile FactorName currentPictureMap;

    private static final int MAP_PICTURE_HEIGHT = 128;

    // How tall the landmarks' pictures are (shown in their popups on the map)
    private static final int LANDMARK_PICTURE_HEIGHT = 220;

    /**
     * Where on the map a nation's design is pictured, {worldX, worldZ}: its middle (where its
     * name goes) for its main one; for a neighbour's, 45% of the way towards that neighbour's
     * middle (not across the map's join, where the way round is the short one), on the side of the
     * nation it's built.
     */
    private float[] towardsNeighbour(float[] own, float[] neighbour) {
        if (neighbour == null) return new float[] { own[1], own[2] };
        float dx = neighbour[1] - own[1], dz = neighbour[2] - own[2];
        if (Math.abs(dx) > game.TOTAL_REGION_WIDTH * 0.5f) return new float[] { own[1] + Math.signum(-dx) * 1500f, own[2] + dz * 0.45f };
        return new float[] { own[1] + dx * 0.45f, own[2] + dz * 0.45f };
    }

    // How far apart (shares of the map's width, across and up) the architecture map's pictures
    // stand: they're wide, so further apart across than up
    private static final float PICTURE_GAP_ACROSS = 0.13f, PICTURE_GAP_UP = 0.08f;

    /**
     * Thins out pictures so they don't crowd together on the map: the
     * main ones first, each kept unless it's on top of one already kept (closer than half the
     * gap), then the others (neighbours' designs, provinces') only where they're a whole gap clear
     * of every one kept. (Across the map's join, the short way round.)
     */
    private List<MapPicture> spaceOut(List<MapPicture> main, List<MapPicture> others) {
        List<MapPicture> kept = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            float gap = pass == 0 ? 0.5f : 1f;
            for (MapPicture p : pass == 0 ? main : others) {
                boolean clear = true;
                for (MapPicture k : kept) {
                    float dx = Math.abs(p.x() - k.x()), dz = Math.abs(p.z() - k.z());
                    dx = Math.min(dx, game.TOTAL_REGION_WIDTH - dx);
                    float ex = dx / (PICTURE_GAP_ACROSS * gap * game.TOTAL_REGION_WIDTH), ez = dz / (PICTURE_GAP_UP * gap * game.TOTAL_REGION_WIDTH);
                    if (ex * ex + ez * ez < 1f) {
                        clear = false;
                        break;
                    }
                }
                if (clear) kept.add(p);
            }
        }
        return kept;
    }

    /** A picture map's pictures (none yet until they're drawn). */
    private List<MapPicture> picturesOf(FactorName f) {
        return mapPictures.getOrDefault(f, List.of());
    }

    private java.awt.Image choiceIcon(String label) {
        for (MapOverlay o : allOverlays()) {
            if (o.species() >= 0 && overlayLabel(o).equals(label)) {
                java.awt.Image[] portraits = o.factor() == FactorName.FLORA ? game.speciesPictures.floraPortraits : game.speciesPictures.speciesPortraits;
                if (portraits != null && portraits[o.species()] != null) return portraits[o.species()];
                return loadIcon(o.factor() == FactorName.FLORA ? "flora" : "animal");
            }
        }
        String file = switch (label) {
            case "Contours" -> "contours";
            case "Rivers" -> "rivers";
            case "Roads" -> "roads";
            case "Buildings" -> "buildings";
            case "Shops" -> "shops";
            case "None" -> "none";
            case "Temperature" -> "temperature";
            case "Rainfall" -> "rainfall";
            case "Soil colour" -> "soil";
            case "Rocks", "Rock frequency", "Rock size" -> "rocks";
            case "Wealth" -> "wealth";
            case "Nations" -> "nations";
            // (the maps of pictures: the same icon every game, whatever this world's designs)
            case "Bollards" -> "bollards";
            case "Road lines" -> "road_lines";
            case "Utility poles" -> "utility_poles";
            case "Architecture" -> "architecture";
            case "Landmarks" -> "landmarks";
            case "Grass abundance", GRASS_GROUP -> "grass";
            case "Grass height" -> "grass_height";
            case "Grass colour" -> "grass_colour";
            case FLORA_GROUP -> "flora";
            case FAUNA_GROUP -> "animal";
            default -> null;
        };
        return file == null ? null : loadIcon(file);
    }

    private java.awt.Image loadIcon(String file) {
        return choiceIcons.computeIfAbsent(file, f -> {
            try {
                return ImageIO.read(new File(GamePaths.HOME + "assets/icons/" + f + ".png"));
            } catch (Exception e) {
                return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            }
        });
    }

    /** Shows one of the maps of pictures over the map (and whatever heatmap's on it), or none. */
    public void choosePictureMap(FactorName factor) {
        currentPictureMap = factor;
        game.minimap.setPictureOverlay(factor == null ? null : factor.label(), factor == null ? null : picturesOf(factor));
    }

    public void chooseGradient(MapOverlay factorName) {
        // (a map of pictures isn't a heatmap: it's shown over one)
        if (factorName != null && PICTURE_MAPS.contains(factorName.factor())) {
            choosePictureMap(factorName.factor());
            return;
        }
        currentDebugFactor = factorName;
        // (the nations' names go in the middle of each country, as the maps of pictures do: so
        // choosing the nations puts any of those away)
        if (factorName != null && factorName.factor() == FactorName.NATIONS && currentPictureMap != null) {
            choosePictureMap(null);
            refreshMapChoices();
        }
        java.awt.Image[] portraits = factorName != null && factorName.factor() == FactorName.FLORA ? game.speciesPictures.floraPortraits : game.speciesPictures.speciesPortraits;
        game.minimap.setOverlayPicture(factorName != null && factorName.species() >= 0 && portraits != null ? portraits[factorName.species()] : null);
        FactorName factor = factorName == null ? null : factorName.factor();
        // An animal's or plant's range: it's shown turning round beside the map
        if (factorName != null && factorName.species() >= 0) {
            boolean plant = factor == FactorName.FLORA;
            game.minimap.setPreview(overlayLabel(factorName), plant ? game.speciesConfigs[factorName.species()].design.kind() : "Animal", null,
                    plant ? game.speciesPictures.detailPicture(factorName.species()) : null);
            game.speciesPictures.previewWanted = factorName;
        } else {
            game.minimap.setPreview(null, null, null, null);
            game.speciesPictures.previewWanted = null;
        }
        // The key: the heatmap's colours from its lowest value anywhere on the land to its highest,
        // in real units where it has them
        if (factor == FactorName.GRASS_COLOUR) {
            game.minimap.setLegend(grassColourSpectrum(), new String[] { "Dry", "", "Lush" });
        } else if (factorName != null && factorName.species() >= 0) {
            // A plant's or animal's key: how many in a chunk's area at each end and the middle of its colours
            SpeciesRange range = factorName.factor() == FactorName.FLORA ? game.floraSpecies.floraRanges[factorName.species()]
                    : game.organismManager.range(factorName.species());
            game.minimap.setLegend(spectrumFor(factorName), new String[] { heatmapLabel(factorName, range.densityAtShare(0f)),
                    heatmapLabel(factorName, range.densityAtShare(0.5f)), heatmapLabel(factorName, range.densityAtShare(1f)) });
        } else if (factorName != null && heatmapMeasure(factorName) != null) {
            float[] range = heatmapRange(factorName);
            String[] labels = factor == FactorName.GRASS_ABUNDANCE ? new String[] { "Sparsest", "", "Thickest" }
                    : factor == FactorName.WEALTH ? new String[] { "Poorest", "", "Richest" }
                    : new String[] { heatmapLabel(factorName, range[0]), heatmapLabel(factorName, (range[0] + range[1]) * 0.5f),
                            heatmapLabel(factorName, range[1]) };
            game.minimap.setLegend(spectrumFor(factorName), labels);
        } else {
            game.minimap.setLegend(null, null);
        }
        game.minimap.setOverlayTiles(overlayTiles(factorName), factorName == null ? null : overlayLabel(factorName));
        if (factorName == null) {
            game.minimap.setOverlay(null, null, false);
            return;
        }
        BufferedImage ready = gradientCache.get(factorName);
        if (ready != null) {
            game.minimap.setOverlay(ready, overlayLabel(factorName), factor == FactorName.NATIONS);
            return;
        }
        // (its tiles once the overlay itself is ready)
        Thread heatmapThread = new Thread(() -> {
            BufferedImage composed = overlayImage(factorName);
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (currentDebugFactor == factorName && composed != null) {
                    game.minimap.setOverlayTiles(overlayTiles(factorName), overlayLabel(factorName));
                    game.minimap.setOverlay(composed, overlayLabel(factorName), factor == FactorName.NATIONS);
                }
            });
        }, "gradient-map");
        heatmapThread.setDaemon(true);
        heatmapThread.start();
    }

    /**
     * The soil overlay: the soil's actual colour in every chunk, blended just as the ground's
     * is (see buildSoilRegionMap): the base soil, the first regional soil blended in as far as
     * its factor says, then the second over that as far as its own says.
     */
    private BufferedImage soilColourMap() {
        WorldPalette palette = new WorldPalette(game.worldSeed);
        float[] base = palette.soilBase, a = palette.soilRegionalA, b = palette.soilRegionalB;
        float[][] ranges = game.soilBlendRanges;
        int minChunkX = (int) Math.floor((-game.TOTAL_REGION_WIDTH / 2.0f) / game.PHYSICAL_CHUNK_SIZE);
        int maxChunkX = (int) Math.ceil((game.TOTAL_REGION_WIDTH / 2.0f) / game.PHYSICAL_CHUNK_SIZE);
        int size = maxChunkX - minChunkX + 1;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.util.stream.IntStream.range(0, size).parallel().forEach(z -> {
            float mapZ = (minChunkX + z + 0.5f) * game.PHYSICAL_CHUNK_SIZE;
            for (int x = 0; x < size; x++) {
                GeodesicChunkGrid.Direction d = MapProjection.direction((minChunkX + x + 0.5f) * game.PHYSICAL_CHUNK_SIZE, mapZ);
                float fa = game.soilVariantAFactor.evaluate(d), fb = game.soilVariantBFactor.evaluate(d);
                float wa = ranges == null ? 0f : ProceduralTextures.smoothstep(ranges[0][0], ranges[0][1], fa);
                float wb = ranges == null ? 0f : ProceduralTextures.smoothstep(ranges[1][0], ranges[1][1], fb);
                int rgb = 0xFF;
                for (int c = 0; c < 3; c++) {
                    float colour = base[c] + (a[c] - base[c]) * wa;
                    colour += (b[c] - colour) * wb;
                    rgb = (rgb << 8) | Math.max(0, Math.min(255, Math.round(colour * 255f)));
                }
                image.setRGB(x, z, rgb);
            }
        });
        return image;
    }

    // Each overlay as worked out, a pixel a chunk (before being laid over the land for the map)
    private final Map<MapOverlay, BufferedImage> rawOverlays = new java.util.concurrent.ConcurrentHashMap<>();

    /** How to draw an overlay in tiles when the map is zoomed in close; null if it can't be yet. */
    private MapTiles.Renderer overlayTiles(MapOverlay factorName) {
        BirdsEyeMaps maps = birdsEyeMaps;
        if (factorName == null || maps == null) return null;
        if (factorName.factor() == FactorName.NATIONS) return maps.nationTiles(game.nationManager);
        BufferedImage raw = rawOverlays.get(factorName);
        return raw == null ? null : maps.overlayTiles(raw, game.PHYSICAL_CHUNK_SIZE);
    }

    /**
     * An overlay ready to show on the map, worked out the first time it's asked for and kept
     * (a second asker waits for the first to finish rather than doing it again).
     */
    private BufferedImage overlayImage(MapOverlay overlay) {
        // (the bollard map has no colours of its own: see chooseGradient)
        if (game.minimap == null || PICTURE_MAPS.contains(overlay.factor())) return null;
        return gradientCache.computeIfAbsent(overlay, o -> {
            FactorName f = o.factor();
            if (f == FactorName.NATIONS) {
                // Each nation filled in its own colour, chosen to stand apart from all the others,
                // drawn sharp enough for its borders to stay smooth when zoomed in
                BirdsEyeMaps maps = birdsEyeMaps;
                return maps != null ? maps.renderNationOverlay(game.nationManager) : null;
            }
            BufferedImage heatmap = f == FactorName.SOIL_COLOUR ? soilColourMap()
                    : game.regionalManager.generateHeatmap(game.TOTAL_REGION_WIDTH, game.PHYSICAL_CHUNK_SIZE, factorFor(o), overlayLabel(o), spectrumFor(o));
            // (kept as it is too, a pixel a chunk, for drawing the overlay in tiles when zoomed in)
            rawOverlays.put(o, heatmap);
            return game.minimap.composeOverlay(heatmap);
        });
    }

    /**
     * The grass colour map's colours: the grass's own as it looks by day, from its colour where
     * it grows worst to where it grows best (as the grass shader mixes and lights them, see
     * fs_grass_instanced).
     */
    private float[][] grassColourSpectrum() {
        WorldPalette palette = game.worldArt != null ? game.worldArt.palette() : new WorldPalette(game.worldSeed);
        float[][] spectrum = new float[4][3];
        for (int k = 0; k < 4; k++) {
            for (int c = 0; c < 3; c++) {
                float light = Math.min(1.5f, 3f * XenoGuesser_GLEventListener.DAY_AMBIENT[c]);
                spectrum[k][c] = Math.min(1f, (palette.grassDry[c] + (palette.grassLush[c] - palette.grassDry[c]) * k / 3f) * light);
            }
        }
        return spectrum;
    }

    // The wealth overlay: how well off people are, from each nation's richness, more so in town
    private RegionalFactor wealthOverlay;

    // Its colours, poorest to richest: dull brown through to gold
    private static final float[][] WEALTH_SPECTRUM = {
        { 0.30f, 0.22f, 0.18f }, { 0.58f, 0.48f, 0.40f }, { 0.85f, 0.75f, 0.45f }, { 1.00f, 0.82f, 0.10f }
    };

    /**
     * What a heatmap colours the land by, 0 to 1: its measure (see heatmapMeasure) from the
     * lowest it is anywhere on the land the map shows (0, the key's first colour) to the highest
     * (1, its last). The grass colour map is the grass's own colour instead.
     */
    private RegionalFactor factorFor(MapOverlay overlay) {
        if (overlay.factor() == FactorName.GRASS_COLOUR) {
            float[] span = game.grassColourSpan();
            return new RegionalFactor(1f, d -> XenoGuesser_GLEventListener.grassShare(span, game.grassColourFactor.evaluate(d)));
        }
        if (overlay.species() >= 0) {
            // A plant or animal: coloured by where it comes on its range (red its outer third,
            // where there are barely any; green its heartland), as it's spawned
            int s = overlay.species();
            if (overlay.factor() == FactorName.FLORA) return new RegionalFactor(1f, d -> game.floraSpecies.floraRanges[s].share(game.floraSpecies.floraRaw(s, d)));
            return new RegionalFactor(1f, d -> game.organismManager.rangeShare(s, SphereMath.atAltitude(d, 0.0)));
        }
        RegionalFactor.Sampler measure = heatmapMeasure(overlay);
        if (measure == null) return null;
        float[] range = heatmapRange(overlay);
        float span = Math.max(1e-6f, range[1] - range[0]);
        return new RegionalFactor(1f, d -> Math.max(0f, Math.min(1f, (measure.sample(d) - range[0]) / span)));
    }

    /**
     * What a heatmap measures in a direction, in the units its key gives: a plant's or animal's
     * count in a chunk's area (100 by 100) there (as they're spawned: a plant's is its abundance
     * times its base; an animal's, see expectedPerChunk), the rocks' (count and typical width in
     * metres), the grass's height in centimetres, degrees Celsius, millimetres of rain a year; or
     * for the grass's abundance and wealth, their own 0 to 1. Null for the maps not on a scale.
     */
    private RegionalFactor.Sampler heatmapMeasure(MapOverlay overlay) {
        if (overlay.species() >= 0 && overlay.factor() == FactorName.FLORA) {
            int s = overlay.species();
            return d -> game.floraSpecies.floraRanges[s].density(game.floraSpecies.floraRaw(s, d));
        }
        if (overlay.species() >= 0) {
            int i = overlay.species();
            return d -> game.organismManager.expectedPerChunk(i, SphereMath.atAltitude(d, 0.0));
        }
        return switch (overlay.factor()) {
            case ROCKS -> d -> game.rocks().expectedPerChunk(SphereMath.atAltitude(d, 0.0));
            // (a unit being ten centimetres, and the size a radius)
            case ROCK_SIZE -> d -> game.rocks().typicalSizeAt(SphereMath.atAltitude(d, 0.0)) * 2f / PlayerBody.METRE;
            case GRASS_ABUNDANCE -> game.grassAbundanceFactor::evaluate;
            // (as the blades are given their heights, see the grass's gathering)
            case GRASS_HEIGHT -> d -> XenoGuesser_GLEventListener.bladeHeight(XenoGuesser_GLEventListener.grassShare(game.grassHeightSpan(), game.grassHeightFactor.evaluate(d))) * XenoGuesser_GLEventListener.GRASS_BLADE_HEIGHT / PlayerBody.METRE * 100f;
            case TEMPERATURE -> d -> -30f + 70f * game.regionalManager.temperatureMap.evaluate(d);
            case RAINFALL -> d -> game.regionalManager.rainfallMap.evaluate(d) * RAINFALL_FULL_MM;
            case WEALTH -> {
                if (wealthOverlay == null) {
                    wealthOverlay = new RegionalFactor(1f, d -> {
                        int nation = game.nationManager.getNationAt(d);
                        return nation <= 0 ? 0f : game.infraManager.areaRichness(nation, game.settlementManager.getUrbanness(d), SphereMath.atAltitude(d, 0.0));
                    });
                }
                yield wealthOverlay::evaluate;
            }
            default -> null;
        };
    }

    /** A value of a heatmap's measure for its key, with its units. */
    private String heatmapLabel(MapOverlay overlay, float value) {
        if (overlay.species() >= 0) return countLabel(value * chunksPerKm2()) + " /km\u00B2";
        return switch (overlay.factor()) {
            case ROCKS -> countLabel(value * chunksPerKm2()) + " /km\u00B2";
            case ROCK_SIZE -> String.format("%.1f m", value);
            case GRASS_HEIGHT -> Math.round(value) + " cm";
            case TEMPERATURE -> Math.round(value) + " \u00B0C";
            case RAINFALL -> Math.round(value) + " mm/yr";
            default -> String.format("%.2f", value);
        };
    }

    /** A heatmap's colours, lowest to highest. */
    private float[][] spectrumFor(MapOverlay overlay) {
        return switch (overlay.factor()) {
            case TEMPERATURE -> RegionalGenerationManager.TEMPERATURE_SPECTRUM;
            case RAINFALL -> RegionalGenerationManager.RAINFALL_SPECTRUM;
            case WEALTH -> WEALTH_SPECTRUM;
            case ROCKS, ROCK_SIZE -> ROCK_SPECTRUM;
            case GRASS_COLOUR -> grassColourSpectrum();
            default -> RANGE_SPECTRUM;
        };
    }

    /** The lowest and highest a factor reaches on the land the map shows, sampled across it. */
    float[] landSpan(RegionalFactor factor) {
        float[] span = { Float.MAX_VALUE, -Float.MAX_VALUE };
        forEachMapLand(28_800, d -> {
            float v = factor.evaluate(d);
            span[0] = Math.min(span[0], v);
            span[1] = Math.max(span[1], v);
        });
        return span[0] > span[1] ? new float[] { 0f, 1f } : span;
    }

    /**
     * Each of about count places spread evenly over the planet between the map's northern and
     * southern edges (MapProjection.CLIP_LATITUDE) that is on land, in turn.
     */
    private void forEachMapLand(int count, java.util.function.Consumer<GeodesicChunkGrid.Direction> visit) {
        double golden = Math.PI * (3 - Math.sqrt(5)), top = Math.sin(MapProjection.CLIP_LATITUDE);
        for (int i = 0; i < count; i++) {
            double y = top * (1 - 2 * (i + 0.5) / count), across = Math.sqrt(1 - y * y), longitude = golden * i;
            GeodesicChunkGrid.Direction d = new GeodesicChunkGrid.Direction(across * Math.cos(longitude), y, -across * Math.sin(longitude));
            if (TerrainMesh.getLayeredHeight(d, game.worldNoise) > game.seaLevelHeight) visit.accept(d);
        }
    }

    // The rainfall map's top (1) as rain over a year, in millimetres, for the map's key
    private static final float RAINFALL_FULL_MM = 3000f;

    // Each heatmap's lowest and highest values on the land (see heatmapRange)
    private final Map<MapOverlay, float[]> heatmapRanges = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A heatmap's lowest and highest values anywhere on the land the map shows, found by
     * looking all over it (kept, once worked out): its map runs from the one to the other, so
     * both ends of its key are somewhere on its map.
     */
    private float[] heatmapRange(MapOverlay overlay) {
        return heatmapRanges.computeIfAbsent(overlay, o -> {
            RegionalFactor.Sampler measure = heatmapMeasure(o);
            float[] span = { Float.MAX_VALUE, -Float.MAX_VALUE };
            forEachMapLand(90_000, d -> {
                float v = measure.sample(d);
                span[0] = Math.min(span[0], v);
                span[1] = Math.max(span[1], v);
            });
            return span[0] > span[1] ? new float[] { 0f, 1f } : span;
        });
    }

    // The rock map's colours: white where there are none, through grey to black where there are the most
    private static final float[][] ROCK_SPECTRUM = {
        { 1f, 1f, 1f }, { 0.67f, 0.67f, 0.67f }, { 0.33f, 0.33f, 0.33f }, { 0f, 0f, 0f }
    };

    /** How many chunks make a square kilometre (a metre being PlayerBody.METRE world units). */
    private float chunksPerKm2() {
        float across = 1000f * PlayerBody.METRE / game.PHYSICAL_CHUNK_SIZE;
        return across * across;
    }

    // The plant and animal maps' colours, none to the most
    private static final float[][] RANGE_SPECTRUM = {
        { 0.10f, 0.20f, 0.85f }, { 0.40f, 0.80f, 0.95f }, { 1.00f, 0.75f, 0.20f }, { 0.85f, 0.00f, 0.00f }
    };

    /** A count for the map's key, rounded sensibly (thousands as k). */
    private static String countLabel(float n) {
        if (n >= 10000f) return Math.round(n / 1000f) + "k";
        if (n >= 1000f) return String.format("%.1fk", n / 1000f);
        if (n >= 10f) return String.valueOf(Math.round(n));
        return String.format("%.1f", n);
    }
}
