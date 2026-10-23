// The trailer's edit, read by index.html. Scenes play in order; each is a module that can be
// cut (enabled: false, or untick it on the page) or reordered without touching the others.
//
//   id      the scene's name (shown in the page's list)
//   clip    a file in clips/ (no .mp4), filmed from the game by tools/trailer (see README.md)
//   from    seconds into the clip to start; rate: playback speed (1 = real time)
//   dur     seconds on screen; xfade: seconds of crossfade from the scene before (0 = hard cut)
//   zoom    [start, end] slow push on the picture; pan: [x, y] drift while zoomed (-1..1)
//   energy  0..1, how hard the score drives under it; hit: a "braam" as it starts (or its size)
//   riser   seconds of rising whoosh into its start; flash: a white flash on the cut
//   text    words on screen: { text, style: title|sub, at, dur, y, size }, in the middle of the
//           screen unless y says otherwise (only where the game's own words fill the middle)
//   vo      a Xenocorp line (trailer/audio, cut by tools/trailer/voice.py) and its subtitle,
//           shown only with the page's Subtitles on: { line, at, caption }
//   logo    the XenoGuesser title card over the clip, with tagline and cta
const TRAILER = {
  bpm: 100,
  letterbox: 0,
  scenes: [
    { act: "The landing" },
    { id: "landing", clip: "pod-reveal", dur: 6.2, zoom: [1.0, 1.03], energy: 0.1,
      vo: { line: "vo-unknown", at: 1.4, caption: "Due to magnetic interference, your landing site is unknown." } },
    { id: "your-task", clip: "pod-orbit", from: 1.0, dur: 3.6, xfade: 0.6, energy: 0.15,
      vo: { line: "vo-task", at: 0.3, caption: "Your task is to identify it." } },
    { id: "where-are-you", clip: "play-walk", dur: 5.0, energy: 0.3, hit: 1, flash: true,
      text: [{ text: "WHERE ARE YOU?", at: 0.4, dur: 3.6 }] },

    { act: "Endless worlds" },
    { id: "vista", clip: "spit", from: 0.5, dur: 3.2, energy: 0.45, riser: 1.2, hit: 0.8,
      text: [{ text: "EVERY PLANET IS BUILT FROM NOTHING", style: "sub", at: 0.3, dur: 2.8 }] },
    { id: "peaks", clip: "peaks-1", from: 0.6, dur: 3.0, xfade: 0.4, energy: 0.5,
      text: [{ text: "SOARING MOUNTAINS", style: "sub", at: 0.4, dur: 5.2 }] },
    { id: "crest", clip: "peaks-2", from: 2.2, dur: 3.0, xfade: 0.4, energy: 0.5 },
    { id: "island", clip: "atoll-low", from: 2.6, dur: 3.0, xfade: 0.4, energy: 0.5,
      text: [{ text: "LONELY ISLANDS", style: "sub", at: 0.3, dur: 2.4 }] },
    { id: "spire", clip: "spire", from: 1, dur: 2.4, xfade: 0.4, energy: 0.5,
      text: [{ text: "ITS OWN LANDS. ITS OWN NATIONS. ITS OWN LIFE.", style: "sub", at: 0.2, dur: 6.0 }] },
    { id: "stones", clip: "stones", from: 1, dur: 2.4, xfade: 0.4, energy: 0.5 },
    { id: "ruin", clip: "ruin", from: 1, dur: 2.4, xfade: 0.4, energy: 0.5 },
    // (the planets as their landing pods see them, from globes/globes.js: one per seed)
    { id: "planets", globes: 12, dur: 6.4, xfade: 0.4, energy: 0.55, hit: 0.6,
      text: [{ text: "NO TWO WORLDS ALIKE", at: 1.6, dur: 2.4, y: 530, size: 80 },
             { text: "AN ENDLESS SUPPLY OF ALIEN PLANETS", style: "sub", at: 3.7, dur: 2.7, y: 530 }] },
    { id: "world-1", clip: "world-1", from: 0.5, dur: 1.4, energy: 0.6, flash: true, hit: 0.5 },
    { id: "world-2", clip: "world-2", from: 0.0, dur: 1.4, energy: 0.6, flash: true },
    { id: "world-4", clip: "world-4", from: 1.3, dur: 1.6, energy: 0.6, flash: true },

    { act: "Read the clues" },
    { id: "signs", clip: "poles", from: 0.6, dur: 3.4, energy: 0.6, hit: 0.7,
      text: [{ text: "READ THE CLUES", at: 0.3, dur: 3.0 },
             { text: "ALIEN SCRIPTS", style: "sub", at: 1.2, dur: 2.0, y: 650 }] },
    { id: "ornate", clip: "ornate", from: 0.8, dur: 2.4, xfade: 0.3, energy: 0.6,
      text: [{ text: "ARCHITECTURE", style: "sub", at: 0.2, dur: 1.7 }] },
    { id: "bollards", clip: "bollards", from: 0.5, dur: 1.8, xfade: 0.3, energy: 0.6,
      text: [{ text: "ROADSIDE DETAILS", style: "sub", at: 0.2, dur: 1.5 }] },
    { id: "shopfront", clip: "shopfront", from: 1.6, dur: 2.4, xfade: 0.3, energy: 0.6,
      text: [{ text: "LOCAL GOODS", style: "sub", at: 0.2, dur: 1.7 }] },
    { id: "flora", clip: "plant-1", from: 0.3, dur: 2.6, xfade: 0.3, energy: 0.6,
      text: [{ text: "ALIEN FLORA", style: "sub", at: 0.2, dur: 2.3 }] },
    { id: "wildlife", clip: "beast-2", from: 1.2, dur: 3.0, xfade: 0.3, energy: 0.6,
      text: [{ text: "AND THE WILDLIFE", style: "sub", at: 0.2, dur: 2.6 }] },
    { id: "identify", clip: "identify", from: 1.0, dur: 2.8, xfade: 0.3, energy: 0.62,
      text: [{ text: "IDENTIFY WHAT YOU FIND", style: "sub", at: 0.3, dur: 2.4 }] },
    // (the map's heatmap of where one species lives, picked from the list: tools/trailer/heatmap.txt)
    { id: "heatmap", clip: "heat-show", from: 14.2, dur: 5.6, energy: 0.62, zoom: [1.5, 1.56], pan: [-1, -0.35],
      text: [{ text: "THEN LINE IT UP WITH YOUR MAPS", style: "sub", at: 0.6, dur: 4.6 }] },
    { id: "guess", clip: "map-guess", from: 0.6, dur: 5.8, energy: 0.65, hit: 0.7,
      text: [{ text: "PIN YOUR GUESS ON THE MAP", style: "sub", at: 0.2, dur: 3.0 }] },
    { id: "results", clip: "map-guess", from: 7.4, dur: 4.4, xfade: 0.3, energy: 0.65, zoom: [1.3, 1.36], pan: [0, -0.5],
      text: [{ text: "THE CLOSER YOU ARE, THE MORE YOU EARN", style: "sub", at: 0.3, dur: 3.6, y: 1010 }] },

    { act: "Gear up" },
    { id: "compass", clip: "play-compass", from: 0.4, dur: 2.6, energy: 0.65, hit: 0.6,
      text: [{ text: "USE YOUR GADGETS", at: 0.2, dur: 2.3 }] },
    { id: "kit", clip: "play-kit", from: 0.6, dur: 3.0, xfade: 0.3, energy: 0.65 },
    { id: "thermometer", clip: "play-thermo", from: 0.6, dur: 2.0, xfade: 0.3, energy: 0.65 },
    { id: "altimeter", clip: "play-altimeter", enabled: false, from: 0.6, dur: 1.9, xfade: 0.3, energy: 0.65 },
    { id: "shop", clip: "shop-after", from: 5.4, dur: 4.2, energy: 0.6, hit: 0.6,
      text: [{ text: "UNLOCK NEW MAPS", at: 0.2, dur: 2.0 },
             { text: "SPEND YOUR EARNINGS", style: "sub", at: 0.6, dur: 3.2, y: 650 }],
      vo: { line: "vo-equipment", at: 0.1, caption: "All further equipment must be purchased from your own earnings." } },

    { act: "Xenocorp" },
    { id: "targets", clip: "tower", dur: 4.6, energy: 0.3, silence: 0.8, hit: 0.9,
      text: [{ text: "MEET YOUR TARGETS", at: 0.3, dur: 4.0 }],
      vo: { line: "vo-targets", at: 0.2, caption: "Only employees that successfully meet their assigned productivity targets will be retained." } },
    { id: "airlock", clip: "shore", dur: 3.4, xfade: 0.5, energy: 0.3,
      vo: { line: "vo-airlock", at: 0.1, caption: "Departing employees will instead be released via the airlock." } },
    { id: "painless", clip: "lake", dur: 3.4, xfade: 0.5, energy: 0.25,
      vo: { line: "vo-painless", at: 0.2, caption: "We are advised that the procedure is painless." } },

    { act: "Multiplayer" },
    { id: "rival", clip: "mp-pod", from: 6.2, dur: 4.4, energy: 0.7, hit: 1, flash: true,
      text: [{ text: "1 V 1 MULTIPLAYER", at: 0.3, dur: 3.8 }],
      vo: { line: "vo-paired", at: 0.3, caption: "You have been paired with Employee 119-A." } },
    { id: "rival-results", clip: "mp-results", from: 13.8, dur: 5.0, xfade: 0.3, energy: 0.75, zoom: [1.4, 1.48], pan: [0, -0.7],
      text: [{ text: "SAME WORLD. SAME ROUNDS. ONE SURVIVOR.", style: "sub", at: 0.2, dur: 3.6, y: 1010 }],
      vo: { line: "vo-better", at: 0.2, caption: "Only the better-performing employee will be retained." } },

    { act: "Finale" },
    { id: "rush-1", clip: "spire", from: 3, dur: 1.1, energy: 0.9, hit: 0.6, flash: true },
    { id: "rush-2", clip: "peaks-3", from: 3.5, dur: 1.0, energy: 0.9, flash: true },
    { id: "rush-3", clip: "bridge", from: 1, dur: 1.0, energy: 0.9, flash: true },
    { id: "rush-4", clip: "lake", from: 2, dur: 1.0, energy: 0.9, flash: true },
    { id: "rush-5", clip: "ruin", from: 1, dur: 1.0, energy: 0.9, flash: true },
    { id: "rush-6", clip: "play-sprint", from: 1.2, dur: 1.4, energy: 0.95, flash: true },
    { id: "title", clip: "pod-orbit", from: 0, dur: 7.0, energy: 0.0, riser: 1.6, hit: 1.2, logo: true, fadeOut: 1.2,
      tagline: "Find your place in the universe", cta: "Wishlist now on Steam",
      vo: { line: "vo-slogan", at: 2.2, who: "", caption: "" } },
  ],
};
