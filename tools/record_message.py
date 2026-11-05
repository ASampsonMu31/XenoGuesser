"""
Records a message from Xenocorp in the game's voice, as the game's own messages are kept
(assets/audio/<name>.wav and .txt): the transcript, when each word starts, and the voice's
settings, copied from another message so the two sound alike.

The voice is Piper "bryce" (assets/audio/voice/bryce.onnx), run as PlanetVoice.java runs it (the
same phonemes, scales and peak level), from eSpeak's IPA (Piper's own phonemiser). The model gives
no timings, so the message is said a phrase at a time (each phrase ending at a comma, colon or full
stop, with a short pause after it) and each word's start is put in its phrase by its share of the
phrase's phonemes. Then it's filtered and levelled, and the faint static added, as
TransmissionMessage.shape and hiss do for the planet sentence.

With --framed, the message is framed as every Xenocorp message is: the settings message's own
opening (its chime and the robotic "You have one new message from Xenocorp:", up to where its
own words begin) before it, and its own sign-off ("Xenocorp: Finding Our Place in the Universe."
and the closing chime) after it; the transcript must begin and end with the same words, and only
what's between them is said.

Usage (from the project's folder):
    python tools/record_message.py <name> <settings from> "<transcript>" [--framed] [word=spoken ...]
e.g.
    python tools/record_message.py xenocorp_january xenocorp_gift "You have one new message..." IQ="I Q"
Each word=spoken says that word as the spoken text instead (the transcript keeps the word).
A spoken text BLEEP:<before>:<after> censors the word: the phonemes before (eSpeak IPA, one
character each), a bleep, then the phonemes after, e.g. fu**ed=BLEEP:f:t.
"""
import json
import sys
import wave

import numpy as np
import onnxruntime as ort
from piper import PiperVoice
from scipy.signal import lfilter

MODEL = "assets/audio/voice/bryce.onnx"
# As PlanetVoice: quicker than the voice's usual pace, a little less varied
SCALES = np.array([0.6, 0.6, 0.7], dtype=np.float32)
# Silence before the first word, and after a phrase ending in each mark (seconds)
LEAD = 0.35
# A censored word's bleep (see BLEEP: in the usage): how long, its pitch and how loud
BLEEP_SECONDS, BLEEP_HZ, BLEEP_LEVEL = 0.32, 1000.0, 0.55
PAUSE = {",": 0.12, ":": 0.28, ";": 0.2, ".": 0.38, "!": 0.38, "?": 0.38}


