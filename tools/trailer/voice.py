"""Cuts the trailer's voice lines out of the game's own Xenocorp messages (assets/audio), by the
word timings kept beside each one, into trailer/audio/<line>.wav. Each line is a phrase of one
message, cut at the quietest moment near where its first word starts and the next word after it
starts, with a few milliseconds' fade at each end. Change LINES and run it again to recut.

Usage:  python tools/trailer/voice.py
"""
import array
import os
import wave

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
OUT = os.path.join(ROOT, "trailer", "audio")

# line id: (message, the phrase as written in it)
LINES = {
    "vo-unknown": ("singleplayer", "Due to magnetic interference, your landing site is unknown."),
    "vo-task": ("singleplayer", "Your task is to identify it"),
    "vo-targets": ("singleplayer", "only employees that successfully meet their assigned productivity targets will be retained."),
    "vo-airlock": ("singleplayer", "Departing employees will instead be released via the airlock."),
    "vo-painless": ("singleplayer", "We are advised that the procedure is painless."),
    "vo-equipment": ("gift", "all further equipment must be purchased from your own earnings."),
    "vo-paired": ("multiplayer_b", "you have been paired with Employee 119-A."),
    "vo-better": ("multiplayer_b", "Only the better-performing employee will be retained."),
    "vo-randomly": ("august", "a number of employees have been guessing their location by closing their eyes and clicking randomly."),
    "vo-notworst": ("august", "Interestingly enough, this is not the worst strategy that we have observed."),
    "vo-slogan": ("singleplayer", "Xenocorp: Finding Our Place in the Universe."),
}


def read_message(name):
    base = os.path.join(ROOT, "assets", "audio", "xenocorp_" + name)
    lines = open(base + ".txt", encoding="utf-8").read().split("\n")
    text, marks, end = lines[0], [], None
    for line in lines[1:]:
        parts = line.split("\t")
        if len(parts) == 2 and parts[0].isdigit():
            marks.append((int(parts[0]), int(parts[1])))
        elif len(parts) == 2 and parts[0] == "end":
            end = int(parts[1])
    with wave.open(base + ".wav") as w:
        rate = w.getframerate()
        samples = array.array("h", w.readframes(w.getnframes()))
    return text, marks, end, rate, samples


def quietest(samples, rate, around_ms, reach_ms):
    """The sample index of the quietest 10 ms within reach of a moment."""
    window = rate // 100
    best, best_energy = int(around_ms * rate / 1000), float("inf")
    start = max(0, int((around_ms - reach_ms) * rate / 1000))
    stop = min(len(samples) - window, int((around_ms + reach_ms) * rate / 1000))
    for i in range(start, stop, window // 4):
        energy = sum(abs(s) for s in samples[i:i + window])
        if energy < best_energy:
            best, best_energy = i + window // 2, energy
    return best


def cut(line_id, message, phrase):
    text, marks, end, rate, samples = read_message(message)
    at = text.index(phrase)
    after = at + len(phrase)
    starts = [t for t, i in marks if i == at]
    if not starts:
        raise ValueError(f"{line_id}: no word starts at '{phrase[:20]}'")
    following = [t for t, i in marks if i > after - 1]
    finish = following[0] if following else end
    first = quietest(samples, rate, starts[0] - 40, 60)
    last = quietest(samples, rate, finish - 60, 90)
    clip = array.array("h", samples[first:last])
    fade = rate // 200
    for k in range(min(fade, len(clip) // 2)):
        clip[k] = int(clip[k] * k / fade)
        clip[-1 - k] = int(clip[-1 - k] * k / fade)
    with wave.open(os.path.join(OUT, line_id + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(clip.tobytes())
    print(f"{line_id}: {len(clip) / rate:.2f} s  \"{phrase}\"")


def main():
    os.makedirs(OUT, exist_ok=True)
    for line_id, (message, phrase) in LINES.items():
        cut(line_id, message, phrase)


if __name__ == "__main__":
    main()
