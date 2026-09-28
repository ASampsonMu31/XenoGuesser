import com.xenoguesser.math.*;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;

import java.util.Map;

public class InfrastructureObject {
    public enum Type { SIGN, BORDER_POST, ROAD }
    
    public Type type;
    public Vector3 position;
    public int nationId;
    public Matrix4 modelMatrix;
    
    public Matrix4 leftPostMatrix;
    public Matrix4 rightPostMatrix;
    public Matrix4 frontBoardMatrix;
    public Matrix4 backBoardMatrix;
    
    // --- TEXT DATA ---
    public int[] textString;
    public int stringLength;
    private final float[] roadVertices;
    private final int[] roadIndices;
    private Model roadModel;

    public InfrastructureObject(Type type, Vector3 position, int nationId, float rotationY, int[] textString) {
        this(type, position, nationId, rotationY, textString, null, null);
    }

    public InfrastructureObject(Type type, Vector3 position, int nationId, float rotationY, int[] textString,
                                float[] roadVertices, int[] roadIndices) {
        this.type = type;
        this.position = position;
        this.nationId = nationId;
        this.roadVertices = roadVertices;
        this.roadIndices = roadIndices;
        
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

    public void initializeRoadModel(GL3 gl, Shader shader, Material material, Renderer renderer,
                                    Light[] lights, Camera camera) {
        if (type == Type.ROAD && roadModel == null && roadVertices != null && roadIndices != null) {
            Mesh mesh = new Mesh(gl, roadVertices, roadIndices);
            roadModel = new Model("road", mesh, new Matrix4(1), shader, material, renderer, lights, camera);
        }
    }

    public void render(GL3 gl, Vector3 ambientLight, float nightProportion, 
                    Map<Integer, Model> signModelsByNation, 
                    Map<Integer, Model> postModelsByNation,
                    Texture alphabetAtlas, int atlasSize, int writingDirection) {
        if (this.type == Type.ROAD) {
            if (roadModel != null) {
                gl.glDisable(GL3.GL_CULL_FACE);
                roadModel.render(gl, ambientLight, nightProportion);
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