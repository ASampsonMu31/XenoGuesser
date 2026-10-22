import com.xenoguesser.math.Vector3;

/** Another road near a segment, its centreline put in that segment's frame (junction tests run there). */
final class Crossing {
    final RoadSegment road;
    final Vector3 start, end;

    Crossing(RoadSegment road, RoadSegment frameOf) {
        this.road = road;
        this.start = InfraGeometry.inFrame(frameOf.frame, road.from);
        this.end = InfraGeometry.inFrame(frameOf.frame, road.to);
    }
}
