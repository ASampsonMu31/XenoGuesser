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
    // The regional soil colour map, for drawing dirt tracks as the ground they run over
    public static Texture soilRegions;
    // How far a poster on a round wall bends back at its edges (see vs_standard), 0 for flat
    private float bend;
    // Whether it's pasted on a wall that leans or curves, and so leans or curves with it
    public boolean shaped;
    public Matrix4 backBoardMatrix;

    // Extra reach beyond the object's anchor point, used for behind-camera culling
    public float boundingRadius;

    // --- TEXT DATA ---
    public int[] textString;
    public int stringLength;

    // --- A SIGN'S PICTURES AND TITLE ---
    public static final int PICTURE_NONE = 0, PICTURE_FLAG = 1, PICTURE_MAP = 2, PICTURE_PORTRAIT = 3, PICTURE_FIGURE = 4,
            PICTURE_ADVERT = 5, PICTURE_PRODUCT = 6;
    public static final int MAX_PICTURES = 3;
    public static final int TITLE_CAPACITY = 16;
    // Up to three pictures, each placed by {x0, y0, x1, y1} as fractions of the text area, y downwards
    public final int[] pictureKinds = new int[MAX_PICTURES];
    public final float[][] pictureRects = new float[MAX_PICTURES][4];
    public final int[] pictureVariants = new int[MAX_PICTURES];
    public int pictureCount;
    // A map is drawn off the GL thread; its pixels arrive here, then become a texture
    public volatile int[] mapPixels;
    public int mapWidth = 1, mapHeight = 1;
    private int mapTexture;
    public final int[] titleString = new int[TITLE_CAPACITY];
    public int titleLength;
    // The title's lettering: {ink threshold (weight), slant, width, outline}, colour and drop shadow
    public float[] titleFont = { 0.5f, 0f, 1f, 0f };
    public float[] titleColour = { 0.05f, 0.05f, 0.05f };
    public boolean titleShadow;
    public float[] boardColour = { 1f, 1f, 1f };

    // --- SIGNS ON WALLS ---
    // Fixed flat to a wall (or sticking out from one) rather than standing on posts. A banner
    // is a shop's name in one line of big letters across a coloured board.
    public boolean wallMounted;
    public boolean banner;
    public boolean bothSides;
    public float boardWidth = 30f, boardHeight = 22f;

    public void addPicture(int kind, float[] rect, int variant) {
        if (pictureCount >= MAX_PICTURES) return;
        pictureKinds[pictureCount] = kind;
        pictureRects[pictureCount] = rect;
        pictureVariants[pictureCount] = variant;
        pictureCount++;
    }

    /** Height above the anchor of the middle of what's drawn, and how far it reaches from there, for culling. */
    public float drawnCentreY() {
        return wallMounted ? 0f : 30f;
    }

    public float drawnRadius() {
        return wallMounted ? Math.max(boardWidth, boardHeight) * 0.6f + 2f : 45f;
    }

    /** One mesh of a batch, drawn with a single material. */
    public static final class BatchPart {
        final float[] vertices;
        final int[] indices;
        final Material material;
        final boolean doubleSided;
                final boolean paint;
        final boolean textured;
        // See-through (window glass): left out of the solid pass and drawn afterwards
        final boolean transparent;
        // Only drawn within this distance of the viewer (small details); 0 for always
        float drawDistance;
        // A dirt track's grittiness (drawn as the ground's soil with grit); 0 for anything else
        public float trackGrit;
        // How weathered (stained and streaked) a poor house's walls or roof are, 0 to 1
        public float weathering;
        Model model;

        public BatchPart(float[] vertices, int[] indices, Material material, boolean doubleSided, boolean paint, boolean textured) {
            this(vertices, indices, material, doubleSided, paint, textured, false, "");
        }

        public final String name;

        public BatchPart(float[] vertices, int[] indices, Material material, boolean doubleSided, boolean paint, boolean textured,
                         boolean transparent) {
            this(vertices, indices, material, doubleSided, paint, textured, transparent, "");
        }

        public BatchPart(float[] vertices, int[] indices, Material material, boolean doubleSided, boolean paint, boolean textured,
                         boolean transparent, String name) {
            this.name = name;
            this.vertices = vertices;
            this.indices = indices;
            this.material = material;
            this.doubleSided = doubleSided;
            this.paint = paint;
            this.textured = textured;
            this.transparent = transparent;
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
            // A hair behind the front, so the blank back can never show through the face
            Matrix4 backShift = Matrix4Transform.translate(0.0f, boardCenterY, -0.08f);
            Matrix4 backTransform = Matrix4.multiply(backShift, Matrix4.multiply(backRot, boardScale));
            this.backBoardMatrix = Matrix4.multiply(this.modelMatrix, backTransform);
        }
    }

    /**
     * A board fixed to a wall: centred on position, facing the way rotationY turns a standing
     * sign to face, width by height. bothSides draws the same face on the back too, for a sign
     * that sticks out from a wall into the street.
     */
    public static InfrastructureObject createWallSign(Vector3 position, int nationId, float rotationY, float width, float height,
                                                      boolean banner, boolean bothSides, int[] textString) {
        return createWallSign(position, nationId, rotationY, width, height, banner, bothSides, textString, 0f, 0f);
    }

    /**
     * As createWallSign, for a wall that leans (its face tipped up by tiltDegrees) or curves
     * (round a cylinder of the given radius, 0 for flat): the poster leans and curves with it.
     */
    public static InfrastructureObject createWallSign(Vector3 position, int nationId, float rotationY, float width, float height,
                                                      boolean banner, boolean bothSides, int[] textString,
                                                      float tiltDegrees, float curveRadius) {
        InfrastructureObject sign = new InfrastructureObject(Type.SIGN, position, nationId, rotationY, textString);
        sign.bend = curveRadius > 0f ? width * width / (2f * curveRadius) : 0f;
        sign.shaped = curveRadius > 0f || tiltDegrees != 0f;
        if (tiltDegrees != 0f) sign.modelMatrix = Matrix4.multiply(sign.modelMatrix, Matrix4Transform.rotateAroundX(-tiltDegrees));
        sign.wallMounted = true;
        sign.banner = banner;
        sign.bothSides = bothSides;
        sign.boardWidth = width;
        sign.boardHeight = height;
        Matrix4 face = Matrix4.multiply(Matrix4Transform.rotateAroundX(90.0f), Matrix4Transform.scale(width, 1.0f, height));
        sign.frontBoardMatrix = Matrix4.multiply(sign.modelMatrix, face);
        sign.backBoardMatrix = Matrix4.multiply(sign.modelMatrix, Matrix4.multiply(Matrix4Transform.rotateAroundY(180.0f), face));
        return sign;
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

    /** Draws the see-through parts (window glass) with whatever shader is in use. */
    public void renderTransparent(GL3 gl) {
        for (BatchPart part : batchParts) {
            if (part.transparent && part.model != null && part.model.getMesh() != null) part.model.getMesh().render(gl);
        }
    }

    /** Turns a finished map into a texture the first time it is wanted; false while it is still being drawn. */
    private boolean uploadMap(GL3 gl) {
        if (mapTexture != 0) return true;
        int[] pixels = mapPixels;
        if (pixels == null) return false;
        int[] id = new int[1];
        gl.glGenTextures(1, id, 0);
        gl.glBindTexture(GL3.GL_TEXTURE_2D, id[0]);
        gl.glPixelStorei(GL3.GL_UNPACK_ALIGNMENT, 4);
        gl.glTexImage2D(GL3.GL_TEXTURE_2D, 0, GL3.GL_RGBA8, mapWidth, mapHeight, 0, GL3.GL_BGRA,
                GL3.GL_UNSIGNED_INT_8_8_8_8_REV, java.nio.IntBuffer.wrap(pixels));
        gl.glGenerateMipmap(GL3.GL_TEXTURE_2D);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_LINEAR_MIPMAP_LINEAR);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_LINEAR);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL3.GL_TEXTURE_2D, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);
        mapTexture = id[0];
        mapPixels = null;
        return true;
    }

    // The viewer's distance squared from this batch's nearest edge, set before drawing
    private float viewerDistanceSquared;

    public void setViewerDistanceSquared(float distanceSquared) {
        this.viewerDistanceSquared = distanceSquared;
    }

    private boolean beyond(float distance) {
        float reach = distance + boundingRadius;
        return viewerDistanceSquared > reach * reach;
    }

    /** Marks parts of these kinds as details, drawn only within distance. */
    public void setDetailDistance(java.util.function.Predicate<BatchPart> isDetail, float distance) {
        for (BatchPart part : batchParts) if (isDetail.test(part)) part.drawDistance = distance;
    }

    public boolean hasTransparentParts() {
        for (BatchPart part : batchParts) if (part.transparent) return true;
        return false;
    }

    public void dispose(GL3 gl) {
        if (mapTexture != 0) {
            gl.glDeleteTextures(1, new int[] { mapTexture }, 0);
            mapTexture = 0;
        }
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
        render(gl, ambientLight, nightProportion, signModelsByNation, postModelsByNation, alphabetAtlas, atlasSize, writingDirection, null);
    }

    public void render(GL3 gl, Vector3 ambientLight, float nightProportion,
                    Map<Integer, Model> signModelsByNation,
                    Map<Integer, Model> postModelsByNation,
                    Texture alphabetAtlas, int atlasSize, int writingDirection, Texture flag) {
        render(gl, ambientLight, nightProportion, signModelsByNation, postModelsByNation, alphabetAtlas, atlasSize, writingDirection, flag, null);
    }

    /** As render, with the nation's flag and its people's pictures (GL texture ids, rows bottom-up) for the sign's picture. */
    public void render(GL3 gl, Vector3 ambientLight, float nightProportion,
                    Map<Integer, Model> signModelsByNation,
                    Map<Integer, Model> postModelsByNation,
                    Texture alphabetAtlas, int atlasSize, int writingDirection, Texture flag, int[] people) {
        if (this.type == Type.BATCH) {
            for (BatchPart part : batchParts) {
                if (part.model == null || part.transparent) {
                    continue;
                }
                if (part.drawDistance > 0f && beyond(part.drawDistance)) {
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
                boolean track = part.trackGrit > 0f;
                if (track) {
                    // The ground's own soil, from the regional soil map, with grit
                    Shader shader = part.model.shader;
                    shader.use(gl);
                    if (soilRegions != null) {
                        gl.glActiveTexture(GL3.GL_TEXTURE6);
                        soilRegions.bind(gl);
                        shader.setInt(gl, "soilRegionMap", 6);
                    }
                    shader.setFloat(gl, "useSoilRegions", 1f);
                    shader.setFloat(gl, "trackGrit", part.trackGrit);
                    // Drawn over the ground it lies on, even where the distant ground is drawn coarsely
                    gl.glEnable(GL3.GL_POLYGON_OFFSET_FILL);
                    gl.glPolygonOffset(-2.0f, -8.0f);
                }
                if (part.weathering > 0f) {
                    part.model.shader.use(gl);
                    part.model.shader.setFloat(gl, "weathering", part.weathering);
                }
                part.model.render(gl, ambientLight, nightProportion);
                if (part.weathering > 0f) part.model.shader.setFloat(gl, "weathering", 0f);
                if (track) {
                    part.model.shader.setFloat(gl, "useSoilRegions", 0f);
                    part.model.shader.setFloat(gl, "trackGrit", 0f);
                    gl.glDisable(GL3.GL_POLYGON_OFFSET_FILL);
                }
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
                if (!wallMounted) {
                    postModel.setModelMatrix(this.leftPostMatrix);
                    postModel.render(gl, ambientLight, nightProportion);

                    postModel.setModelMatrix(this.rightPostMatrix);
                    postModel.render(gl, ambientLight, nightProportion);
                }

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

                // The pictures, which the text flows around on the front
                for (int i = 0; i < MAX_PICTURES; i++) {
                    int shownKind = PICTURE_NONE;
                    boolean ready = true;
                    float aspect = 1f;
                    boolean flip = false;
                    gl.glActiveTexture(GL3.GL_TEXTURE4 + i);
                    int kind = i < pictureCount ? pictureKinds[i] : PICTURE_NONE;
                    if (kind == PICTURE_FLAG && flag != null) {
                        flag.bind(gl);
                        shownKind = 1;
                        aspect = FlagDesigner.WIDTH / (float) FlagDesigner.HEIGHT;
                        // Made from an image, so stored bottom row first
                        flip = true;
                    } else if (kind == PICTURE_MAP) {
                        shownKind = 2;
                        aspect = mapWidth / (float) mapHeight;
                        ready = uploadMap(gl);
                        if (ready) gl.glBindTexture(GL3.GL_TEXTURE_2D, mapTexture);
                    } else if (kind >= PICTURE_PORTRAIT && people != null) {
                        int index = Inhabitants.pictureIndex(kind, pictureVariants[i]);
                        if (index >= 0 && index < people.length) {
                            gl.glBindTexture(GL3.GL_TEXTURE_2D, people[index]);
                            shownKind = 3;
                            aspect = Inhabitants.PICTURE_WIDTH / (float) Inhabitants.PICTURE_HEIGHT;
                            flip = true;
                        }
                    }
                    String n = "[" + i + "]";
                    signShader.setInt(gl, "picture" + i, 4 + i);
                    signShader.setInt(gl, "pictureKind" + n, shownKind);
                    signShader.setInt(gl, "pictureReady" + n, ready ? 1 : 0);
                    signShader.setInt(gl, "pictureFlip" + n, flip ? 1 : 0);
                    signShader.setFloat(gl, "pictureAspect" + n, aspect);
                    float[] r = pictureRects[i];
                    signShader.setFloat(gl, "pictureRect" + n, r[0], r[1], r[2], r[3]);
                }
                signShader.setInt(gl, "bannerMode", banner ? 1 : 0);
                float areaW = banner ? boardWidth * 0.94f : boardWidth * 0.92f, areaH = banner ? boardHeight * 0.9f : boardHeight * 0.88f;
                signShader.setFloat(gl, "areaSize", areaW, areaH);
                signShader.setFloat(gl, "boardColour", boardColour[0], boardColour[1], boardColour[2]);

                // The title, in its own lettering
                int titleLoc = gl.glGetUniformLocation(signShader.getID(), "titleString");
                if (titleLoc != -1) gl.glUniform1iv(titleLoc, TITLE_CAPACITY, titleString, 0);
                signShader.setInt(gl, "titleLength", titleLength);
                signShader.setFloat(gl, "titleFont", titleFont[0], titleFont[1], titleFont[2], titleFont[3]);
                signShader.setFloat(gl, "titleColour", titleColour[0], titleColour[1], titleColour[2]);
                signShader.setInt(gl, "titleShadow", titleShadow ? 1 : 0);

                // --- 1. FRONT SIDE (Draws full text) ---
                signShader.setFloat(gl, "bend", bend);
                signShader.setInt(gl, "stringLength", this.stringLength);
                billboardModel.setModelMatrix(this.frontBoardMatrix);
                billboardModel.render(gl, ambientLight, nightProportion);

                if (wallMounted || bothSides) signShader.setFloat(gl, "bend", 0f);
                if (bothSides) {
                    // Sticking out into the street: the same face both ways
                    billboardModel.setModelMatrix(this.backBoardMatrix);
                    billboardModel.render(gl, ambientLight, nightProportion);
                    return;
                }
                if (wallMounted) return;

                // --- 2. BACK SIDE (Forces string length to 0 = Blank surface) ---
                signShader.setFloat(gl, "bend", 0f);
                signShader.setInt(gl, "stringLength", 0);
                for (int i = 0; i < MAX_PICTURES; i++) signShader.setInt(gl, "pictureKind[" + i + "]", 0);
                signShader.setInt(gl, "titleLength", 0);
                signShader.setInt(gl, "bannerMode", 0);
                billboardModel.setModelMatrix(this.backBoardMatrix);
                billboardModel.render(gl, ambientLight, nightProportion);
            }
        }
    }
}
