# The Steam trailer

`trailer/index.html` plays the trailer: game footage cut together with on-screen words, Xenocorp's
voice lines (from the game's own messages) and a score made as it plays. Every picture in it is
the game itself, filmed or recorded by the tools here. The edit is `trailer/scenes.js`: one entry
per scene, each a module that can be cut, reordered or re-timed on its own.

To watch it, run `tools/trailer/serve.bat` (the page needs a web server for Export), then press
play. The bar under the picture can be dragged through the cut; Space plays and pauses.

Below it is the sequencer: a library of every picture, title and voice line, and three tracks
the cut is built on. Drag from the library onto a track; pictures play one after another and
can be dragged along their track to reorder them, titles and voice lines play at the time
they're dropped and can be dragged sideways. The cut is saved in the browser as it's edited
(**Back to the original cut** returns to `scenes.js`'s). **Export video** plays the cut through
once and saves it as a video file (MP4 where the browser can, otherwise WebM) to upload to
Steam. The page's code: `engine.js` draws and scores a cut, `editor.js` is the sequence, the
transport and the sequencer.

## When the game changes

Each clip comes from one of three sources. Re-make only the clips a change affects, then reload
the page.

| Clips | Made by | Remake with |
|---|---|---|
| Landscapes, landmarks, towns, creatures, gameplay with the HUD and gadgets (`pod-*`, `spire`, `play-*`...) | `shots.txt`, filmed a frame at a time by `TrailerDirector` | `tools/trailer/film.sh tools/trailer/shots.txt <frames dir> 123 [shot,shot]` |
| Mountains, the island, flora, creatures (`peaks-*`, `atoll-*`, `plant-*`, `beast-*`) | `shots-more.txt`, filmed the same way | `tools/trailer/film.sh tools/trailer/shots-more.txt <frames dir> 123 [shot,shot]` |
| The spinning planets (`trailer/globes/globes.js`) | `TrailerGlobes`: each seed's globe as its landing pod sees it, and its name | `java -cp "target/classes;$(cat target/classpath.txt)" TrailerGlobes trailer/globes 123 7 42 ...` |
| The other planets (`world-1`..`world-4`) | `worlds.txt`, one shot per seed | `tools/trailer/film.sh tools/trailer/worlds.txt <frames dir> <seed>`, then name the clip `world-N` |
| The shop, the map, guessing and results (`shop-*`, `map-guess`) | `gameplay.txt`, played in real time with the mouse and keys | `python tools/trailer/record.py tools/trailer/gameplay.txt <frames dir> -- -Dxenoguesser.hudtest=firstshop` |
| Multiplayer (`mp-*`) | `multiplayer.txt`, two game windows on this PC | see the switches at the top of `multiplayer.txt` |

Then `python tools/trailer/encode.py <frames dir> trailer/clips [shot ...]` makes the clips
(ffmpeg with `h264_mf`, found on the PATH or in Anaconda). The voice lines are re-cut with
`python tools/trailer/voice.py` if the messages change.

While filming or recording, the game's window opens; leave the PC alone until it closes (the
recordings drive the real mouse and keyboard, and type into whatever window is in front if the
game's isn't). Don't compile while a film runs: the running game loads classes as it goes, and
finds them half rebuilt.

## How the filming works

`-Dxenoguesser.trailer=<shot list>` (developer switches only) hands the game to `TrailerDirector`.
For each shot it finds the place (a landmark, a developer view, a landing site), sets the sun and
weather, loads everything along the camera's path, then films. The world's clock steps exactly
1/30 s per frame (`GameClock.filmStep`), so however slowly the PC draws, the people, animals,
clouds and water move at their true speed and every camera move is smooth. A `move=play` shot
uses the player's own controls instead (keys held, items taken out, the view turned by a damped
spring with a slight hand tremor), so the walking, head bob and HUD are the game's own.

A shot's settings are described at the top of `shots.txt`.
