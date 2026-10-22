/**
 * One part of InfrastructureManager: the code for one kind of thing (bridges, houses, signs...),
 * working on the manager's state through infra. The manager makes one of each and keeps it.
 */
abstract class InfraPart extends InfraGeometry {

    final InfrastructureManager infra;

    InfraPart(InfrastructureManager infra) {
        this.infra = infra;
    }

    @Override
    PerlinNoise terrain() {
        return infra.terrainNoise;
    }
}
