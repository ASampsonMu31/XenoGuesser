"""Records the trailer's gameplay that's drawn outside the 3D view (the big map, guessing, the
results' globe, the shop) in real time: starts the game (seed 123, developer switches on), then
plays a script of what a player does, with the real mouse and keyboard, while the screen is
recorded into <out dir>/<clip>/t<milliseconds>.jpg for encode.py.

The mouse moves as a hand moves it: along a slight curve, quick in the middle and slowing onto
its target, a little past it and back. Don't touch the PC while it runs.

Script lines (# comments):
  waitlog <text>        wait until the game's log has a line containing text
  sleep <seconds>
  rec <clip> / stop     start recording into a clip's folder / stop
  shot <name>           save one screenshot as <name>.jpg (to find where things are)
  move <x> <y> [s]      the mouse to x, y (shares of the screen) over s seconds
  look <dx> <dy> [s]    turn the view by dx, dy pixels of mouse movement over s seconds
  click / rclick        a left / right click where the mouse is
  key <name> [s]        press a key (held s seconds): w, m, space, enter, 1-4, shift, escape...
  down <name> / up <name>
  wheel <notches>
  partner               start a second game (multiplayer's other player) with the switches after ++
  cursor on|off          draw the mouse pointer into the recording (when it's free, over the map or shop)
  front                 bring the recorded game's window to the front (stops if it can't)
  waitclick <file>      wait for a file holding "x y" (shares of the screen, written by hand
                        while looking at a shot), then move there and click
  maprect <x0> <y0> <x1> <y1>   where the big map's chart is on screen (shares), for movegoal
  movegoal <du> <dv> [s]        the mouse to the round's answer on the chart (as the game's log
                                gives it), off by du, dv shares of the chart

Usage:  python tools/trailer/record.py <script> <out dir> [-- java -D switches] [++ the partner's switches]
"""
import ctypes
import ctypes.wintypes
import math
import os
import queue
import random
import subprocess
import sys
import threading
import time

from PIL import ImageDraw, ImageGrab

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
user32 = ctypes.windll.user32
user32.SetProcessDPIAware()
SW, SH = user32.GetSystemMetrics(0), user32.GetSystemMetrics(1)

KEYS = {"w": 0x57, "a": 0x41, "s": 0x53, "d": 0x44, "m": 0x4D, "c": 0x43, "x": 0x58, "space": 0x20, "enter": 0x0D,
        "shift": 0x10, "escape": 0x1B, "1": 0x31, "2": 0x32, "3": 0x33, "4": 0x34, "p": 0x50}


def key_down(name):
    user32.keybd_event(KEYS[name], 0, 0, 0)


def key_up(name):
    user32.keybd_event(KEYS[name], 0, 2, 0)


def mouse_at():
    p = ctypes.wintypes.POINT()
    user32.GetCursorPos(ctypes.byref(p))
    return p.x, p.y


def human_path(start, end):
    """A hand's movement as a function of t (0 to 1): a gentle arc, eased in and out, a little past and back."""
    (x0, y0), (x1, y1) = start, end
    dist = math.hypot(x1 - x0, y1 - y0) or 1.0
    bend = random.uniform(-0.12, 0.12) * dist
    nx, ny = -(y1 - y0) / dist, (x1 - x0) / dist
    over = min(14, dist * 0.03)
    ox, oy = (x1 - x0) / dist * over, (y1 - y0) / dist * over

    def at(t):
        if t < 0.85:
            u = t / 0.85
            e = u * u * (3 - 2 * u)
            return (x0 + (x1 + ox - x0) * e + nx * bend * math.sin(math.pi * u),
                    y0 + (y1 + oy - y0) * e + ny * bend * math.sin(math.pi * u))
        u = (t - 0.85) / 0.15
        u = u * u * (3 - 2 * u)
        return x1 + ox * (1 - u), y1 + oy * (1 - u)
    return at


def timed(seconds, step):
    """Calls step(t) for t from 0 to 1 by the clock, as often as it can (Windows' sleeps are coarse)."""
    started = time.perf_counter()
    while True:
        t = min(1.0, (time.perf_counter() - started) / max(seconds, 1e-3))
        step(t)
        if t >= 1.0:
            return
        time.sleep(0.004)


