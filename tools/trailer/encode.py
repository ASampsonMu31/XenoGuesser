"""Makes the trailer's clips from filmed frames (see film.sh): each <frames dir>/<shot>/ of
f00001.jpg... becomes <clips dir>/<shot>.mp4 (H.264 at 30 fps and 1920x1080, through ffmpeg's
h264_mf, Windows' own encoder). A folder of frames recorded in real time (record.py's, each
named t<milliseconds>.jpg) is timed by those names instead.

ffmpeg is looked for in $FFMPEG, then on the PATH, then in Anaconda (whose build has h264_mf).

Usage:  python tools/trailer/encode.py <frames dir> <clips dir> [shot ...]
"""
import os
import shutil
import subprocess
import sys

FPS = 30
BITRATE = "12M"


def ffmpeg():
    for found in (os.environ.get("FFMPEG"), shutil.which("ffmpeg"),
                  os.path.expanduser(r"~\anaconda3\Library\bin\ffmpeg.exe")):
        if found and os.path.exists(found):
            return found
    raise SystemExit("ffmpeg not found: set FFMPEG to one with the h264_mf encoder")


def encode(folder, clip):
    names = sorted(n for n in os.listdir(folder) if n.endswith(".jpg"))
    if not names:
        return
    if names[0].startswith("t"):
        # Recorded in real time: a list giving each frame until the next one's time
        listing = os.path.join(folder, "frames.txt")
        with open(listing, "w") as out:
            times = [int(n[1:-4]) for n in names]
            for i, n in enumerate(names):
                out.write(f"file '{n}'\n")
                out.write(f"duration {((times[i + 1] if i + 1 < len(times) else times[i] + 33) - times[i]) / 1000:.3f}\n")
        source = ["-f", "concat", "-safe", "0", "-i", listing, "-r", str(FPS)]
    else:
        source = ["-framerate", str(FPS), "-i", os.path.join(folder, "f%05d.jpg")]
    subprocess.run([ffmpeg(), "-y", "-hide_banner", "-loglevel", "error", *source,
                    "-vf", "scale=1920:1080:flags=lanczos,format=nv12",
                    "-c:v", "h264_mf", "-b:v", BITRATE, "-rate_control", "cbr", "-movflags", "+faststart", clip], check=True)
    print(f"{os.path.basename(clip)}: {len(names)} frames, {os.path.getsize(clip) // 1024} KB")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    source, target = sys.argv[1], sys.argv[2]
    os.makedirs(target, exist_ok=True)
    wanted = set(sys.argv[3:])
    for shot in sorted(os.listdir(source)):
        folder = os.path.join(source, shot)
        if os.path.isdir(folder) and (not wanted or shot in wanted):
            encode(folder, os.path.join(target, shot + ".mp4"))


if __name__ == "__main__":
    main()
