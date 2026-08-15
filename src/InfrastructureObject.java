import gmaths.*;

public class InfrastructureObject {
    public enum Type { SIGN, BORDER_POST, ROAD }
    
    public Type type;
    public Vec3 position;
    public int nationId;
    public Mat4 modelMatrix;

    public InfrastructureObject(Type type, Vec3 position, int nationId) {
        this.type = type;
        this.position = position;
        this.nationId = nationId;
        
        // Base anchors exactly to the terrain position
        this.modelMatrix = Mat4Transform.translate(position);
    }
}