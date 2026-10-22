# XenoGuesser

A Java 17+ (JOGL/OpenGL 3, Swing) geo-guessing game on a procedurally generated planet. Read
`docs/ARCHITECTURE.md` for where things live before exploring; it maps every file and subsystem,
the startup order, the per-frame render order and the two big classes' parts.

## Layout

- `src/` — all game code, one default package (plus `com.xenoguesser.math`). No subfolders:
  new classes go straight in `src/`. Package-private access across classes is normal here.
- `assets/shaders/*.txt` — GLSL (`vs_*`, `fs_*`); `assets/audio`, `assets/art`, `assets/icons`,
  `assets/text`; `models/` — the glyph model (PyTorch) and voice (ONNX).
- `checks/regression-fingerprints.txt` — the golden output of `RegressionCheck`.
- `tools/` — `run-checks.sh`, `frame-shots.sh` (see below), `record_message.py`.
- `GEODESIC_MIGRATION_PLAN.txt` (decisions `D1`–`D14`) and `MULTIPLAYER_INTEGRATION_PLAN.txt`
  (`M1`–`M56`): the codes comments cite. Read a decision there rather than guessing.

## Build and run (Windows; Git Bash for the scripts)

- Compile: `mvn -q -o compile` (drop `-o` if a dependency is missing). Output: `target/classes`.
- Play: `run.bat`; with developer switches: `run-dev.bat` (`-Dxenoguesser.dev=true`, see `Dev`).
- Packaged exe: `build-exe.bat` → `dist/`.
- Developer switches are read only through `Dev` (never `System.getProperty`), and only work
  when run from `target/classes`. Handy ones: `xenoguesser.seed=123`, `skipmenu`, `view=street|shop|bridge|river|herd|...`
  (see `RoundSpawner.spawnPlayerAtRandomLocation`), `spawnat=lat,lon`, `hudtest=...` (see
  `XenoGuesser.runHudTest`: `frameshot`, `quit`, `autoplay`...), `unlockall`.

## Code conventions (match them)

- Comments are prose in British English explaining *why* and *what it is*, in the voice of the
  surrounding code ("The houses round a town's middle stand taller..."), a one-line `/** */` on
  most methods and fields. Keep that density; don't add banner comments or `// NEW`.
- Big classes are a facade plus parts (see ARCHITECTURE): `InfrastructureManager` + `InfraPart`
  subclasses reach state through `infra`; `XenoGuesser_GLEventListener` + `GamePart` subclasses
  through `game`. Put new code in the part it belongs to; add a part rather than growing a facade.
  A part's field initialisers must not read the facade (parts are built before its fields).
- Use records for multi-value returns, not `Object[]`.
- Determinism: everything generated from the world seed (multiplayer PCs must make the same
  world). Use seeded `Random`s, sort anything collected from a `HashMap` before relying on its
  order, never use `System.identityHashCode` or time in generation. (The people simulation does
  use identity hashes, so it differs run to run: that's known and fine.)
- Threads: world generation, bird's-eye maps, poles (`utility-poles`), sign maps
  (`InfrastructureManager.SIGN_MAP_ARTIST`), terrain builders and Swing's EDT all touch shared
  state; `preparePoles` and `routes()` both lock on the `InfrastructureManager`. GL calls only on
  the GL thread.

## Checking a change

- Always: compile.
- Generation or gameplay logic (anything not purely GL): `tools/run-checks.sh` (or
  `tools/run-checks.sh regression` for just the fingerprints, ~2 min, needs ~10 GB). It rebuilds
  seed 123's world without GL and compares 36 fingerprints (rivers, textures, maps, every
  infrastructure cell round four places, lookups on grids, viewpoints, flora and grass cells,
  animals, and everything reachable from the listener) with `checks/regression-fingerprints.txt`.
  A refactor must change none. A deliberate change will: check the DIFFERS lines are the ones
  you meant (`java ... RegressionCheck breakdown` writes per-field hashes to
  `checks/regression-breakdown.txt` to find which), then `tools/run-checks.sh write`.
- `PlanningCheck` has 6 known failures, mostly because its two hard-coded town spots no longer
  hold the towns they did (a sea-distance sample, one sign board at 4.8 N, and at 67 N "0 of 0
  buildings", "no signs" and "vertices outside their batch's bounds"; "few vehicles parked");
  only new ones matter.
- Rendering (shaders, `render*`, GL set-up): `tools/frame-shots.sh <dir>` saves a frame per
  developer view; compare with frames from before the change (`CLASSES=<old classes dir>`).
  Frames vary slightly run to run (clouds, people), so compare by eye.

## Finding things cheaply

- Start from `docs/ARCHITECTURE.md`, then `grep -n` for the member; read only that range.
- Largest files (read by range, not whole): `XenoGuesser_GLEventListener` (~3.5k lines),
  `MapPanel` (~2.7k), `Inhabitants` (~2.3k), `XenoGuesser` (~1.9k), `InfrastructureManager`
  (~1.7k), `Rivers`, `PlayerBody`, `RoadNetworkBuilder`, `Landmark` (~1.4k each).
- Section markers inside classes are `// ===` banners (InfrastructureManager) or
  `// ------------------------------------------------------------------ topic` lines.
