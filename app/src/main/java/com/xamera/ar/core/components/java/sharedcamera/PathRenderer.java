package com.xamera.ar.core.components.java.sharedcamera;

import android.opengl.GLES20;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

/**
 * PathRenderer renders a 3D path using a line strip.
 *
 * Use {@link #addPoint(float, float, float)} to add vertices (for example, when the user taps).
 * Call {@link #draw(float[])} with your current Model-View-Projection (MVP) matrix each frame.
 */
public class PathRenderer {

    // Vertex shader: transforms each vertex with the MVP matrix.
    private static final String VERTEX_SHADER_CODE =
            "uniform mat4 u_MVPMatrix;\n" +
                    "attribute vec3 a_Position;\n" +
                    "void main() {\n" +
                    "  gl_Position = u_MVPMatrix * vec4(a_Position, 1.0);\n" +
                    "}\n";

    // Fragment shader: outputs a uniform color.
    private static final String FRAGMENT_SHADER_CODE =
            "precision mediump float;\n" +
                    "uniform vec4 u_Color;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = u_Color;\n" +
                    "}\n";

    // OpenGL handles for our shader program and attributes/uniforms.
    private int mProgram;
    private int mPositionHandle;
    private int mMVPMatrixHandle;
    private int mColorHandle;

    // Each vertex has 3 coordinates (x, y, z)
    private static final int COORDS_PER_VERTEX = 3;
    // Number of bytes per vertex (3 floats × 4 bytes per float)
    private static final int VERTEX_STRIDE = COORDS_PER_VERTEX * 4;

    // Dynamic buffer for vertices.
    private FloatBuffer vertexBuffer;
    // An ArrayList to hold vertex data; each 3 floats represent one vertex.
    private final ArrayList<Float> verticesList = new ArrayList<>();
    private int vertexCount = 0;

    // The color for the 3D path (light blue with full opacity)
    private float[] pathColor = {0.5f, 0.8f, 1.0f, 1.0f};

    /**
     * Constructs a new PathRenderer.
     * Compiles the shaders and initializes an (initially empty) vertex buffer.
     */
    public PathRenderer() {
        // Compile shaders and link the program.
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE);
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);

        // Create an empty vertex buffer.
        ByteBuffer bb = ByteBuffer.allocateDirect(0);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
    }

    /**
     * Adds a new point (vertex) to the path.
     *
     * @param x The x-coordinate (in world space).
     * @param y The y-coordinate (in world space).
     * @param z The z-coordinate (in world space).
     */
    public void addPoint(float x, float y, float z) {
        verticesList.add(x);
        verticesList.add(y);
        verticesList.add(z);
        vertexCount = verticesList.size() / COORDS_PER_VERTEX;
        updateVertexBuffer();
    }

    /**
     * Clears the current path.
     */
    public void clearPath() {
        verticesList.clear();
        vertexCount = 0;
        updateVertexBuffer();
    }

    /**
     * Updates the vertex buffer based on the current list of vertices.
     */
    private void updateVertexBuffer() {
        // Allocate a new direct byte buffer sized to hold all vertex data.
        ByteBuffer bb = ByteBuffer.allocateDirect(verticesList.size() * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();

        // Copy the vertices into an array.
        float[] verticesArray = new float[verticesList.size()];
        for (int i = 0; i < verticesList.size(); i++) {
            verticesArray[i] = verticesList.get(i);
        }
        vertexBuffer.put(verticesArray);
        vertexBuffer.position(0);
    }

    /**
     * Draws the 3D path using the provided Model-View-Projection (MVP) matrix.
     *
     * @param mvpMatrix The MVP matrix to transform the vertices.
     */
    public void draw(float[] mvpMatrix) {
        // Tell OpenGL to use our shader program.
        GLES20.glUseProgram(mProgram);

        // Get handles for our shader attributes and uniforms.
        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "u_MVPMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "u_Color");
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "a_Position");

        // Pass in the MVP matrix.
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);
        // Pass in the path color.
        GLES20.glUniform4fv(mColorHandle, 1, pathColor, 0);

        // Set a bold line width.
        GLES20.glLineWidth(10.0f);  // Bold line width; adjust if needed

        // Enable the vertex attribute array.
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false, VERTEX_STRIDE, vertexBuffer);

        // Draw the path as a line strip if we have at least two vertices.
        if (vertexCount >= 2) {
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, vertexCount);
        }

        // Disable the vertex array.
        GLES20.glDisableVertexAttribArray(mPositionHandle);
    }

    /**
     * Utility method for compiling a shader.
     *
     * @param type       The type of shader (GLES20.GL_VERTEX_SHADER or GLES20.GL_FRAGMENT_SHADER).
     * @param shaderCode The source code of the shader.
     * @return The OpenGL handle for the shader.
     */
    private static int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
