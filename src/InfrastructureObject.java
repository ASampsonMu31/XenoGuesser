import com.xenoguesser.math.*;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;

import java.util.Map;

public class InfrastructureObject {
    public enum Type { SIGN, BORDER_POST, ROAD, BUILDING }

    private static final float DOOR_THICKNESS = 1.5f;
    
    public Type type;
    public Vector3 position;
    public int nationId;
    public Matrix4 modelMatrix;
    
    public Matrix4 leftPostMatrix;
    public Matrix4 rightPostMatrix;
    public Matrix4 frontBoardMatrix;
    public Matrix4 backBoardMatrix;

    public Matrix4 wallMatrix;
    public Matrix4 roofMatrix;
    public Matrix4 doorMatrix;

    // Extra reach beyond the object's anchor point, used for behind-camera culling
    public float boundingRadius;
    
    // --- TEXT DATA ---
    public int[] textString;
    public int stringLength;
    private float[] roadVertices;
    private int[] roadIndices;
    private float[] lineVertices;
    private int[] lineIndices;
    private Model roadModel;
    private Model lineModel;

    public InfrastructureObject(Type type, Vector3 position, int nationId, float rotationY, int[] textString) {
        this.type = type;
        this.position = position;
        this.nationId = nationId;
        
        // --- FIXED: INCREASE MAX LIMIT TO 512 CHARACTERS ---
        int maxShaderCapacity = 512;
        int inputLen = (textString != null) ? textString.length : 0;
        
        this.stringLength = Math.min(inputLen, maxShaderCapacity);
        this.textString = new int[maxShaderCapacity]; // Internal array capacity matches GLSL
        
        if (textString != null && this.stringLength > 0) {
            System.arraycopy(textString, 0, this.textString, 0, this.stringLength);
        }
        
        this.modelMatrix = Matrix4Transform.translate(position);
        this.modelMatrix = Matrix4.multiply(this.modelMatrix, Matrix4Transform.rotateAroundY(rotationY));
        
        if (this.type == Type.SIGN) {
            float postSpacing = 15.0f;
            float postHeight = 45.0f;  
            Matrix4 postScale = Matrix4Transform.scale(1.0f, postHeight, 1.0f);
            
            Matrix4 leftShift = Matrix4Transform.translate(-postSpacing, postHeight / 2.0f, 0.0f);
            this.leftPostMatrix = Matrix4.multiply(this.modelMatrix, Matrix4.multiply(leftShift, postScale));
            
            Matrix4 rightShift = Matrix4Transform.translate(postSpacing, postHeight / 2.0f, 0.0f);
            this.rightPostMatrix = Matrix4.multiply(this.modelMatrix, Matrix4.multiply(rightShift, postScale));
            
            float boardWidth = postSpacing * 2.0f; 
            float boardHeight = 22.0f;             
            float boardCenterY = 32.0f;            
            
            Matrix4 boardShift = Matrix4Transform.translate(0.0f, boardCenterY, 0.0f);
            Matrix4 boardScale = Matrix4Transform.scale(boardWidth, 1.0f, boardHeight); 
            
            Matrix4 frontRot = Matrix4Transform.rotateAroundX(90.0f); 
            Matrix4 frontTransform = Matrix4.multiply(boardShift, Matrix4.multiply(frontRot, boardScale));
            this.frontBoardMatrix = Matrix4.multiply(this.modelMatrix, frontTransform);
            
            Matrix4 backRot = Matrix4Transform.rotateAroundX(-90.0f); 
            Matrix4 backTransform = Matrix4.multiply(boardShift, Matrix4.multiply(backRot, boardScale));
            this.backBoardMatrix = Matrix4.multiply(this.modelMatrix, backTransform);
        }
    }

    public static InfrastructureObject createRoad(Vector3 position, int nationId, float boundingRadius,
                                                  float[] roadVertices, int[] roadIndices,
                                                  float[] lineVertices, int[] lineIndices) {
        InfrastructureObject road = new InfrastructureObject(Type.ROAD, position, nationId, 0.0f, null);
        road.boundingRadius = boundingRadius;
        road.roadVertices = roadVertices;
        road.roadIndices = roadIndices;
        road.lineVertices = lineVertices;
        road.lineIndices = lineIndices;
        return road;
    }