def move(x, y, seconds):
    path = human_path(mouse_at(), (int(x * SW), int(y * SH)))
    timed(seconds, lambda t: user32.SetCursorPos(*(int(round(c)) for c in path(t))))


def look(dx, dy, seconds):
    """Relative mouse movement (the view turns), eased in and out with a little tremor."""
    done = [0.0, 0.0]

    def step(t):
        e = t * t * (3 - 2 * t)
        tx, ty = dx * e + random.uniform(-0.6, 0.6) * (t < 1), dy * e + random.uniform(-0.4, 0.4) * (t < 1)
        mx, my = int(round(tx - done[0])), int(round(ty - done[1]))
        if mx or my:
            user32.mouse_event(0x0001, mx, my, 0, 0)
            done[0] += mx
            done[1] += my
    timed(seconds, step)


def click(right=False):
    user32.mouse_event(0x0008 if right else 0x0002, 0, 0, 0, 0)
    time.sleep(random.uniform(0.07, 0.12))
    user32.mouse_event(0x0010 if right else 0x0004, 0, 0, 0, 0)


class Recorder:
    """Grabs the screen as fast as it can, the frames written by a few threads."""

    def __init__(self):
        self.folder = None
        self.cursor = False
        self.jobs = queue.Queue(maxsize=24)
        for _ in range(3):
            threading.Thread(target=self.write, daemon=True).start()
        threading.Thread(target=self.grab, daemon=True).start()

    def write(self):
        while True:
            image, path = self.jobs.get()
            image.save(path, quality=92)
            self.jobs.task_done()

    def grab(self):
        while True:
            folder = self.folder
            if folder is None:
                time.sleep(0.005)
                continue
            started = time.perf_counter()
            image = ImageGrab.grab()
            cursor = mouse_at()
            if self.cursor:
                # (the screen grab leaves the pointer out: drawn in where it was)
                x, y = cursor
                pts = [(x, y), (x, y + 26), (x + 7, y + 20), (x + 12, y + 31), (x + 17, y + 29), (x + 12, y + 18), (x + 20, y + 18)]
                ImageDraw.Draw(image).polygon(pts, fill=(255, 255, 255), outline=(0, 0, 0))
            self.jobs.put((image, os.path.join(folder, f"t{int(started * 1000):012d}.jpg")))
            time.sleep(max(0.0, 1 / 30 - (time.perf_counter() - started)))

    def start(self, folder):
        os.makedirs(folder, exist_ok=True)
        self.folder = folder

    def stop(self):
        self.folder = None
        self.cursor = False
        self.jobs.join()


def front(pid):
    """Brings a process's biggest visible window to the front (an Alt press lets it take the focus)."""
    found = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.wintypes.HWND, ctypes.wintypes.LPARAM)
    def each(hwnd, _):
        owner = ctypes.wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid and user32.IsWindowVisible(hwnd):
            r = ctypes.wintypes.RECT()
            user32.GetWindowRect(hwnd, ctypes.byref(r))
            found.append(((r.right - r.left) * (r.bottom - r.top), hwnd))
        return True

    user32.EnumWindows(each, 0)
    if found:
        user32.keybd_event(0x12, 0, 0, 0)
        user32.SetForegroundWindow(max(found)[1])
        user32.keybd_event(0x12, 0, 2, 0)
    time.sleep(0.5)
    # (never type or click into some other window: stop if the game's still not in front)
    owner = ctypes.wintypes.DWORD()
    user32.GetWindowThreadProcessId(user32.GetForegroundWindow(), ctypes.byref(owner))
    if owner.value != pid:
        raise SystemExit("The game's window couldn't be brought to the front; stopped before any input")


