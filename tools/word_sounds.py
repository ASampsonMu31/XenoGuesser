"""Writes assets/text/word_sounds.txt.gz: the commonest English words, how common each is, and
how eSpeak says it in the voice's alphabet (M58). Pronunciation splits a username into the words
it most likely holds and says each as written here, falling back on its letter-to-sound rules
only for what isn't a word. The game can't run eSpeak, so the sounds are worked out here, once.

Needs the piper-tts package (it carries eSpeak and its data) and wordfreq:
    pip install piper-tts wordfreq
    python tools/word_sounds.py

Each line: word, tab, its Zipf frequency times 100 (log10 of uses per billion words, plus 9),
tab, its phonemes. Single letters other than a and i are left out (a name's lone letters are
said as letters), as are abbreviations eSpeak spells out and words it says as another language's.
"""
import gzip
import re
import unicodedata
from pathlib import Path

from piper.phonemize_espeak import EspeakPhonemizer
from wordfreq import top_n_list, zipf_frequency

WORDS = 80_000
OUT = Path(__file__).resolve().parent.parent / "assets" / "text" / "word_sounds.txt.gz"


def main():
    espeak = EspeakPhonemizer()
    lines = []
    for word in top_n_list("en", WORDS * 2):
        if not re.fullmatch(r"[a-z]+", word) or (len(word) == 1 and word not in ("a", "i")):
            continue
        sentences = espeak.phonemize("en", word)
        phonemes = unicodedata.normalize("NFC", "".join(p for s in sentences for p in s)).strip()
        # (eSpeak marks a switch to another language's voice; those aren't said in this one)
        if not phonemes or "(" in phonemes:
            continue
        # (nor abbreviations eSpeak spells out, a stress for every letter: "uk", "bbc", "th")
        if len(word) <= 5 and phonemes.count("ˈ") + phonemes.count("ˌ") >= len(word):
            continue
        lines.append(f"{word}\t{round(zipf_frequency(word, 'en') * 100)}\t{phonemes}")
        if len(lines) == WORDS:
            break
    with gzip.open(OUT, "wt", encoding="utf-8", newline="\n") as out:
        out.write("\n".join(lines) + "\n")
    print(f"Wrote {len(lines)} words to {OUT}")


if __name__ == "__main__":
    main()