def main():
    name, settings_from, transcript = sys.argv[1], sys.argv[2], sys.argv[3]
    framed = "--framed" in sys.argv[4:]
    spoken_as = dict(a.split("=", 1) for a in sys.argv[4:] if a != "--framed")
    # Framed: the opening and the closing chime are the settings message's own
    frame_lines = open("assets/audio/" + settings_from + ".txt", encoding="utf-8").read().splitlines()
    intro_text = frame_lines[0][:frame_lines[0].index(": ") + 2]
    outro_from = frame_lines[0].rindex("Xenocorp: ")
    outro_text = frame_lines[0][outro_from:]
    intro_rows, outro_rows, intro_audio, outro_audio, body_from, body_to = [], [], None, None, 0, len(transcript)
    if framed:
        assert transcript.startswith(intro_text), "the transcript must begin " + repr(intro_text)
        assert transcript.endswith(" " + outro_text), "the transcript must end " + repr(outro_text)
        rows = [tuple(int(v) for v in l.split("\t")) for l in frame_lines[2:] if l[:1].isdigit()]
        intro_rows = [r for r in rows if r[1] < len(intro_text)]
        first_word = min(r[0] for r in rows if r[1] >= len(intro_text))
        frame_end = int(frame_lines[1].split("\t")[1])
        with wave.open("assets/audio/" + settings_from + ".wav") as w:
            frame = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
            frame_rate = w.getframerate()
        cut = int((first_word - 200) * frame_rate / 1000)
        # (the sign-off from just before its first word: the pause before it is quiet)
        outro_ms = min(r[0] for r in rows if r[1] >= outro_from) - 200
        intro_audio, outro_audio = frame[:cut], frame[int(outro_ms * frame_rate / 1000):]
        outro_rows = [(r[0] - outro_ms, r[1] - outro_from) for r in rows if r[1] >= outro_from]
        outro_length = frame_end - outro_ms
        body_from, body_to = len(intro_text), len(transcript) - len(outro_text) - 1
    voice = PiperVoice.load(MODEL)
    config = json.load(open(MODEL + ".json", encoding="utf-8"))
    ids = config["phoneme_id_map"]
    rate = config["audio"]["sample_rate"]
    session = ort.InferenceSession(MODEL)

    def phonemes(text):
        return [p for sentence in voice.phonemize(text) for p in sentence]

    def say(text):
        return say_phonemes(phonemes(text))

    def say_phonemes(said):
        sequence = [ids["^"][0], ids["_"][0]]
        for p in said:
            if p in ids:
                sequence += [ids[p][0], ids["_"][0]]
        sequence.append(ids["$"][0])
        out = session.run(None, {"input": np.array([sequence], dtype=np.int64),
                                 "input_lengths": np.array([len(sequence)], dtype=np.int64),
                                 "scales": SCALES})[0].squeeze()
        return out / max(1e-8, float(np.abs(out).max()))

    # The words, where each starts in the transcript, and the phrases they make
    words, at = [], body_from
    for token in transcript[body_from:body_to].split(" "):
        start = transcript.index(token, at)
        words.append((token, start))
        at = start + len(token)
    phrases, current = [], []
    for word in words:
        current.append(word)
        if word[0][-1] in PAUSE:
            phrases.append(current)
            current = []
    if current:
        phrases.append(current)

    # (framed, the body starts just after the opening, which its timings follow on from)
    lead = 0.2 if framed else LEAD
    offset = len(intro_audio) / rate if framed else 0.0
    audio = [np.zeros(int(lead * rate))]
    timings = []
    t = lead
    def trimmed(sound):
        # (trimmed of silence at either end, a little kept)
        loud = np.nonzero(np.abs(sound) > 0.015)[0]
        if len(loud):
            sound = sound[max(0, loud[0] - rate // 50): min(len(sound), loud[-1] + rate // 20)]
        return sound

    def censored(rule):
        # BLEEP:<phonemes before>:<phonemes after>: the word's start, a bleep over the middle, its end
        _, before_ph, after_ph = rule.split(":")
        parts = []
        if before_ph:
            parts.append(trimmed(say_phonemes(list(before_ph))))
        n = int(BLEEP_SECONDS * rate)
        ramp = np.minimum(1.0, np.minimum(np.arange(n), np.arange(n)[::-1]) / (0.008 * rate))
        parts.append(BLEEP_LEVEL * np.sin(2 * np.pi * BLEEP_HZ * np.arange(n) / rate) * ramp)
        if after_ph:
            parts.append(trimmed(say_phonemes(list(after_ph))))
        return np.concatenate(parts)

    for phrase in phrases:
        clean = [w.strip(",.:;!?\"") for w, _ in phrase]
        spoken = [spoken_as.get(c, c) for c in clean]
        # The phrase in runs: words said together, and each censored word on its own
        runs, current = [], []
        for item in zip(phrase, spoken):
            if item[1].startswith("BLEEP:"):
                if current:
                    runs.append(current)
                runs.append([item])
                current = []
            else:
                current.append(item)
        if current:
            runs.append(current)
        for run in runs:
            if run[0][1].startswith("BLEEP:"):
                sound = censored(run[0][1])
                timings.append((int(round((offset + t) * 1000)), run[0][0][1]))
            else:
                sound = trimmed(say(" ".join(s for _, s in run)))
                weights = [max(1, len([p for p in phonemes(s) if p.strip()])) for _, s in run]
                total, before = float(sum(weights)), 0.0
                length = len(sound) / rate
                for ((word, char), _), weight in zip(run, weights):
                    timings.append((int(round((offset + t + length * before / total) * 1000)), char))
                    before += weight
            audio.append(sound)
            t += len(sound) / rate
        pause = PAUSE.get(phrase[-1][0][-1], 0.0)
        audio.append(np.zeros(int(pause * rate)))
        t += pause
    x = np.concatenate(audio)

    # Filtered and levelled as the recordings' voice is, then the static (see TransmissionMessage)
    extra = {}
    for line in open("assets/audio/" + settings_from + ".txt", encoding="utf-8").read().splitlines():
        parts = line.split("\t")
        if parts[0] in ("rate", "level", "highpass", "presence", "hiss"):
            extra[parts[0]] = parts

    def numbers(s):
        return [float(v) for v in s.strip().split(" ")]

    hp, pr, lv, hs = extra["highpass"], extra["presence"], extra["level"], extra["hiss"]
    x = lfilter(numbers(hp[1]), numbers(hp[2]), x)
    peak = lfilter(numbers(pr[1]), numbers(pr[2]), x)
    shaped = np.tanh((x + float(pr[3]) * peak) * float(lv[1])) * float(lv[2])
    noise = lfilter(numbers(hs[2]), numbers(hs[3]), np.random.default_rng(119).standard_normal(len(shaped)))
    shaped = shaped + noise / max(1e-9, float(np.abs(noise).max())) * float(hs[1])
    pcm = (np.clip(shaped, -1.0, 1.0) * 32767).astype("<i2")
    if framed:
        # (the pause after the body's last word leaves room before the sign-off)
        pcm = np.concatenate([intro_audio, pcm, outro_audio])
    with wave.open("assets/audio/" + name + ".wav", "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes(pcm.tobytes())

    body_end = int(round((offset + t) * 1000))
    timings += [(body_end + ms, body_to + 1 + char) for ms, char in outro_rows]
    end = body_end + (outro_length if framed else 0)
    lines = [transcript, "end\t%d" % end]
    lines += ["%d\t%d" % timing for timing in intro_rows + timings]
    lines += ["\t".join(extra[k]) for k in ("rate", "level", "highpass", "presence", "hiss")]
    open("assets/audio/" + name + ".txt", "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    print("%s: %.1f s, %d words" % (name, end / 1000.0, len(intro_rows) + len(timings)))


if __name__ == "__main__":
    main()
