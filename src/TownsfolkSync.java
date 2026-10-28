import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Multiplayer: the same people, vehicles and open front doors on both PCs (M57). They meet and
 * react to one another (queueing, giving way, stepping round, borrowing a neighbour's car), so
 * two PCs moving their own would soon disagree; instead the host's PC moves them round both
 * players (Inhabitants.update, given the partner), and five times a second sends the joiner's
 * PC everyone near enough the joiner to be seen: where each is and how they stand, and now and
 * then what they look like (their nation and the seed their looks were drawn from, so the
 * joiner's PC makes the same person or vehicle). The joiner's PC moves none of its own: it
 * draws the host's, a snapshot behind, smoothed from one snapshot to the next.
 * <p>
 * A snapshot goes as TOWNSFOLK messages of at most Multiplayer.MAX_TOWNSFOLK_BYTES, each
 * standing alone: the snapshot's number and the joiner's place (which the places are measured
 * from, as floats), then entries until it's full. A busy town street (60 people out and 50
 * vehicles in reach) is about 2.5 kB a snapshot, 12 kB a second; the country far less.
 */
final class TownsfolkSync {

    private static final long INTERVAL_MS = 200;
    // People are sent as far out as they're drawn, vehicles likewise, with a margin for the
    // joiner walking on before the next snapshot
    private static final double PEOPLE_REACH = 950.0, VEHICLE_REACH = Vehicles.DRAW_DISTANCE + 150.0;
    // What each one looks like goes with its first few snapshots in reach, then every few seconds
    // (in case those were lost)
    private static final int LOOKS_FIRST = 4;
    private static final long LOOKS_EVERY_MS = 3000;
    // The joiner's PC draws them this long behind the host's time (as each snapshot was taken
    // there), on top of the quickest a snapshot has come: one interval and a margin for the
    // network's unevenness, so there are nearly always snapshots either side of the moment drawn.
    // It keeps the last few of each one's snapshots, carries one on along its way at most
    // PREDICT_MS past its latest (should snapshots be late or lost), and forgets one not heard of
    // for FORGET_MS (gone out of reach)
    private static final long BEHIND_MS = 260, PREDICT_MS = 400, FORGET_MS = 1500;
    private static final int SAMPLES_KEPT = 6;

    // Entry kinds, and their flags
    private static final byte PERSON = 1, VEHICLE = 2, DOOR = 3;
    private static final byte WITH_LOOKS = 1, DRIVING = 2, HOUSEHOLD = 4, PARKED = 8;

    private final Inhabitants inhabitants;

    /**
     * The time snapshots are taken and drawn by, in milliseconds (only differences mean
     * anything): from the fine timer, as the wall clock on Windows moves in steps of up to 16 ms,
     * a frame's worth, which would have things drawn moving in fits and starts.
     */
    private static long clock() {
        return System.nanoTime() / 1_000_000L;
    }

    TownsfolkSync(Inhabitants inhabitants) {
        this.inhabitants = inhabitants;
    }

    // ------------------------------------------------------------------ the host's PC

    // Each person's and vehicle's number for the joiner, how many snapshots it's been in, when
    // its looks last went, and when it was last in reach (numbers not used for a while are let go)
    private static final class Sent {
        final int id;
        int snapshots;
        long looksAt, inReachAt;

        Sent(int id) {
            this.id = id;
        }
    }

    private final IdentityHashMap<Object, Sent> sent = new IdentityHashMap<>();
    private int nextId = 1, snapshot;
    private long lastSentMs, lastPrunedMs;
    // For the log every LOG_MS: bytes sent since, and how many were in the last snapshot
    private static final long LOG_MS = 10_000;
    private long bytesSent, loggedAt;
    private int peopleSent, vehiclesSent;

    /** The host's PC, every frame: every INTERVAL_MS, those near the joiner (at joiner) are sent. */
    void hostFrame(Multiplayer session, SpherePosition joiner, long now) {
        if (joiner != null) measureHost(joiner);
        if (joiner == null || now - lastSentMs < INTERVAL_MS) return;
        lastSentMs = now;
        snapshot++;
        Pieces out = new Pieces(session, snapshot, clock(), joiner);
        Vehicles vehicles = inhabitants.vehicles();
        peopleSent = 0;
        vehiclesSent = 0;
        for (Vehicles.Vehicle v : inhabitants.vehiclesNow()) {
            if (SphereMath.distance(joiner, v.position()) > VEHICLE_REACH) continue;
            vehiclesSent++;
            Sent s = numbered(v, now);
            boolean looks = wantsLooks(s, now);
            ByteBuffer e = out.entry(1 + 4 + 1 + 12 + 2 + (looks ? 13 : 0));
            e.put(VEHICLE).putInt(s.id).put((byte) ((looks ? WITH_LOOKS : 0) | (v.householdLooks ? HOUSEHOLD : 0) | (v.isParked() ? PARKED : 0)));
            putPlace(e, joiner, v.position());
            e.putShort(angle(v.heading));
            if (looks) e.putInt(v.looksNation).putLong(v.looks);
        }
        inhabitants.forEachOut(p -> {
            boolean driving = p.phase == Inhabitants.Phase.DRIVING;
            if (driving) {
                // (only those who can be seen, in an open-topped vehicle near enough)
                if (p.driving == null || vehicles.seat(p.driving) == null || !sent.containsKey(p.driving)
                        || sent.get(p.driving).inReachAt != now) return;
            } else if (p.position == null || SphereMath.distance(joiner, p.position) > PEOPLE_REACH) {
                return;
            }
            peopleSent++;
            Sent s = numbered(p, now);
            boolean looks = wantsLooks(s, now);
            ByteBuffer e = out.entry(1 + 4 + 1 + (driving ? 4 : 12 + 2 + 1 + 2 + 3) + (looks ? 13 : 0));
            e.put(PERSON).putInt(s.id).put((byte) ((looks ? WITH_LOOKS : 0) | (driving ? DRIVING : 0) | (p.householdLooks ? HOUSEHOLD : 0)));
            if (driving) {
                e.putInt(sent.get(p.driving).id);
            } else {
                putPlace(e, joiner, p.position);
                e.putShort(angle(p.heading));
                e.put((byte) Math.round(Math.max(0f, Math.min(1f, p.walking)) * 255f));
                e.putShort((short) Math.round(Math.max(-300f, Math.min(300f, p.lift)) * 100f));
                e.put((byte) p.carrying).put((byte) p.boughtFrom).put((byte) p.packing);
            }
            if (looks) e.putInt(p.nation).putLong(p.looks);
        });
        inhabitants.forEachOpenDoor((door, amount) -> {
            if (SphereMath.distance(joiner, door.door) > PEOPLE_REACH) return;
            out.entry(1 + 8 + 1).put(DOOR).putLong(door.id).put((byte) Math.round(Math.min(1f, amount) * 255f));
        });
        out.flush();
        if (now - loggedAt >= LOG_MS) {
            if (loggedAt > 0) {
                System.out.printf(java.util.Locale.ROOT, "[TOWNSFOLK] Sent %d people and %d vehicles in the latest snapshot; %.1f kB a second; "
                        + "moving ones' speed frame to frame here off their average by %.0f%% on average, under half of it in %.1f%% of frames, over 1.5 times it in %.1f%%%n",
                        peopleSent, vehiclesSent, bytesSent / ((now - loggedAt) / 1000.0) / 1000.0, 100.0 * unevenness / Math.max(1, movingFrames),
                        100.0 * stalls / Math.max(1, movingFrames), 100.0 * surges / Math.max(1, movingFrames));
                movingFrames = 0;
                unevenness = 0.0;
                stalls = 0;
                surges = 0;
            }
            loggedAt = now;
            bytesSent = 0;
        }
        // Numbers not used for a while are let go (they'd only keep the gone alive)
        if (now - lastPrunedMs > 5000) {
            lastPrunedMs = now;
            sent.values().removeIf(s -> now - s.inReachAt > 10_000);
        }
    }

    private Sent numbered(Object thing, long now) {
        Sent s = sent.computeIfAbsent(thing, k -> new Sent(nextId++));
        s.inReachAt = now;
        return s;
    }

    private static boolean wantsLooks(Sent s, long now) {
        boolean looks = s.snapshots++ < LOOKS_FIRST || now - s.looksAt > LOOKS_EVERY_MS;
        if (looks) s.looksAt = now;
        return looks;
    }

    /** A place on the datum, as floats from the joiner's place (far more exact than needed within a few thousand units). */
    private static void putPlace(ByteBuffer e, SpherePosition joiner, SpherePosition place) {
        e.putFloat((float) (place.x() - joiner.x())).putFloat((float) (place.y() - joiner.y())).putFloat((float) (place.z() - joiner.z()));
    }

    /** A heading (radians) in a short, to about a ten-thousandth of a radian. */
    private static short angle(double heading) {
        return (short) Math.round(Math.atan2(Math.sin(heading), Math.cos(heading)) / Math.PI * 32767.0);
    }

    private static double angle(short packed) {
        return packed / 32767.0 * Math.PI;
    }

    /** A snapshot's messages: each begins with the snapshot's number, when it was taken (the host's time) and the joiner's place, then as many entries as fit. */
    private final class Pieces {
        private static final int HEADER = 4 + 8 + 3 * 8;
        private final Multiplayer session;
        private final int snapshot;
        private final long takenAt;
        private final SpherePosition joiner;
        private final ByteBuffer piece = ByteBuffer.allocate(Multiplayer.MAX_TOWNSFOLK_BYTES);

        Pieces(Multiplayer session, int snapshot, long takenAt, SpherePosition joiner) {
            this.session = session;
            this.snapshot = snapshot;
            this.takenAt = takenAt;
            this.joiner = joiner;
            begin();
        }

        private void begin() {
            piece.clear();
            piece.putInt(snapshot).putLong(takenAt).putDouble(joiner.x()).putDouble(joiner.y()).putDouble(joiner.z());
        }

        /** Room for an entry of this many bytes (sending what's already there first if it wouldn't fit). */
        ByteBuffer entry(int bytes) {
            if (piece.remaining() < bytes) flush();
            return piece;
        }

        void flush() {
            if (piece.position() <= HEADER) return;
            byte[] bytes = new byte[piece.position()];
            piece.flip();
            piece.get(bytes);
            session.sendTownsfolk(bytes);
            // (with the message's type byte)
            bytesSent += bytes.length + 1;
            begin();
        }
    }

    // ------------------------------------------------------------------ the joiner's PC

    /** Where one was in a snapshot, and when that was taken (the host's time). */
    private record Sample(long at, SpherePosition place, double heading, float walking, float lift) {
    }

    /** One of the host's people or vehicles as drawn here: its latest few samples, oldest first, and when it was last heard of (this PC's time). */
    private static final class Replica {
        final Object thing;
        final List<Sample> samples = new ArrayList<>();
        long heardAt;
        int drivingId = -1;
        // How far it has gone (to turn its wheels or walk its legs by), and from where it was last drawn
        SpherePosition drawnAt;
        // For the log's measure of smoothness
        final Track track = new Track();

        Replica(Object thing) {
            this.thing = thing;
        }
    }

    private final Map<Integer, Replica> replicas = new HashMap<>();
    // This PC's time less the host's, for a snapshot that came as quickly as any (see read)
    private long hostBehind = Long.MIN_VALUE;
    // The host's open doors as last heard (by door id: how far), and the doors near the joiner by id
    private final Map<Long, Float> doorsOpen = new HashMap<>();
    private int doorsSnapshot = -1;
    private final Map<Long, InfrastructureManager.Doorway> doorsById = new HashMap<>();
    private SpherePosition doorsFoundAt;

    /** The joiner's PC, every frame: takes in whatever's come, and puts everyone where they were a snapshot ago. */
    void joinerFrame(Multiplayer session, InfrastructureManager infrastructure, long now, float dt) {
        for (byte[] piece = session.pollTownsfolk(); piece != null; piece = session.pollTownsfolk()) {
            try {
                read(ByteBuffer.wrap(piece), infrastructure, now);
            } catch (java.nio.BufferUnderflowException e) {
                System.out.println("[MP] Ignored a TOWNSFOLK piece that was too short (" + piece.length + " bytes)");
            }
        }
        // (the moment drawn, in the host's time)
        long drawAt = clock() - hostBehind - BEHIND_MS;
        long frameNanos = System.nanoTime();
        float frameSeconds = lastFrameNanos == 0L ? 0f : (frameNanos - lastFrameNanos) / 1e9f;
        lastFrameNanos = frameNanos;
        List<Inhabitants.Person> people = inhabitants.replicaPeople;
        List<Vehicles.Vehicle> cars = inhabitants.replicaCars;
        people.clear();
        cars.clear();
        Iterator<Replica> it = replicas.values().iterator();
        while (it.hasNext()) {
            Replica r = it.next();
            if (now - r.heardAt > FORGET_MS) {
                it.remove();
                continue;
            }
            if (r.thing instanceof Vehicles.Vehicle v && !r.samples.isEmpty()) {
                Sample s = sampleAt(r, drawAt);
                double moved = r.drawnAt == null ? 0.0 : SphereMath.distance(r.drawnAt, s.place());
                v.position = s.place();
                v.heading = s.heading();
                v.spin += (float) moved;
                measure(r.track, moved, frameSeconds, 5.0);
                r.drawnAt = s.place();
                cars.add(v);
            }
        }
        // (people after the vehicles, so those driving sit where their vehicle is now)
        for (Replica r : replicas.values()) {
            if (!(r.thing instanceof Inhabitants.Person p)) continue;
            if (r.drivingId >= 0) {
                Replica car = replicas.get(r.drivingId);
                if (car == null || !(car.thing instanceof Vehicles.Vehicle v)) continue;
                p.phase = Inhabitants.Phase.DRIVING;
                p.driving = v;
                p.position = v.position;
                r.drawnAt = null;
            } else {
                if (r.samples.isEmpty()) continue;
                Sample s = sampleAt(r, drawAt);
                double moved = r.drawnAt == null ? 0.0 : SphereMath.distance(r.drawnAt, s.place());
                p.phase = Inhabitants.Phase.WALKING;
                p.driving = null;
                p.position = s.place();
                p.heading = s.heading();
                p.walking = s.walking();
                p.lift = s.lift();
                measure(r.track, moved, frameSeconds, 3.0);
                // (their legs walked by how far they've gone, as on the host)
                p.walkPhase = (p.walkPhase + (float) moved / inhabitants.halfStride(p) * (float) Math.PI) % (float) (Math.PI * 2);
                p.lookYaw = 0.35f * (float) Math.sin(p.walkPhase * 0.13f + p.sway);
                r.drawnAt = s.place();
            }
            people.add(p);
        }
        if (now - loggedAt >= LOG_MS) {
            if (loggedAt > 0) {
                System.out.printf(java.util.Locale.ROOT, "[TOWNSFOLK] Drawing %d of the host's people and %d vehicles; moving ones' speed frame to frame "
                        + "off their average by %.0f%% on average, under half of it in %.1f%% of frames, over 1.5 times it in %.1f%%%n",
                        people.size(), cars.size(), 100.0 * unevenness / Math.max(1, movingFrames), 100.0 * stalls / Math.max(1, movingFrames),
                        100.0 * surges / Math.max(1, movingFrames));
            }
            movingFrames = 0;
            unevenness = 0.0;
            stalls = 0;
            surges = 0;
            loggedAt = now;
        }
        Map<InfrastructureManager.Doorway, Float> open = new HashMap<>();
        for (Map.Entry<Long, Float> d : doorsOpen.entrySet()) {
            InfrastructureManager.Doorway door = doorsById.get(d.getKey());
            if (door != null) open.put(door, d.getValue());
        }
        inhabitants.showDoors(open, dt);
    }

    // For the log: frames in which something drawn was moving, how far its speed that frame was
    // from its average (summed, as a share), and how many were under half of it or over 1.5 times it
    private int movingFrames, stalls, surges;
    private double unevenness;

    /** Counts how evenly one moving thing is drawn going along (moving: its average over minSpeed a second). */
    private void measure(Track t, double moved, float dt, double minSpeed) {
        if (dt <= 0f) return;
        double speed = moved / dt;
        t.averageSpeed += (speed - t.averageSpeed) * Math.min(1.0, dt);
        if (t.averageSpeed < minSpeed) return;
        movingFrames++;
        unevenness += Math.abs(speed - t.averageSpeed) / t.averageSpeed;
        if (speed < t.averageSpeed * 0.5) stalls++;
        if (speed > t.averageSpeed * 1.5) surges++;
    }

    /** One thing's place as last drawn and its speed averaged over about a second, for the log's measure of smoothness. */
    private static final class Track {
        SpherePosition at;
        double averageSpeed;
    }

    // The host's PC: those near the joiner as moved here, for the same measure (to compare with
    // the joiner's, which can be no smoother than this)
    private final IdentityHashMap<Object, Track> hostTracks = new IdentityHashMap<>();
    private long lastFrameNanos, lastHostNanos;

    /** The host's PC, every frame: measures how evenly those near the joiner go along here. */
    private void measureHost(SpherePosition joiner) {
        long nanos = System.nanoTime();
        float seconds = lastHostNanos == 0L ? 0f : (nanos - lastHostNanos) / 1e9f;
        lastHostNanos = nanos;
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Vehicles.Vehicle v : inhabitants.vehiclesNow()) {
            if (SphereMath.distance(joiner, v.position()) > VEHICLE_REACH) continue;
            seen.add(v);
            Track t = hostTracks.computeIfAbsent(v, k -> new Track());
            if (t.at != null) measure(t, SphereMath.distance(t.at, v.position()), seconds, 5.0);
            t.at = v.position();
        }
        inhabitants.forEachOut(p -> {
            if (p.phase != Inhabitants.Phase.WALKING || p.position == null || SphereMath.distance(joiner, p.position) > PEOPLE_REACH) return;
            seen.add(p);
            Track t = hostTracks.computeIfAbsent(p, k -> new Track());
            if (t.at != null) measure(t, SphereMath.distance(t.at, p.position), seconds, 3.0);
            t.at = p.position;
        });
        hostTracks.keySet().retainAll(seen);
    }

    /** Reads one piece of a snapshot. */
    private void read(ByteBuffer m, InfrastructureManager infrastructure, long now) {
        int number = m.getInt();
        long takenAt = m.getLong();
        SpherePosition joiner = new SpherePosition(m.getDouble(), m.getDouble(), m.getDouble());
        // How far this PC's clock is ahead of the host's, less the network's quickest delivery:
        // the least seen, let creep up slowly (so a clock drifting the other way is followed)
        long behind = clock() - takenAt;
        hostBehind = hostBehind == Long.MIN_VALUE || behind < hostBehind ? behind : hostBehind + Math.max(1L, (behind - hostBehind) / 500L);
        Vehicles vehicles = inhabitants.vehicles();
        while (m.hasRemaining()) {
            byte kind = m.get();
            if (kind == DOOR) {
                long door = m.getLong();
                float amount = (m.get() & 0xFF) / 255f;
                // (a new snapshot's doors replace the last's)
                if (number != doorsSnapshot) {
                    if (number < doorsSnapshot) continue;
                    doorsSnapshot = number;
                    doorsOpen.clear();
                }
                doorsOpen.put(door, amount);
                if (!doorsById.containsKey(door)) findDoors(infrastructure, joiner);
                continue;
            }
            int id = m.getInt();
            byte flags = m.get();
            boolean household = (flags & HOUSEHOLD) != 0;
            if (kind == VEHICLE) {
                SpherePosition place = getPlace(m, joiner);
                double heading = angle(m.getShort());
                Replica r = replicas.get(id);
                if ((flags & WITH_LOOKS) != 0) {
                    int nation = m.getInt();
                    long looks = m.getLong();
                    if (r == null) replicas.put(id, r = new Replica(vehicles.replica(nation, looks, household)));
                }
                if (r == null || !(r.thing instanceof Vehicles.Vehicle v)) continue;
                v.parked = (flags & PARKED) != 0;
                add(r, new Sample(takenAt, place, heading, 0f, 0f), now);
            } else if (kind == PERSON) {
                boolean driving = (flags & DRIVING) != 0;
                int drivingId = -1, carrying = 0, boughtFrom = 0, packing = 0;
                Sample sample = null;
                if (driving) {
                    drivingId = m.getInt();
                } else {
                    SpherePosition place = getPlace(m, joiner);
                    double heading = angle(m.getShort());
                    float walking = (m.get() & 0xFF) / 255f;
                    float lift = m.getShort() / 100f;
                    carrying = m.get();
                    boughtFrom = m.get();
                    packing = m.get();
                    sample = new Sample(takenAt, place, heading, walking, lift);
                }
                Replica r = replicas.get(id);
                if ((flags & WITH_LOOKS) != 0) {
                    int nation = m.getInt();
                    long looks = m.getLong();
                    if (r == null) replicas.put(id, r = new Replica(inhabitants.replicaPerson(nation, looks, household)));
                }
                if (r == null || !(r.thing instanceof Inhabitants.Person p)) continue;
                r.drivingId = drivingId;
                if (sample != null) {
                    // (out of a vehicle: walked on from where they got out, not slid from where they last walked)
                    if (r.drawnAt == null) r.samples.clear();
                    add(r, sample, now);
                    p.carrying = carrying;
                    p.boughtFrom = boughtFrom;
                    p.packing = packing;
                } else {
                    r.heardAt = now;
                }
            } else {
                // (an entry of a kind this PC doesn't know: the rest of the piece can't be read)
                System.out.println("[MP] Ignored the rest of a TOWNSFOLK piece at an entry of unknown kind " + kind);
                return;
            }
        }
    }

    private static SpherePosition getPlace(ByteBuffer m, SpherePosition joiner) {
        return new SpherePosition(joiner.x() + m.getFloat(), joiner.y() + m.getFloat(), joiner.z() + m.getFloat());
    }

    /** A new sample for one, in order of when it was taken (one come out of order slotted in, one from a snapshot already had replacing it). */
    private static void add(Replica r, Sample sample, long now) {
        r.heardAt = now;
        List<Sample> samples = r.samples;
        int i = samples.size();
        while (i > 0 && samples.get(i - 1).at() > sample.at()) i--;
        if (i > 0 && samples.get(i - 1).at() == sample.at()) samples.set(i - 1, sample);
        else samples.add(i, sample);
        while (samples.size() > SAMPLES_KEPT) samples.remove(0);
    }

    /**
     * Where one was at a moment (the host's time): between the samples either side of it, or
     * carried on along its way a little past the latest (should snapshots be late or lost),
     * held there after that; before the earliest, at the earliest.
     */
    private static Sample sampleAt(Replica r, long at) {
        List<Sample> samples = r.samples;
        int n = samples.size();
        if (n == 1 || at <= samples.get(0).at()) return samples.get(0);
        int k = 1;
        while (k < n - 1 && samples.get(k).at() < at) k++;
        Sample a = samples.get(k - 1), b = samples.get(k);
        if (b.at() <= a.at()) return b;
        double f = Math.min((double) (at - a.at()) / (b.at() - a.at()), 1.0 + (double) PREDICT_MS / (b.at() - a.at()));
        // (on the datum: so near together, a straight line between them and back down to it)
        SpherePosition p = SphereMath.atAltitude(new SpherePosition(a.place().x() + (b.place().x() - a.place().x()) * f,
                a.place().y() + (b.place().y() - a.place().y()) * f, a.place().z() + (b.place().z() - a.place().z()) * f), 0.0);
        double turn = Math.atan2(Math.sin(b.heading() - a.heading()), Math.cos(b.heading() - a.heading()));
        float g = (float) Math.min(1.0, f);
        return new Sample(at, p, a.heading() + turn * Math.min(1.0, f), a.walking() + (b.walking() - a.walking()) * g,
                a.lift() + (b.lift() - a.lift()) * g);
    }

    /** Files the front doors round the joiner by id (again only once they've gone a way on). */
    private void findDoors(InfrastructureManager infrastructure, SpherePosition joiner) {
        if (doorsFoundAt != null && SphereMath.distance(doorsFoundAt, joiner) < 200.0) return;
        doorsFoundAt = joiner;
        doorsById.clear();
        for (InfrastructureManager.Doorway door : infrastructure.doorwaysNear(joiner, (float) PEOPLE_REACH + 300f)) doorsById.put(door.id, door);
    }

    /** Forgets everyone (a new round, or the session over). */
    void clear() {
        sent.clear();
        replicas.clear();
        doorsOpen.clear();
        doorsById.clear();
        doorsFoundAt = null;
        doorsSnapshot = -1;
        hostBehind = Long.MIN_VALUE;
    }
}