    /**
     * Builds a building whose walls rise from basePosition. The door sits on the
     * local +Z wall, doorBaseOffset above the base so it meets the ground there.
     */
    public static InfrastructureObject createBuilding(Vector3 basePosition, int nationId, float rotationY,
                                                      float width, float depth, float wallHeight,
                                                      float roofHeight, float roofOverhang,
                                                      float doorWidth, float doorHeight, float doorBaseOffset) {
        InfrastructureObject building = new InfrastructureObject(Type.BUILDING, basePosition, nationId, rotationY, null);

        Matrix4 wallTransform = Matrix4Transform.scale(width, wallHeight, depth);
        building.wallMatrix = Matrix4.multiply(building.modelMatrix, wallTransform);

        float roofWidth = width + 2.0f * roofOverhang;
        float roofDepth = depth + 2.0f * roofOverhang;
        Matrix4 roofTransform = Matrix4.multiply(
            Matrix4Transform.translate(0.0f, wallHeight, 0.0f),
            Matrix4Transform.scale(roofWidth, roofHeight, roofDepth)
        );
        building.roofMatrix = Matrix4.multiply(building.modelMatrix, roofTransform);

        Matrix4 doorTransform = Matrix4.multiply(
            Matrix4Transform.translate(0.0f, doorBaseOffset, depth * 0.5f),
            Matrix4Transform.scale(doorWidth, doorHeight, DOOR_THICKNESS)
        );
        building.doorMatrix = Matrix4.multiply(building.modelMatrix, doorTransform);

        float horizontalRadius = 0.5f * (float) Math.sqrt(roofWidth * roofWidth + roofDepth * roofDepth);
        building.boundingRadius = Math.max(horizontalRadius, wallHeight + roofHeight);
        return building;
    }

    public void initializeRoadModel(GL3 gl, Shader shader, Material material, Material lineMaterial,
                                    Renderer renderer, Light[] lights, Camera camera) {
        if (type == Type.ROAD && roadModel == null && roadVertices != null && roadIndices != null) {
            Mesh mesh = new Mesh(gl, roadVertices, roadIndices);
            roadModel = new Model("road", mesh, new Matrix4(1), shader, material, renderer, lights, camera);

            if (lineMaterial != null && lineIndices != null && lineIndices.length > 0) {
                Mesh lineMesh = new Mesh(gl, lineVertices, lineIndices);
                lineModel = new Model("road_lines", lineMesh, new Matrix4(1), shader, lineMaterial, renderer, lights, camera);
            }
        }
    }

    public void renderBuilding(GL3 gl, Vector3 ambientLight, float nightProportion,
                               Model wallModel, Model roofModel, Model doorModel) {
        if (type != Type.BUILDING || wallModel == null || roofModel == null || doorModel == null) {
            return;
        }
        wallModel.setModelMatrix(wallMatrix);
        wallModel.render(gl, ambientLight, nightProportion);

        roofModel.setModelMatrix(roofMatrix);
        roofModel.render(gl, ambientLight, nightProportion);

        doorModel.setModelMatrix(doorMatrix);
        doorModel.render(gl, ambientLight, nightProportion);
    }

    public void render(GL3 gl, Vector3 ambientLight, float nightProportion, 
                    Map<Integer, Model> signModelsByNation, 
                    Map<Integer, Model> postModelsByNation,
                    Texture alphabetAtlas, int atlasSize, int writingDirection) {
        if (this.type == Type.ROAD) {
            if (roadModel != null) {
                gl.glDisable(GL3.GL_CULL_FACE);
                roadModel.render(gl, ambientLight, nightProportion);
                if (lineModel != null) {
                    // Pull the paint towards the camera so it never z-fights the asphalt
                    gl.glEnable(GL3.GL_POLYGON_OFFSET_FILL);
                    gl.glPolygonOffset(-1.0f, -2.0f);
                    lineModel.render(gl, ambientLight, nightProportion);
                    gl.glDisable(GL3.GL_POLYGON_OFFSET_FILL);
                }
                gl.glEnable(GL3.GL_CULL_FACE);
            }
        } else if (this.type == Type.SIGN) {
            Model billboardModel = signModelsByNation.get(this.nationId);
            Model postModel = postModelsByNation.get(this.nationId);
            
            if (billboardModel != null && postModel != null) {
                // Render Support Posts
                postModel.setModelMatrix(this.leftPostMatrix);
                postModel.render(gl, ambientLight, nightProportion);
                
                postModel.setModelMatrix(this.rightPostMatrix);
                postModel.render(gl, ambientLight, nightProportion);
                
                // Setup Billboard Shader
                Shader signShader = billboardModel.shader;
                signShader.use(gl);
                
                int stringLoc = gl.glGetUniformLocation(signShader.getID(), "textString");
                if (stringLoc != -1) {
                    gl.glUniform1iv(stringLoc, 512, this.textString, 0);
                }
                
                signShader.setInt(gl, "atlasSize", atlasSize);
                signShader.setInt(gl, "writingDirection", writingDirection);
                
                if (alphabetAtlas != null) {
                    gl.glActiveTexture(GL3.GL_TEXTURE3);
                    alphabetAtlas.bind(gl);
                    signShader.setInt(gl, "alphabetAtlas", 3);
                }
                
                // --- 1. FRONT SIDE (Draws full text) ---
                signShader.setInt(gl, "stringLength", this.stringLength);
                billboardModel.setModelMatrix(this.frontBoardMatrix);
                billboardModel.render(gl, ambientLight, nightProportion);

                // --- 2. BACK SIDE (Forces string length to 0 = Blank surface) ---
                signShader.setInt(gl, "stringLength", 0);
                billboardModel.setModelMatrix(this.backBoardMatrix);
                billboardModel.render(gl, ambientLight, nightProportion);
            }
        }
    }
}