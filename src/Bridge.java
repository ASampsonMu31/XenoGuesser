/**
 * A stretch of a road over a river or a lake, from bank to bank (from and to: distances along
 * its path): its deck runs straight from the road's height at one end to the other's,
 * humped by hump in the middle and, if it's long, raised by rise over all but its ends
 * (climbing to it over ramp, a share of its length, at each), to stand clear of the water.
 */
final class Bridge {
    final float from, to, deckFrom, deckTo, rise, ramp, hump;
    final BridgeKind kind;

    Bridge(float from, float to, float deckFrom, float deckTo, float rise, float ramp, float hump, BridgeKind kind) {
        this.from = from;
        this.to = to;
        this.deckFrom = deckFrom;
        this.deckTo = deckTo;
        this.rise = rise;
        this.ramp = ramp;
        this.hump = hump;
        this.kind = kind;
    }

    /** How much of rise is reached a share f along: up its ramps, level between. */
    static float lift(float f, float ramp) {
        return smoothRamp(f / ramp) * smoothRamp((1f - f) / ramp);
    }

    static float smoothRamp(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    float length() {
        return to - from;
    }

    /** The deck's height (its top) a distance along the path. */
    float deck(float along) {
        float f = Math.max(0f, Math.min(1f, (along - from) / (to - from)));
        return deckFrom + (deckTo - deckFrom) * f + rise * lift(f, ramp) + hump * 4f * f * (1f - f);
    }

    boolean holds(float along) {
        return along >= from && along <= to;
    }
}
