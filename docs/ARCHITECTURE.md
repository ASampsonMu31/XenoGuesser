# XenoGuesser architecture

A map of the code for finding things fast. CLAUDE.md has the rules and commands; this file says
where everything lives and how the pieces talk. All Java is in `src/`, in the default package
(except `com.xenoguesser.math`), so every class can use every other's package-private members.

## The game in one paragraph

A procedurally generated planet (seed per game) with nations, towns, roads, houses, plants,
animals and people. Each round the player is dropped from a landing pod somewhere on it, looks
around (walking or not, see `GameMode`), then guesses where they are on the map. Points buy maps
(heatmaps, layers, maps of pictures) in the shop between rounds. Quarters, targets, Xenocorp's
voiced messages and achievements frame a "year". Multiplayer (two players, Steam P2P or two local
windows) plays the same world and rounds, first to win 10 rounds (the closer guess wins each).
Players have a username and suit colour (`Profile`), said in Xenocorp's messages.

## Startup, in order

| Step | Where | Thread |
|---|---|---|
| Window, main menu | `XenoGuesser.main` → `XenoGuesser` ctor → `MainMenu` | Swing EDT |
| Play pressed: pod opens, loading screen | `XenoGuesser.startGame` → `openPod`, `PodSequence`, `LoadingScreen`, `LoadingProgress` | EDT |
| Writing systems (ML glyphs, slow) | `WritingSystems.generate` → `GlyphGenerator` (DJL/PyTorch, `models/`) | own |
| Terrain, sea level, map join | `XenoGuesser.generateWorld`: `PerlinNoise`, `TerrainMesh.getLayeredHeight`, `XenoGuesser_GLEventListener.precalculateSeaLevel`, `MapProjection.chooseJoin` | `world-generation` |
| Climate, rivers | `RegionalGenerationManager`, `Rivers.generate` | world-generation |
| World layout | `new XenoGuesser_GLEventListener(...)` (constructor): regional factors, `FloraSpecies.createFloraSpecies`, `NationGenerationManager`, `SettlementManager`, `InfrastructureManager` (+ `prepareRoadNetwork`), `Inhabitants`, `OrganismManager`, landmark groves, collision sources, flora/grass `CellStream`s, then `MapOverlays.startBirdsEyeMapRendering` | world-generation (+ `birds-eye-map-renderer`, `utility-poles`, `sign-map-artist`) |
| Textures | `WorldArtGenerator.generate` (+ `InfrastructureManager.textureJobs`) → files in `RunFiles.WORLD_DIR` | `world-art` pool |
| Map panel, game window | `new MapPanel(...)`, `XenoGuesser.buildGameWindow` | EDT |
| GL set-up, a step per frame | `XenoGuesser_GLEventListener.display` → `advanceLoading` → `coreSteps`, flora models, `finishSteps`, surroundings built, maps ready, rocks | GL (JOGL `FPSAnimator`) |
| Round starts | `RoundSpawner.spawnPlayerAtRandomLocation`, `XenoGuesser.onWorldReady`/`enterGame` | GL / EDT |

Same seed ⇒ same world on both multiplayer PCs: everything is generated from seeded `Random`s
(never unseeded ones in generation), and lists built from hash maps are sorted before use.

## Each frame (GL thread)

`XenoGuesser_GLEventListener.display` handles loading, round building behind the results,
teleporting, then `renderScene` → `render`:

1. `countFrameRate`, `PlayerMotion.movePlayer` (keys, oxygen, collision, ground or deck
   underfoot, swimming, jumping, head bob).
2. Sun, chunk streaming (`updateVisibleChunks`: terrain patch, `wantInfrastructureAround` cells
   from `InfrastructureManager.generateForCell`, `updateVegetation` trees/grass).
