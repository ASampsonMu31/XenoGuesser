---
name: update-tutorial
description: Update XenoGuesser's tutorial (Xenocorp's training programme) after the game changes, so every exercise's place, weather, objectives and pass check still suit the current world and mechanics. Use when the user asks to update, check, fix or extend the tutorial, or after changes to world generation, maps, gadgets or the HUD that the tutorial relies on.
---

# Updating the tutorial

The tutorial is a fixed sequence of exercises on one fixed planet. Its places are found
automatically on that planet, but the game's generation changes over time, so after any update
the places can stop suiting their exercises. This skill re-finds them, checks them against the
requirements below, and fixes whatever no longer fits.

## Where everything is

| What | Where |
|---|---|
| The planet's seed, the exercises in order (`Stage`), the rounds file's format | `src/TutorialPlan.java` |
| Each exercise's script: messages, objectives, gadgets and maps given, what's detected | `src/Tutorial.java` |
| What passes each exercise, the green wash, the explanation's `{words}` | `src/TutorialChecks.java` |
| How each exercise's place is looked for | `src/TutorialSites.java` |
| The finder (builds the planet without GL, writes the rounds file) | `src/TutorialFinder.java`, `tools/find-tutorial.sh` |
| The rounds found | `assets/text/tutorial_rounds.txt` (generated: don't hand-edit unless asked) |
| Everything Xenocorp says, the objectives' and results' words | `assets/text/tutorial_messages.txt` (the user's: edit only when asked, or when a mechanic changed) |
| The voiced messages | `assets/audio/tutorial/` (made by `python tools/record_tutorial.py`) |
| Hooks elsewhere | `RoundSpawner.spawnPlayerAtRandomLocation` (the spawn, sky, hour, season), `MapPanel` (`setJudge`, `drawVerdict`, no clock), `XenoGuesser.startTutorial`/`afterResults`/`enterGame`, `MainMenu` (Tutorial button), `HudRenderer` (objectives panel), `MeasurementLog` (marker pin, readings), `IdentificationLens` (identified), `MapOverlays.ownMapsOnly`, `LoadingScreen` (`msg.pod`) |

## Steps

1. **Compile**: `mvn -q -o compile`.
2. **Re-find the places**: `tools/find-tutorial.sh` (about 2 minutes, ~10 GB). It prints each
   exercise's place and the share of the land that would pass it, and exits 1 if any exercise
   found nowhere suitable. Notes for failed searches say which condition ran out
   (e.g. "near it 812, on the contour 790, level 12...").
3. **Check every line against the requirements below.** Where a search finds nothing, or
   something that breaks a requirement, change its search in `TutorialSites` (loosen the least
   important condition first, or look in a smarter way) and run the finder again. If the world
   simply can't give an exercise what it needs, a different `TutorialPlan.SEED` is the last
   resort: tell the user before changing it (it changes every place, and the planet's name).
4. **Check the mechanics still match** (read the code each relies on; fix the tutorial, not
   the game, unless the user says otherwise):
   - gadgets: `XenoGuesser_GLEventListener.ITEMS` order (compass, thermometer, altimeter, kit),
     `ownItemsOnly`, keys 1-4 (messages say "press 1" because each early exercise has one gadget);
   - maps: `FactorName`, `MapPanel.Layer`, `MapOverlays.PICTURE_MAPS`, `factorFor`,
     `heatmapMeasure`, `designKey`, `soilColourAt`. A new map or layer needs an exercise (or a
     place in an existing one): add a `Stage`, its `giveFor`/`mapObjectives`/`mapShown` cases, a
     `TutorialChecks.rule` case, a `TutorialSites` search, and its `msg./obj./explain./fail.`
     entries in the messages file;
   - temperature: `XenoGuesser_GLEventListener.temperatureAt` (the temperature exercise's hour,
     `TutorialSites.AVERAGE_HOUR`, is when the daily term is zero; the declination 0 is the
     equinox: re-derive both if the formula changed);
   - the altimeter (`PlayerBody.setAltitude`), contours every `BirdsEyeMaps.CONTOUR_METRES`;
   - the compass and the sun (`Camera.getYaw`: degrees from east towards south;
     `Planet.sunDirection`);
   - identification (`IdentificationLens.drawLensFinds` calls `tutorial.identified`);
   - the round's randomness: `RoundSpawner` must take the tutorial's spawn, hour, season and
     weather, and `Tutorial.spawn` must fix the creatures' salt. Anything newly random per round
     (a new weather effect, say) must be fixed for the tutorial too.
5. **Words**: if a mechanic or key changed, update the affected entries in
   `assets/text/tutorial_messages.txt`, keeping the tone (below). Then
   `python tools/record_tutorial.py` re-records only the changed messages.
6. **Run the checks**: `tools/run-checks.sh regression` must show no new DIFFERS (the tutorial
   changes nothing on seed 123's world; the whole-object "map" and "listener" fingerprints do
   change whenever the tutorial's own fields change, which is fine: check with `breakdown` that
   only tutorial fields differ). Then look at the exercises in the game: developer switches
   `-Dxenoguesser.skipmenu=tutorial -Dxenoguesser.tutorialstage=<0-12>` go straight into one, and
   with `-Dxenoguesser.hudtest=screen,guess,quit -Dxenoguesser.screen=<file prefix>
   -Dxenoguesser.screenat=12000,36000 -Dxenoguesser.guessat=answer -Dxenoguesser.guessdelay=28000
   -Dxenoguesser.quitdelay=40000` take screenshots of the exercise and of a passing guess's results
   (the green wash and explanation); without `guessat` the guess is the map's middle (normally a
   fail: "Try again"). On a PC short of memory the world can take over ten minutes to load.
7. Tell the user what moved, what was loosened, and anything that still doesn't fully meet a
   requirement.

## The requirements (the user's, formalised)

### The tutorial as a whole
- R1. Always the same planet (`TutorialPlan.SEED`), exercises in the same order at the same
  places. Everything that can vary is fixed per exercise: the pod's place and heading, the hour,
  the season (sun's declination), the clouds and rain (`wetness`, and `newDay` from a fixed
  Random), the creatures (`organismManager.clear` with a fixed salt). Trying an exercise again
  gives it exactly as before. (The townsfolk simulation is known to differ run to run: fine.)
- R2. The planet is still generated when the tutorial starts (the game has no saved worlds),
  behind the landing pod as usual; the pod says `msg.pod`, not the normal deployment message.
  There is no shop, no quarters, no targets, no round clock.
- R3. Xenocorp's messages are voiced in the Bryce voice, *without* the chime, the "You have one
  new message from Xenocorp" opening or the sign-off. Their words live in
  `assets/text/tutorial_messages.txt` for the user to edit.
- R4. Tone: Xenocorp's usual one, official and corporate, dry, quietly snarky and faintly
  threatening about budgets, insurance and the airlock; British English.
- R5. Objectives are listed on the HUD's right, white until done, then gold; above them a bar
  shows how far through the whole programme the player is.
- R6. Passed: the results show, in green on the globe, every place that would have passed, and
  a concise explanation at the bottom of how it could have been worked out (`explain.*`); the
  button goes on ("Next exercise", "Finish training" at the end). Failed: "Try again", which
  restarts the exercise (`fail.*` says what to look at again).

### The exercises, in order
1. **Sun (hemisphere)**. Somewhere cold (coldest fifth of the land), far from roads and houses,
   clear sky, sun clearly in the north or south (latitude above ~22 degrees). A marker pin
   (like the readings' pins) is put in the ground 10 m ahead of the pod's stairs; then the
   welcome; then walk to it (WASD) and turn a full circle on it (mouse). The turn's direction is
   detected, and the next message says most trainees turn the other way and the player is odd.
   Then a compass is given at once: face the sun with it (detected: compass out, view within
   12 degrees of the sun for 0.8 s). Then expanding the map (Tab), zooming and guessing are
   explained. Pass: the right hemisphere (the whole of it goes green). Trying again skips the
   walking and turning.
2. **Coast**. Very rural, 30-70 m from the sea, on a very straight coast (the coast within 1.5 km
   lying along one line), compass only, no heatmaps. A marker on the shore ahead; reaching it
   removes it; then line the compass up along the coast (detected both ways along it). Pass: a
   guess no further from a coast than four times the pod's distance from the sea (out to sea,
   twice), where that coast (measured over 1.5 km) runs within 20 degrees of the site's.
3. **Temperature**. Near the equator, low and level, thermometer only, temperature heatmap only.
   At the equinox and the hour when the day's warming is zero, so the thermometer reads what
   the heatmap shows (within 0.5 C). Pass: the heatmap at the guess within 2.5 C of the reading;
   the matching land should be a modest share (4-25%) of the planet.
4. **Altitude**. Scenic, on a mountainside with a view downhill, exactly on a 10 m contour about
   75% of the planet's highest ground; altimeter only, contours only. Pass: ground within 8 m of
   the reading.
5. **Plants and animals**. One plant and one animal plentiful here, no other species about;
   identification kit only. First identify 5 plants and 5 animals; then their two maps are given.
   The pair is chosen so that only one region of the planet has plenty of both. Pass: in it.
6. **The other maps**, one exercise each, in the same format (intro message giving the maps,
   objectives to show each, then guess), each at a fitting, good-looking place where the map
   tells the place apart from much of the planet (2-25% of the land passes):
   rainfall + rivers (a wet place, raining), soil colour + rocks (rocky), grass (lush, tall),
   wealth + roads/buildings/shops (a rich town), flags + languages (a town), bollards + road lines
   + utility poles (on a road with them), architecture (among houses), landmarks (in view of one).
   Every map the game has must be covered by some exercise.
