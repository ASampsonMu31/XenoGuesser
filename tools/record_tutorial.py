"""
Records the tutorial's messages in the Xenocorp voice (see TutorialText.java): every msg.* entry
of assets/text/tutorial_messages.txt whose recording (assets/audio/tutorial/<key>.wav and .txt)
is missing, or was made from other words, is said afresh with tools/record_message.py, unframed
(no chime, no "You have one new message", no sign-off), in the voice settings of the gift
message. Recordings of entries no longer in the file are deleted.

Usage (from the project's folder):
    python tools/record_tutorial.py            (only what's changed)
    python tools/record_tutorial.py --all      (everything again)
Words the voice says badly can be spelt out for it in SPOKEN_AS below (the transcript keeps the word).
"""
import os
import subprocess
import sys

MESSAGES = "assets/text/tutorial_messages.txt"
OUT = "assets/audio/tutorial"
SETTINGS_FROM = "xenocorp_gift"
# How some words are said (word=spoken), as record_message.py takes them
SPOKEN_AS = [
    # (the swear in the sun's exercise, censored: the f of "frog", a bleep, the t-sounding end of "ducked")
    "fu**ed=BLEEP:f:t",
]


def entries():
    found = {}
    for line in open(MESSAGES, encoding="utf-8").read().splitlines():
        t = line.strip()
        if not t or t.startswith("#") or " = " not in t:
            continue
        key, text = t.split(" = ", 1)
        if key.strip().startswith("msg."):
            found[key.strip()] = text.strip()
    return found


def main():
    again = "--all" in sys.argv[1:]
    os.makedirs(OUT, exist_ok=True)
    wanted = entries()
    recorded = 0
    for key, text in wanted.items():
        transcript = os.path.join(OUT, key + ".txt")
        if not again and os.path.exists(transcript) and os.path.exists(os.path.join(OUT, key + ".wav")):
            with open(transcript, encoding="utf-8") as f:
                if f.readline().strip() == text:
                    continue
        subprocess.run([sys.executable, "tools/record_message.py", "tutorial/" + key, SETTINGS_FROM, text] + SPOKEN_AS, check=True)
        recorded += 1
    # (the recordings of messages taken out of the file)
    for name in os.listdir(OUT):
        key = name.rsplit(".", 1)[0]
        if key not in wanted:
            os.remove(os.path.join(OUT, name))
    print("%d of %d messages recorded" % (recorded, len(wanted)))


if __name__ == "__main__":
    main()