3. Daylight and sky colour; `OrganismManager.update`, `Inhabitants.update`.
4. Passes: shadows (`renderShadows`, `ShadowMaps`) → depth pre-pass → sky (`SphereSkyRenderer`)
   → terrain → infrastructure (`renderGeodesicInfrastructure`, `renderSigns`) → flora → creatures
   and people → `renderGrass` → rocks (`RockField`) → landing pod → partner → clouds
   (`SphereWeather`) → window glass → sea → rivers (`RiverRenderer`) → rain → lens depth →
   `renderPlayerBody` (`PlayerBody`, in the camera's local frame).
5. Then `OxygenBlur`, `HudRenderer.drawHud`, `SpeciesPictures.advancePreviews`.

Everything is drawn camera-relative in planet-centred coordinates (`SpherePosition`, doubles);
shaders are in `assets/shaders/*.txt` (`vs_*`/`fs_*`, loaded by `Shader`).

## The two big classes and their parts

Both are split into a facade that holds the state plus "parts", one class per concern. A part
gets the facade through one field and is otherwise ordinary code; parts call each other through
the facade (`infra.bridges.bridgeDeckAt(...)`, `game.mapOverlays.chooseGradient(...)`).

### `InfrastructureManager` (roads, buildings, signs) — facade, state, public lookups

Parts extend `InfraPart` (field `infra`), which extends `InfraGeometry` (shared frame helpers,
mesh primitives, float[3] vector maths, shared constants). The manager also extends
`InfraGeometry`.

| Part / type | What it does |
|---|---|
| `InfrastructureManager` | State (segments, houses, shops, `CellIndex` lookups), constructor (per-nation styles), `prepareRoadNetwork` (the order everything is planned in), spatial queries (`isRoadLocation`, `walkingSurfaceY`, `isBuildingLocation`...), doorways/routes/kerbs for people and traffic, landing sites, `generateForCell` (bakes one geodesic cell into `NationBatch`es and signs), obstacles, map pictures |
| `HousePlacement` | Houses laid out along roads (dead-end houses, plots, slopes, rivers, forms, wall variants, fences, driveways) |
| `HouseBuilder` / `HouseDressing` | A house's mesh (walls, roof, windows, posters, flags) / its ornament by architecture |
| `Gardens` | Driveways, fences, private ground |
| `ShopBuilder` | Which buildings are shops, shop fronts, goods |
| `RoadGeometry` | Deck heights, roads as drawn, asphalt/track meshes, supports, lines, cul-de-sacs, guard rails |
| `RoadFurniture` | Bollards, utility poles and wires |
| `Bridges` | Finding bridges, their decks (`Bridges.Deck`) and plank/beam/arch meshes |
| `BorderCrossings` | Fenced hostile borders, booths, guards, traffic lights (`borders()`) |
| `RoadsideSigns` | House and roadside signs, their text and pictures (`SignMap`) |
| `InfraMaterials` | Materials by part name (`"wall_variant3"`, `"orn@2"`, `"bollard#1"`...) |
| `InfraViewpoints` | Developer views (`viewpoints()`) |
| Data | `RoadSegment`, `House`, `NationBatch`, `Bridge`, `BridgeKind`, `Crossing`, `HouseWall`, `RoofOutline`; public nested `Shop`, `Doorway`, `Kerb`, `TrafficLight`, `Guard`, `ArchitecturePicture` |

### `XenoGuesser_GLEventListener` (the running 3D game) — facade, GL state, loading, render

Parts extend `GamePart` (field `game`).

| Part / type | What it does |
|---|---|
| `XenoGuesser_GLEventListener` | World set-up (constructor), GL init/loading steps, terrain/vegetation/infrastructure streaming, `render`, flora/grass building, rivers/sea, round loading, misc. game API used by `XenoGuesser` |
| `RoundSpawner` | Where each round starts, prefetching the next, time of day, dev views |
| `PlayerMotion` | Movement, sprinting, jumping, head bob, oxygen |
| `MapOverlays` | Bird's-eye map + layers, heatmaps, maps of pictures, owned maps, shop offers |
| `SpeciesPictures` | Portraits/turning previews of plants and animals |
| `IdentificationLens` | The magnifying glass's finds |
| `HudRenderer` | The HUD over the 3D view |
| `SnowCover` | Snow on the ground |
| `FloraSpecies` | Plant species generation and ranges (`SpeciesConfig`, `SphereFlora`) |
| `DevShots` | Saved frames, menu picture, sign/vehicle shots, dev viewpoints |
| `TrailerDirector` | Films the Steam trailer's shots (`-Dxenoguesser.trailer`): finds each place, sets the day, steps the clock a frame at a time and saves frames; made only when filming (see `tools/trailer/README.md`) |
| Data | `FactorName` (map overlays' factors), `MapOverlay`, `MapPicture`, `NationLabel` |

## Everything else, by area

- **Window and flow**: `XenoGuesser` (window, menus, rounds' flow between map, results, shop,
  quarters, messages; `runHudTest` = dev test runs), `MainMenu`, `SingleplayerPanel` (mode and map, after Play Singleplayer), `Expedition` (the game being played, said and drawn alike everywhere; matchmaking's ticks), `SettingsMenu`, `ControlsPanel`,
  `ShopPanel`, `QuarterPanel`, `GameHUD`, `HudStyle`, `HudOverlay`, `MessageStrip`/`MessageTicker`,
  `WindowTitleBar`/`WindowResizer`, `Achievements`, `GameSettings`, `GameMode`, `MapKind`, `GameClock`.
- **Map**: `MapPanel` (minimap/large map, guessing, results; `Layer`), `MapLayersPanel`, `MapMarkers`,
  `MapTiles`, `MapProjection`, `MapColours`, `BirdsEyeMaps` (layer images), `RevealGlobe` (results
  globe), `PreviewCard`.
- **Sphere toolkit**: `Planet`, `SpherePosition`, `SphereMath`, `SphereFrame`, `GeodesicChunkGrid`,
  `CellIndex`, `CellStream`, `SphereRaster`, `GeodesicCollision`, `Viewpoint`, `Camera`.
- **Terrain, water, sky, weather**: `TerrainMesh`, `PerlinNoise`, `Rivers`, `RiverRenderer`,
  `RegionalGenerationManager`, `RegionalFactor`, `SpeciesRange`, `SphereSkyRenderer`,
  `SphereWeather`, `RainSound`, `ShadowMaps`, `RockField`, `WorldPalette`, `ProceduralTextures`,
  `TileableNoise`, `WorldArtGenerator`, `PlanetGlobeTexture`.
- **Plants**: `Flora` (a species' branching meshes and LODs, `Flora.Design`), `FloraForms` (the non-tree forms), `FloraSpecies`.
- **Animals**: `OrganismManager`, `OrganismSpecies`, `OrganismParts`, `OrganismMesh`.
- **People and traffic**: `Inhabitants` (people, guards, doors, traffic lights), `Vehicles`.
- **Nations and towns**: `NationGenerationManager`, `NationKinship`, `SettlementManager`,
  `RoadNetworkBuilder`, `RoadPath`, `RoadRoutes`, `Landmarks`/`Landmark`, styles per nation:
  `BuildingStyle`, `ArchitectureStyle`, `ArchitectureTextures`, `FenceStyle`, `GuardRailStyle`,
  `BollardStyle`, `UtilityPoleStyle`, `RoadLineStyle`, `RegionalDesigns`, `FlagDesigner`,
  `NationScripts`, `WritingSystems`, `GlyphGenerator`, `Products`, `PackagingArt`, `PlanetName`.
- **Player and pod**: `PlayerBody`, `LandingPod`, `PodSequence`, `PodTimeline`, `PodInterior`,
  `PodFurniture`, `PodChair`, `PodPhysics`, `PodTerminal`, `PodSpaceView`, `PodSounds`,
  `LoadingScreen`, `LoadingArt`, `LoadingProgress`, `TransmissionMessage` (with the players' names
  spliced in), `PlanetVoice`, `OxygenBlur`.
- **Player profile**: `Profile` (username, suit colour, the name's recording), `ProfilePanel`,
  `NameCheck` (swearing by spelling, sound and spoonerism), `Pronunciation` (a name split into words,
  said from `assets/text/word_sounds.txt.gz`, made by `tools/word_sounds.py`), `SuitColour`.
- **Multiplayer**: `Multiplayer` (session, messages), `MultiplayerTransport`, `SteamTransport`,
  `LocalTransport`, `MultiplayerPanel` (friends' codes, and matchmaking's casual and ranked queues), `MultiplayerScore`
  (rounds won), `Rating` (ranked Elo), `RemotePlayer`, `TownsfolkSync` (the host's
  people and vehicles sent to the joiner's PC).
- **GL plumbing**: `Shader`, `Mesh`, `MeshBuilder`, `MeshData`, `Model`, `Renderer`, `Material`,
  `Light`, `TextureLibrary`, `Offscreen`, `SoftwareRenderer`, `Affine`, `SphereRender`,
  `com.xenoguesser.math.*`, `BuildingMeshes`, `InfrastructureObject` (a cell's batch or a sign).
- **Runtime**: `Dev` (developer switches), `RunFiles`, `GamePaths`, `MemoryBudget`, `CrashLog`,
  `BackgroundWorkers`.
- **Checks** (main classes, not part of the game): `SphereToolkitCheck`, `PlanningCheck`,
  `MultiplayerCheck`, `RegressionCheck` + `Fingerprint`.

## Codes in comments

- `D1`–`D14`: design decisions for the sphere world, in `GEODESIC_MIGRATION_PLAN.txt`.
- `M1`–`M58`: multiplayer and later feature decisions, in `MULTIPLAYER_INTEGRATION_PLAN.txt`.
