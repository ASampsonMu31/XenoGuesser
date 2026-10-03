import com.xenoguesser.math.*;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.util.texture.Texture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InfrastructureObject {
    /** SIGN is a single roadside or garden sign; BATCH is pre-merged world-space geometry (roads, rails, houses, fences). */
    public enum Type { SIGN, BATCH }

    public Type type;
    public Vector3 position;
    public int nationId;
    public Matrix4 modelMatrix;

    public Matrix4 leftPostMatrix;
    public Matrix4 rightPostMatrix;
    public Matrix4 frontBoardMatrix;
    public Matrix4 backBoardMatrix;

    // Extra reach beyond the object's anchor point, used for behind-camera culling
    public float boundingRadius;

    // --- TEXT DATA ---
    public int[] textString;
    public int stringLength;

    /** One mesh of a batch, drawn with a single material. */
    public static final class BatchPart {
        final float[] vertices;
        final int[] indices;
        final Material material;
        final boolean doubleSided;
                final boolean paint;
        final boolean textured;
        Model model;

        public BatchPart(float[] vertices, int[] indices, Material material, boolean doubleSided, boolean paint, boolean textured) {
            this.vertices = vertices;
            this.indices = indices;
            this.material = material;
            this.doubleSided = doubleSided;
            this.paint = paint;
            this.textured = textured;
        }
    }

    private final List<BatchPart> batchParts = new ArrayList<>();

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

    public static InfrastructureObject createBatch(Vector3 position, int nationId, float boundingRadius, List<BatchPart> parts) {
        InfrastructureObject batch = new InfrastructureObject(Type.BATCH, position, nationId, 0.0f, null);
        batch.boundingRadius = boundingRadius;
        batch.batchParts.addAll(parts);
        return batch;
    }

        /** Uploads the batch meshes; must run on the GL thread. Textured parts use the texturing shader. */
    public void initializeBatch(GL3 gl, Shader shader, Shader texturedShader, Renderer renderer, Light[] lights, Camera camera) {
        for (BatchPart part : batchParts) {
            if (part.model == null && part.indices.length > 0) {
                Mesh mesh = new Mesh(gl, part.vertices, part.indices);
                part.model = new Model("infrastructure_batch", mesh, new Matrix4(1), part.textured ? texturedShader : shader,
                        part.material, renderer, lights, camera);
            }
        }
    }

    public void dispose(GL3 gl) {
        for (BatchPart part : batchParts) {
            if (part.model != null && part.model.getMesh() != null) {
                part.model.getMesh().dispose(gl);
                part.model = null;
            }
        }
    }

    public void render(GL3 gl, Vector3 ambientLight, float nightProportion,
                    Map<Integer, Model> signModelsByNation,
                    Map<Integer, Model> postModelsByNation,
                    Texture alphabetAtlas, int atlasSize, int writingDirection) {
        if (this.type == Type.BATCH) {
            for (BatchPart part : batchParts) {
                if (part.model == null) {
                    continue;
                }
                if (part.doubleSided) {
                    gl.glDisable(GL3.GL_CULL_FACE);
                }
                if (part.paint) {
                    // Pull the paint towards the camera so it never z-fights the asphalt
                    gl.glEnable(GL3.GL_POLYGON_OFFSET_FILL);
                    gl.glPolygonOffset(-1.0f, -2.0f);
                }
                part.model.render(gl, ambientLight, nightProportion);
                if (part.paint) {
                    gl.glDisable(GL3.GL_POLYGON_OFFSET_FILL);
                }
                if (part.doubleSided) {
                    gl.glEnable(GL3.GL_CULL_FACE);
                }
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
