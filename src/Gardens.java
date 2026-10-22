import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Each house's land: its driveway (laid, and filed for lookups), its fence round the plot or
 * along the front (joined with the neighbours'), and what counts as private ground.
 */
final class Gardens extends InfraPart {

    Gardens(InfrastructureManager infra) {
        super(infra);
    }

    /**
     * The driveway's surface: paving laid over the ground from the road's edge back to the
     * back of the house, and noted so grass doesn't grow through it.
     */
    void bakeDriveway(NationBatch batch, House house) {
        MeshBuilder paving = batch.builder("driveway");
        float cx = (house.driveMinX + house.driveMaxX) * 0.5f;
        float frontZ = followKerb(house, cx)[1] + FRONT_FENCE_GAP;
        float backZ = -house.depth * 0.5f;
        int along = Math.max(2, Math.round((frontZ - backZ) / 4f)), across = Math.max(2, Math.round(DRIVEWAY_WIDTH / 4f));
        int first = -1;
        for (int i = 0; i <= along; i++) {
            float lz = backZ + (frontZ - backZ) * i / along;
            for (int j = 0; j <= across; j++) {
                float lx = house.driveMinX + (house.driveMaxX - house.driveMinX) * j / across;
                float y = groundAt(house, lx, lz);
                float nx = (groundAt(house, lx - 1f, lz) - groundAt(house, lx + 1f, lz)) * 0.5f;
                float nz = (groundAt(house, lx, lz - 1f) - groundAt(house, lx, lz + 1f)) * 0.5f;
                float length = (float) Math.sqrt(nx * nx + 1f + nz * nz);
                int index = paving.addVertex(lx, y + DRIVEWAY_LIFT, lz, nx / length, 1f / length, nz / length, lx / 12f, lz / 12f);
                if (first < 0) first = index;
                batch.include(lx, y, lz, 0f);
            }
        }
        int row = across + 1;
        for (int i = 0; i < along; i++) {
            for (int j = 0; j < across; j++) {
                int a = first + i * row + j, b = a + 1, c = a + row, d = c + 1;
                // Wound to face up (turning the house keeps it so)
                paving.addTriangle(a, c, b);
                paving.addTriangle(b, c, d);
            }
        }
    }

    /** Notes where a driveway is (filed at its house, reaching its far corner), so grass keeps off it. */
    void noteDriveway(House house) {
        float cx = (house.driveMinX + house.driveMaxX) * 0.5f;
        house.driveFrontZ = followKerb(house, cx)[1] + FRONT_FENCE_GAP;
        float x = Math.max(Math.abs(house.driveMinX), Math.abs(house.driveMaxX));
        float z = Math.max(house.depth * 0.5f, house.driveFrontZ);
        infra.drivewayIndex.add(house.frame.origin(), Math.hypot(x, z) + 1.0, house);
    }

    /** Whether a place (on the datum) is on a driveway, worked in its house's frame. */
    /** Whether a place is on (or within clearance of) someone's land: a house's plot, its garden, or its driveway. */
    public boolean isPrivateGround(SpherePosition place, float clearance) {
        place = SphereMath.atAltitude(place, 0.0);
        if (infra.isPlotLocation(place, clearance)) return true;
        for (House house : infra.drivewayIndex.near(place, clearance)) {
            float[] local = local(house, place);
            if (local[0] >= house.driveMinX - clearance && local[0] <= house.driveMaxX + clearance
                    && local[1] >= -house.depth * 0.5f - clearance && local[1] <= house.driveFrontZ + clearance) return true;
        }
        return false;
    }

    boolean onDriveway(SpherePosition place) {
        for (House house : infra.drivewayIndex.near(place, 0.0)) {
            float[] local = local(house, place);
            if (local[0] >= house.driveMinX && local[0] <= house.driveMaxX
                    && local[1] >= -house.depth * 0.5f && local[1] <= house.driveFrontZ) return true;
        }
        return false;
    }

    /**
     * The house's fence: its front follows the edge of the road it faces, with a gate in
     * front of the door; the rest outlines the plot, an irregular piece of it, or nothing
     * (front boundary only). A side a neighbour already fences is left to them.
     */
    void bakeFence(NationBatch batch, House house) {
        FenceStyle style = infra.fenceStyles.get(house.nationId);
        String part = !house.secure ? "fence" : style.securityType == FenceStyle.SecurityType.HIGH_WALL ? "secwall" : "security";
        MeshBuilder builder = batch.builder(part);
        builder.setPlanarTextureScale(1.0f / style.tileSize);

        // Front, right to left, pulled onto the line of the kerb
        List<float[]> front = new ArrayList<>();
        float span = house.plotMaxX - house.plotMinX;
        int samples = Math.max(2, (int) Math.ceil(span / 5f));
        for (int i = 0; i <= samples; i++) {
            float lx = house.plotMaxX - span * i / samples;
            front.add(followKerb(house, lx));
        }
        float[] frontRight = front.get(0), frontLeft = front.get(front.size() - 1);
        List<float[]> run = new ArrayList<>();
        // The gate in front of the door, widened to take in the driveway if there is one
        float gapMin = -GATE_HALF_WIDTH, gapMax = GATE_HALF_WIDTH;
        if (house.drivewaySide != 0) {
            gapMin = Math.min(gapMin, house.driveMinX - 1f);
            gapMax = Math.max(gapMax, house.driveMaxX + 1f);
        }
        for (float[] point : front) {
            if (point[0] > gapMin && point[0] < gapMax) {
                fenceLine(batch, builder, house, style, run);
                run = new ArrayList<>();
                continue;
            }
            run.add(point);
        }
        fenceLine(batch, builder, house, style, run);

        float minX = house.plotMinX, maxX = house.plotMaxX, minZ = house.plotMinZ;
        if (house.fenceShape == FENCE_FRONT_ONLY) {
            // Just short returns down each side
            float returnZ = Math.max(minZ, frontRight[1] - 8f);
            if (!house.sharedRight) fenceLine(batch, builder, house, style, List.of(frontRight, new float[] { maxX, returnZ }));
            fenceLine(batch, builder, house, style, List.of(new float[] { minX, Math.max(minZ, frontLeft[1] - 8f) }, frontLeft));
            return;
        }
        List<float[]> back = new ArrayList<>();
        if (house.fenceShape == FENCE_IRREGULAR) {
            // An irregular outline: back corners cut in by different amounts, the back edge kinked
            Random rand = new Random(house.seed ^ 0x1F3A5L);
            float depthSpan = frontRight[1] - minZ;
            back.add(new float[] { maxX, minZ + depthSpan * (0.15f + 0.35f * rand.nextFloat()) });
            back.add(new float[] { maxX - span * (0.1f + 0.25f * rand.nextFloat()), minZ });
            back.add(new float[] { minX + span * (0.3f + 0.4f * rand.nextFloat()), minZ + depthSpan * 0.15f * rand.nextFloat() });
            back.add(new float[] { minX, minZ + depthSpan * (0.1f + 0.3f * rand.nextFloat()) });
        } else {
            back.add(new float[] { maxX, minZ });
            back.add(new float[] { minX, minZ });
        }
        List<float[]> outline = new ArrayList<>();
        outline.add(frontRight);
        outline.addAll(back);
        outline.add(frontLeft);
        // The right side is the first stretch; a neighbour may already have fenced it
        if (house.sharedRight) {
            fenceLine(batch, builder, house, style, outline.subList(1, outline.size()));
        } else {
            fenceLine(batch, builder, house, style, outline);
        }
        if (house.paddock != null) {
            float[] p = house.paddock;
            fenceLine(batch, builder, house, style, List.of(new float[] { p[0], p[3] }, new float[] { p[0], p[2] },
                    new float[] { p[1], p[2] }, new float[] { p[1], p[3] }));
        }
    }

    /** A point on the front fence at local x: on the kerb line of the road the house faces, if it is near. */
    float[] followKerb(House house, float localX) {
        float bestDistance = Float.MAX_VALUE;
        float[] best = null;
        // (the road put in the house's frame)
        for (RoadSegment segment : infra.segmentsNear(world(house, localX, 0f, house.plotMaxZ), 60f)) {
            if (segment.pathIndex != house.frontagePath || segment.culDeSac) continue;
            float[] nearest = nearestOnLine(localX, house.plotMaxZ, local(house, segment.from), local(house, segment.to));
            float dx = localX - nearest[0], dz = house.plotMaxZ - nearest[1];
            float distance = (float) Math.sqrt(dx * dx + dz * dz);
            if (distance < bestDistance && distance > 1e-3f) {
                bestDistance = distance;
                float reach = segment.width * 0.5f + FRONT_FENCE_GAP;
                best = new float[] { nearest[0] + dx / distance * reach, nearest[1] + dz / distance * reach };
            }
        }
        if (best == null || bestDistance > 60f) return new float[] { localX, house.plotMaxZ };
        // Stay in front of the house and not too far from the plot's own line
        float z = Math.max(house.depth * 0.5f + 4f, Math.min(house.plotMaxZ + 20f, best[1]));
        return new float[] { localX, z };
    }

    /** Builds fence along a polyline of points in the house's frame. */
    private void fenceLine(NationBatch batch, MeshBuilder builder, House house, FenceStyle style, List<float[]> localPoints) {
        for (int i = 0; i + 1 < localPoints.size(); i++) {
            float[] a = localPoints.get(i), b = localPoints.get(i + 1);
            // (solid only where it's actually built: see fenceRun)
            fenceRun(batch, builder, style, house, a[0], a[1], b[0], b[1]);
        }
    }

    /** One straight run of a house's fence, between two points in its frame. */
    private void fenceRun(NationBatch batch, MeshBuilder builder, FenceStyle style, House house,
                          float startX, float startZ, float endX, float endZ) {
        boolean secure = house.secure;
        float length = (float) Math.hypot(endX - startX, endZ - startZ);
        if (length < 1.0f) {
            return;
        }
        int pieces = Math.max(1, Math.round(length / style.postSpacing));
        float dirX = (endX - startX) / length;
        float dirZ = (endZ - startZ) / length;
        float h = secure ? style.securityHeight : style.height;
        boolean posts = secure ? style.securityType != FenceStyle.SecurityType.HIGH_WALL
                : style.type != FenceStyle.Type.WALL && style.type != FenceStyle.Type.HEDGE;

        float[][] points = new float[pieces + 1][];
        for (int i = 0; i <= pieces; i++) {
            float t = (float) i / pieces;
            float x = startX + (endX - startX) * t;
            float z = startZ + (endZ - startZ) * t;
            points[i] = new float[] { x, groundAt(house, x, z), z };
            batch.include(x, points[i][1] + h, z, 2.0f);
        }

        for (int i = 0; i < pieces; i++) {
            float[] a = points[i];
            float[] b = points[i + 1];
            // A bending road may clip the corner of a garden; the fence stops short of it
            if (infra.isRoadLocation(world(house, (a[0] + b[0]) * 0.5f, 0f, (a[2] + b[2]) * 0.5f), 1.0f)) {
                continue;
            }
            infra.recordSegment(world(house, a[0], 0f, a[2]), world(house, b[0], 0f, b[2]), secure ? 0.8f : 0.4f, h);
            if (secure) {
                switch (style.securityType) {
                    case HIGH_WALL -> {
                        addPrism(builder, a, b, 2.2f, -1.0f, h);
                        addPrism(builder, a, b, 2.8f, h, h + 0.7f);
                        // A row of spikes along the coping
                        uprights(builder, a, b, dirX, dirZ, 3.0f, 0.15f, h + 0.7f, 1.6f);
                    }
                    case MESH -> {
                        addPrism(builder, a, b, 0.25f, 0.6f, 1.0f);
                        addPrism(builder, a, b, 0.25f, h * 0.5f - 0.2f, h * 0.5f + 0.2f);
                        addPrism(builder, a, b, 0.25f, h - 0.4f, h);
                        uprights(builder, a, b, dirX, dirZ, 2.4f, 0.1f, 0.2f, h - 0.2f);
                        // Strands of wire strung above
                        for (int wire = 1; wire <= 2; wire++) {
                            addPrism(builder, a, b, 0.12f, h + wire * 0.7f - 0.06f, h + wire * 0.7f + 0.06f);
                        }
                    }
                    default -> {
                        addPrism(builder, a, b, 0.35f, 1.0f, 1.5f);
                        addPrism(builder, a, b, 0.35f, h - 1.6f, h - 1.1f);
                        // Bars rising past the top rail into points
                        uprights(builder, a, b, dirX, dirZ, 2.3f, 0.2f, -0.3f, h + 0.9f);
                    }
                }
            } else {
                switch (style.type) {
                    case WALL:
                    case HEDGE:
                        addPrism(builder, a, b, style.thickness, -1.0f, h);
                        break;
                    case PANEL:
                        addPrism(builder, a, b, style.thickness, -0.5f, h);
                        break;
                    case RAIL:
                        addPrism(builder, a, b, 0.5f, h * 0.3f - 0.3f, h * 0.3f + 0.3f);
                        addPrism(builder, a, b, 0.5f, h * 0.6f - 0.3f, h * 0.6f + 0.3f);
                        addPrism(builder, a, b, 0.5f, h * 0.9f - 0.3f, h * 0.9f + 0.3f);
                        break;
                    case PICKET:
                    default:
                        addPrism(builder, a, b, 0.5f, h * 0.25f - 0.3f, h * 0.25f + 0.3f);
                        addPrism(builder, a, b, 0.5f, h * 0.7f - 0.3f, h * 0.7f + 0.3f);
                        uprights(builder, a, b, dirX, dirZ, 3.5f, 0.3f, -0.5f, h + 0.5f);
                        break;
                }
            }
            if (posts) {
                addBox(builder, a[0], a[1] - 1.0f, a[2], dirX, dirZ, 0.6f, 0.6f, h + 1.5f);
            }
        }
        float[] last = points[pieces];
        if (posts && !infra.isRoadLocation(world(house, last[0], 0f, last[2]), 1.0f)) {
            addBox(builder, last[0], last[1] - 1.0f, last[2], dirX, dirZ, 0.6f, 0.6f, h + 1.5f);
        }
    }

    static final float DRIVEWAY_LIFT = 0.5f;
}
