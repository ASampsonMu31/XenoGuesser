import gmaths.*;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;

import java.util.Map;

public class InfrastructureObject {
    public enum Type { SIGN, BORDER_POST, ROAD }
    
    public Type type;
    public Vec3 position;
    public int nationId;
    public Mat4 modelMatrix;
    
    public Mat4 leftPostMatrix;
    public Mat4 rightPostMatrix;
    public Mat4 frontBoardMatrix;
    public Mat4 backBoardMatrix;
    
    // --- TEXT DATA ---
    public int[] textString;
    public int stringLength;

    // --- Custom mesh for chunk-based roads ---
    public Mesh customMesh;

    public InfrastructureObject(Type type, Vec3 position, int nationId, float rotationY, int[] textString) {
        this.type = type;
        this.position = position;
        this.nationId = nationId;
        this.customMesh = null;
        
        int maxShaderCapacity = 512;
        int inputLen = (textString != null) ? textString.length : 0;
        
        this.stringLength = Math.min(inputLen, maxShaderCapacity);
        this.textString = new int[maxShaderCapacity];
        
        if (textString != null && this.stringLength > 0) {
            System.arraycopy(textString, 0, this.textString, 0, this.stringLength);
        }
        
        this.modelMatrix = Mat4Transform.translate(position);
        this.modelMatrix = Mat4.multiply(this.modelMatrix, Mat4Transform.rotateAroundY(rotationY));
        
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

    // Overload 1: Backwards compatibility for existing calls without Camera parameter
    public void render(GL3 gl, Vec3 ambientLight, float nightProportion, 
                        Map<Integer, Model> signModelsByNation, 
                        Map<Integer, Model> postModelsByNation,
                        Texture alphabetAtlas, int atlasSize, int writingDirection,
                        Shader solidShader, Material roadMaterial) {
        render(gl, null, ambientLight, nightProportion, signModelsByNation, postModelsByNation, 
               alphabetAtlas, atlasSize, writingDirection, solidShader, roadMaterial);
    }

    // Overload 2: Full render call including Camera for solidShader projection
    public void render(GL3 gl, Camera camera, Vec3 ambientLight, float nightProportion, 
                        Map<Integer, Model> signModelsByNation, 
                        Map<Integer, Model> postModelsByNation,
                        Texture alphabetAtlas, int atlasSize, int writingDirection,
                        Shader solidShader, Material roadMaterial) {
                            
        if (this.type == Type.SIGN) {
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
                
                // 1. FRONT SIDE
                signShader.setInt(gl, "stringLength", this.stringLength);
                billboardModel.setModelMatrix(this.frontBoardMatrix);
                billboardModel.render(gl, ambientLight, nightProportion);

                // 2. BACK SIDE
                signShader.setInt(gl, "stringLength", 0);
                billboardModel.setModelMatrix(this.backBoardMatrix);
                billboardModel.render(gl, ambientLight, nightProportion);
            }
        } else if (this.type == Type.ROAD && this.customMesh != null) {
            if (solidShader == null) return;

            // 1. Bind shader program
            solidShader.use(gl);

            // The standard vertex shader consumes a combined MVP matrix.
            if (camera != null) {
                Mat4 viewProjection = Mat4.multiply(camera.getPerspectiveMatrix(), camera.getViewMatrix());
                Mat4 mvp = Mat4.multiply(viewProjection, this.modelMatrix);
                int mvpLoc = gl.glGetUniformLocation(solidShader.getID(), "mvpMatrix");
                if (mvpLoc != -1) gl.glUniformMatrix4fv(mvpLoc, 1, false, mvp.toFloatArrayForGLSL(), 0);
                int viewPosLoc = gl.glGetUniformLocation(solidShader.getID(), "viewPos");
                Vec3 viewPos = camera.getPosition();
                if (viewPosLoc != -1) gl.glUniform3f(viewPosLoc, viewPos.x, viewPos.y, viewPos.z);
            }

            // 3. Send transformation matrix
            int modelLoc = gl.glGetUniformLocation(solidShader.getID(), "model");
            if (modelLoc != -1 && this.modelMatrix != null) {
                gl.glUniformMatrix4fv(modelLoc, 1, false, this.modelMatrix.toFloatArrayForGLSL(), 0);
            }

            // 4. Set material uniforms directly on solidShader
            if (roadMaterial != null) {
                Vec3 amb = roadMaterial.getAmbient();
                Vec3 diff = roadMaterial.getDiffuse();
                Vec3 spec = roadMaterial.getSpecular();
                Vec3 emi = roadMaterial.getEmission();
                
                int ambLoc = gl.glGetUniformLocation(solidShader.getID(), "material.ambient");
                if (ambLoc != -1) gl.glUniform3f(ambLoc, amb.x, amb.y, amb.z);
                
                int diffLoc = gl.glGetUniformLocation(solidShader.getID(), "material.diffuse");
                if (diffLoc != -1) gl.glUniform3f(diffLoc, diff.x, diff.y, diff.z);
                
                int specLoc = gl.glGetUniformLocation(solidShader.getID(), "material.specular");
                if (specLoc != -1) gl.glUniform3f(specLoc, spec.x, spec.y, spec.z);
                
                int emiLoc = gl.glGetUniformLocation(solidShader.getID(), "material.emission");
                if (emiLoc != -1) gl.glUniform3f(emiLoc, emi.x, emi.y, emi.z);
                
                int shinLoc = gl.glGetUniformLocation(solidShader.getID(), "material.shininess");
                if (shinLoc != -1) gl.glUniform1f(shinLoc, roadMaterial.getShininess());
            }

            int ambientLoc = gl.glGetUniformLocation(solidShader.getID(), "ambientLight");
            if (ambientLoc != -1) gl.glUniform3f(ambientLoc, ambientLight.x, ambientLight.y, ambientLight.z);

            gl.glDisable(GL3.GL_CULL_FACE);
            this.customMesh.render(gl);
            gl.glEnable(GL3.GL_CULL_FACE);
        }
    }
}