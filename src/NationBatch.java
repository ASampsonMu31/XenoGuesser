import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Geometry accumulated for one nation within one geodesic cell, one builder per material,
 * in world coordinates (planet-centred; drawn camera-relative, as the terrain is).
 */
final class NationBatch {
    private final InfrastructureManager infra;
    final int nationId;
    final Map<String, MeshBuilder> builders = new LinkedHashMap<>();
    // The box round what's been built, in the world
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
    // What is being built now is in a frame of its own (D3), placed in the world by this
    MeshBuilder.Placement placement = InfraGeometry.OUTSIDE_FRAME;

    NationBatch(InfrastructureManager infra, int nationId) {
        this.infra = infra;
        this.nationId = nationId;
    }

    /** Builds from now on in a frame of its own, placed in the world by placement (null: between pieces). */
    void place(MeshBuilder.Placement placement) {
        this.placement = placement == null ? InfraGeometry.OUTSIDE_FRAME : placement;
        for (MeshBuilder builder : builders.values()) builder.setPlacement(this.placement);
    }

    MeshBuilder builder(String part) {
        MeshBuilder builder = builders.computeIfAbsent(part, ignored -> new MeshBuilder());
        builder.setPlacement(placement);
        return builder;
    }

    /** Takes in a point in the frame being built in, and room round it, for the batch's bounds. */
    void include(float x, float y, float z, float margin) {
        float[] p = { x, y, z };
        placement.place(p, new float[] { 0f, 1f, 0f });
        minX = Math.min(minX, p[0] - margin);
        maxX = Math.max(maxX, p[0] + margin);
        minY = Math.min(minY, p[1] - margin);
        maxY = Math.max(maxY, p[1] + margin);
        minZ = Math.min(minZ, p[2] - margin);
        maxZ = Math.max(maxZ, p[2] + margin);
    }

    InfrastructureObject build() {
        List<InfrastructureObject.BatchPart> parts = new ArrayList<>();
        for (Map.Entry<String, MeshBuilder> entry : builders.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            String part = entry.getKey();
            boolean doubleSided = part.equals("rail") || part.equals("band") || part.equals("fence") || part.equals("security") || part.equals("roadwall")
                    || part.equals("borderfence") || part.equals("barbed") || part.startsWith(BorderCrossings.BOOTH_PART)
                    || part.startsWith("curtain") || part.equals("flag") || part.equals("goods") || part.startsWith("bollard") || part.startsWith("reflector")
                    || part.startsWith("pole") || part.startsWith("insulator") || part.equals("wire");
                            boolean paint = part.equals("line") || part.equals("driveway");
            int wornAt = part.indexOf(HouseBuilder.WORN);
            Material material = infra.infraMaterials.material(wornAt < 0 ? part : part.substring(0, wornAt), nationId);
            InfrastructureObject.BatchPart batchPart = new InfrastructureObject.BatchPart(entry.getValue().vertexArray(), entry.getValue().indexArray(),
                    material, doubleSided, paint, material.diffuseMapExists(), part.equals("glass"), part);
            if (wornAt >= 0) batchPart.weathering = Integer.parseInt(part.substring(wornAt + HouseBuilder.WORN.length())) / 3f;
            if (part.equals("dirt")) batchPart.trackGrit = 0.05f + 0.95f * infra.trackGrit(nationId);
            parts.add(batchPart);
        }
        if (parts.isEmpty() || minX > maxX) {
            return null;
        }
        SpherePosition centre = new SpherePosition((minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5);
        double dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        float radius = (float) (0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz));
        InfrastructureObject batch = InfrastructureObject.createBatch(centre, nationId, radius, parts);
        // The goods on shop shelves and small flags vanish into the distance before the buildings
        // do; what's seen through windows (curtains, rooms, shop interiors) lasts nearly as long
        batch.setDetailDistance(p -> p.name.equals("goods") || p.name.equals("shelf") || p.name.equals("flag"), 700f);
        batch.setDetailDistance(p -> p.name.startsWith("curtain") || p.name.equals("interior") || p.name.equals("shopinterior"), 1700f);
        // (and houses' small ornaments and spikes before the houses themselves)
        batch.setDetailDistance(p -> p.name.startsWith("orn@") || p.name.startsWith("spike@"), 1500f);
        return batch;
    }
}
