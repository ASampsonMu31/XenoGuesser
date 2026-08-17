import gmaths.*;
import com.jogamp.opengl.GL3;
import java.util.Map;

public class InfrastructureObject {
    public enum Type { SIGN, BORDER_POST, ROAD }
    
    public Type type;
    public Vec3 position;
    public int nationId;
    public Mat4 modelMatrix;
    
    // Pre-baked matrices for composite sign parts
    public Mat4 leftPostMatrix;
    public Mat4 rightPostMatrix;
    public Mat4 frontBoardMatrix;
    public Mat4 backBoardMatrix;

    public InfrastructureObject(Type type, Vec3 position, int nationId, float rotationY) {
        this.type = type;
        this.position = position;
        this.nationId = nationId;
        
        // Base anchors exactly to the terrain position with rotation applied
        this.modelMatrix = Mat4Transform.translate(position);
        this.modelMatrix = Mat4.multiply(this.modelMatrix, Mat4Transform.rotateAroundY(rotationY));
        
        // Pre-bake component transformations once at creation
        if (this.type == Type.SIGN) {
            float postSpacing = 15.0f;
            float postHeight = 45.0f;  
            Mat4 postScale = Mat4Transform.scale(1.0f, postHeight, 1.0f);
            
            Mat4 leftShift = Mat4Transform.translate(-postSpacing, postHeight / 2.0f, 0.0f);
            this.leftPostMatrix = Mat4.multiply(this.modelMatrix, Mat4.multiply(leftShift, postScale));
            
            Mat4 rightShift = Mat4Transform.translate(postSpacing, postHeight / 2.0f, 0.0f);
            this.rightPostMatrix = Mat4.multiply(this.modelMatrix, Mat4.multiply(rightShift, postScale));
            
            float boardWidth = postSpacing * 2.0f; 
            float boardHeight = 22.0f;             
            float boardCenterY = 32.0f;            
            
            Mat4 boardShift = Mat4Transform.translate(0.0f, boardCenterY, 0.0f);
            Mat4 boardScale = Mat4Transform.scale(boardWidth, 1.0f, boardHeight); 
            
            Mat4 frontRot = Mat4Transform.rotateAroundX(90.0f); 
            Mat4 frontTransform = Mat4.multiply(boardShift, Mat4.multiply(frontRot, boardScale));
            this.frontBoardMatrix = Mat4.multiply(this.modelMatrix, frontTransform);
            
            Mat4 backRot = Mat4Transform.rotateAroundX(-90.0f); 
            Mat4 backTransform = Mat4.multiply(boardShift, Mat4.multiply(backRot, boardScale));
            this.backBoardMatrix = Mat4.multiply(this.modelMatrix, backTransform);
        }
    }

    // NEW: The object now knows how to draw itself
    public void render(GL3 gl, Vec3 ambientLight, float nightProportion, 
                        Map<Integer, Model> signModelsByNation, 
                        Map<Integer, Model> postModelsByNation) {
        if (this.type == Type.SIGN) {
            Model billboardModel = signModelsByNation.get(this.nationId);
            Model postModel = postModelsByNation.get(this.nationId);
            
            if (billboardModel != null && postModel != null) {
                // Draw Posts
                postModel.setModelMatrix(this.leftPostMatrix);
                postModel.render(gl, ambientLight, nightProportion);
                
                postModel.setModelMatrix(this.rightPostMatrix);
                postModel.render(gl, ambientLight, nightProportion);
                
                // Draw Billboards
                billboardModel.setModelMatrix(this.frontBoardMatrix);
                billboardModel.render(gl, ambientLight, nightProportion);

                billboardModel.setModelMatrix(this.backBoardMatrix);
                billboardModel.render(gl, ambientLight, nightProportion);
            }
        }
    }
}