def launch(out, extra, name="log.txt"):
    cp_file = os.path.join(ROOT, "target", "classpath.txt")
    cp = os.path.join(ROOT, "target", "classes") + ";" + open(cp_file).read().strip()
    log = open(os.path.join(out, name), "w")
    cmd = ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-XX:MaxRAMPercentage=70",
           "--add-exports", "java.base/java.lang=ALL-UNNAMED", "--add-exports", "java.desktop/sun.java2d=ALL-UNNAMED",
           "--add-exports", "java.desktop/sun.awt=ALL-UNNAMED",
           "-Dxenoguesser.dev=true", "-Dxenoguesser.seed=123",
           # (straight into a game, unless it's multiplayer's, which starts from the menu's panel)
           *([] if any("mpauto" in e for e in extra) else ["-Dxenoguesser.skipmenu=true"]),
           "-Dxenoguesser.quality=high", "-Dxenoguesser.shadows=on", *extra, "-classpath", cp, "XenoGuesser"]
    return subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT), os.path.join(out, name)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    script, out = sys.argv[1], os.path.abspath(sys.argv[2])
    args = sys.argv[3:]
    partner_extra = args[args.index("++") + 1:] if "++" in args else []
    args = args[:args.index("++")] if "++" in args else args
    extra = args[args.index("--") + 1:] if "--" in args else []
    partner = None
    os.makedirs(out, exist_ok=True)
    game, log = launch(out, extra)
    rec = Recorder()
    random.seed(7)
    chart = [0.654, 0.32, 0.992, 0.92]
    try:
        for line in open(script, encoding="utf-8"):
            words = line.split("#")[0].split()
            if not words:
                continue
            op, args = words[0], words[1:]
            print(time.strftime("%H:%M:%S"), line.strip(), flush=True)
            if op == "waitlog":
                text = " ".join(args)
                deadline = time.time() + 600
                while text not in open(log, encoding="utf-8", errors="replace").read():
                    if game.poll() is not None or time.time() > deadline:
                        raise SystemExit("The game ended (or never said '" + text + "')")
                    time.sleep(0.5)
            elif op == "sleep":
                time.sleep(float(args[0]))
            elif op == "rec":
                rec.start(os.path.join(out, args[0]))
            elif op == "stop":
                rec.stop()
            elif op == "shot":
                ImageGrab.grab().save(os.path.join(out, args[0] + ".jpg"), quality=90)
            elif op == "move":
                move(float(args[0]), float(args[1]), float(args[2]) if len(args) > 2 else 0.6)
            elif op == "look":
                look(float(args[0]), float(args[1]), float(args[2]) if len(args) > 2 else 1.0)
            elif op == "click":
                click()
            elif op == "rclick":
                click(True)
            elif op == "key":
                key_down(args[0])
                time.sleep(float(args[1]) if len(args) > 1 else 0.08)
                key_up(args[0])
            elif op == "down":
                key_down(args[0])
            elif op == "up":
                key_up(args[0])
            elif op == "partner":
                partner, _ = launch(out, partner_extra, "partner_log.txt")
            elif op == "waitclick":
                while not os.path.exists(os.path.join(out, args[0])):
                    if game.poll() is not None:
                        raise SystemExit("The game ended while waiting for " + args[0])
                    time.sleep(0.5)
                x, y = (float(v) for v in open(os.path.join(out, args[0])).read().split())
                move(x, y, 0.7)
                time.sleep(0.25)
                click()
            elif op == "cursor":
                rec.cursor = args[0] == "on"
            elif op == "front":
                front(game.pid)
            elif op == "maprect":
                chart = [float(a) for a in args]
            elif op == "movegoal":
                goals = [l.split()[1:3] for l in open(log, encoding="utf-8", errors="replace") if l.startswith("[MAPGOAL]")]
                u, v = float(goals[-1][0]) + float(args[0]), float(goals[-1][1]) + float(args[1])
                move(chart[0] + (chart[2] - chart[0]) * u, chart[1] + (chart[3] - chart[1]) * v, float(args[2]) if len(args) > 2 else 0.8)
            elif op == "wheel":
                user32.mouse_event(0x0800, 0, 0, int(float(args[0]) * 120), 0)
            else:
                print("Unknown line:", line.strip())
    finally:
        rec.stop()
        game.kill()
        if partner:
            partner.kill()


if __name__ == "__main__":
    main()
