package com.xamera.ar.core.components.java.sharedcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * LetterRenderer renders a textured cube whose texture is generated from a text string.
 *
 * <p>The text is drawn into a Bitmap (using Android’s Canvas) which is then uploaded as an OpenGL texture.
 * The cube is defined by 36 vertices (6 faces × 2 triangles per face). The vertex data uses full-range texture
 * coordinates ([0,1]) so that the entire texture is mapped onto each face. The texture creation logic measures
 * the entire string and creates a bitmap (with some padding) so that the full string is visible.
 */
public class LetterRenderer {
    private static final String TAG = LetterRenderer.class.getSimpleName();

    // Vertex shader: transforms vertex positions and passes texture coordinates.
    private final String vertexShaderCode =
            "attribute vec4 a_Position;" +
                    "attribute vec2 a_TexCoord;" +
                    "uniform mat4 u_MVPMatrix;" +
                    "varying vec2 v_TexCoord;" +
                    "void main() {" +
                    "  gl_Position = u_MVPMatrix * a_Position;" +
                    "  v_TexCoord = a_TexCoord;" +
                    "}";

    // Fragment shader: samples from the texture.
    private final String fragmentShaderCode =
            "precision mediump float;" +
                    "uniform sampler2D u_Texture;" +
                    "varying vec2 v_TexCoord;" +
                    "void main() {" +
                    "  gl_FragColor = texture2D(u_Texture, v_TexCoord);" +
                    "}";

    /*
     * Define vertex data for a cube.
     * Each face is represented by two triangles (6 vertices per face, 36 vertices total).
     * Each vertex consists of 5 floats: X, Y, Z, U, V.
     * The texture coordinates here span the full [0,1] range so that the entire texture appears on every face.
     */
    private final float[] vertexData = {
            // Front face
            -0.5f,  0.5f,  0.5f,   0f, 0f,
            -0.5f, -0.5f,  0.5f,   0f, 1f,
            0.5f,  0.5f,  0.5f,   1f, 0f,
            0.5f,  0.5f,  0.5f,   1f, 0f,
            -0.5f, -0.5f,  0.5f,   0f, 1f,
            0.5f, -0.5f,  0.5f,   1f, 1f,

            // Right face
            0.5f,  0.5f,  0.5f,   0f, 0f,
            0.5f, -0.5f,  0.5f,   0f, 1f,
            0.5f,  0.5f, -0.5f,   1f, 0f,
            0.5f,  0.5f, -0.5f,   1f, 0f,
            0.5f, -0.5f,  0.5f,   0f, 1f,
            0.5f, -0.5f, -0.5f,   1f, 1f,

            // Back face
            0.5f,  0.5f, -0.5f,   0f, 0f,
            0.5f, -0.5f, -0.5f,   0f, 1f,
            -0.5f,  0.5f, -0.5f,   1f, 0f,
            -0.5f,  0.5f, -0.5f,   1f, 0f,
            0.5f, -0.5f, -0.5f,   0f, 1f,
            -0.5f, -0.5f, -0.5f,   1f, 1f,

            // Left face
            -0.5f,  0.5f, -0.5f,   0f, 0f,
            -0.5f, -0.5f, -0.5f,   0f, 1f,
            -0.5f,  0.5f,  0.5f,   1f, 0f,
            -0.5f,  0.5f,  0.5f,   1f, 0f,
            -0.5f, -0.5f, -0.5f,   0f, 1f,
            -0.5f, -0.5f,  0.5f,   1f, 1f,

            // Top face
            -0.5f,  0.5f, -0.5f,   0f, 0f,
            -0.5f,  0.5f,  0.5f,   0f, 1f,
            0.5f,  0.5f, -0.5f,   1f, 0f,
            0.5f,  0.5f, -0.5f,   1f, 0f,
            -0.5f,  0.5f,  0.5f,   0f, 1f,
            0.5f,  0.5f,  0.5f,   1f, 1f,

            // Bottom face
            -0.5f, -0.5f,  0.5f,   0f, 0f,
            -0.5f, -0.5f, -0.5f,   0f, 1f,
            0.5f, -0.5f,  0.5f,   1f, 0f,
            0.5f, -0.5f,  0.5f,   1f, 0f,
            -0.5f, -0.5f, -0.5f,   0f, 1f,
            0.5f, -0.5f, -0.5f,   1f, 1f,
    };

    // OpenGL program and handles.
    private int mProgram;
    private int mTextureId;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMVPMatrixHandle;
    private int mTextureUniformHandle;
    private FloatBuffer vertexBuffer;

    private final Context context;
    // The text (string) to render.
    private String letterText;

    /**
     * Constructs the LetterRenderer.
     *
     * @param context    The Android context.
     * @param letterText The text to display.
     */
    public LetterRenderer(Context context, String letterText) {
        this.context = context;
        this.letterText = letterText;
        // Create the texture from a bitmap containing the entire string.
        mTextureId = loadLetterTexture(letterText);
        // Compile shaders and link the OpenGL program.
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);
        // Prepare the vertex buffer.
        ByteBuffer bb = ByteBuffer.allocateDirect(vertexData.length * 4); // 4 bytes per float
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(vertexData);
        vertexBuffer.position(0);
    }

    /**
     * Loads and compiles a shader.
     *
     * @param type       The type of shader (GLES20.GL_VERTEX_SHADER or GLES20.GL_FRAGMENT_SHADER).
     * @param shaderCode The GLSL source code.
     * @return The shader handle.
     */
    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    /**
     * Creates a texture by drawing the entire string onto a Bitmap.
     *
     * <p>The method uses the Paint and Canvas classes to measure the full text bounds and then creates
     * a bitmap (with some padding) large enough to display the entire string. The resulting bitmap is
     * then uploaded as an OpenGL texture.
     *
     * @param letter The text string to render.
     * @return The OpenGL texture ID.
     */
    private int loadLetterTexture(String letter) {
        // Create a Paint object to measure and draw the text.
        Paint paint = new Paint();
        paint.setTextSize(128); // You can adjust this initial size if needed.
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setAntiAlias(true);

        // Measure the text bounds.
        Rect bounds = new Rect();
        paint.getTextBounds(letter, 0, letter.length(), bounds);

        // Add some padding so the text isn’t clipped.
        int padding = 20;
        int bmpWidth = bounds.width() + padding;
        int bmpHeight = bounds.height() + padding;

        // Create a Bitmap with a transparent background.
        Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT);

        // Draw the text centered in the bitmap.
        float x = bmpWidth / 2f;
        // Adjust y so that the text baseline is correctly centered.
        float y = bmpHeight / 2f - (bounds.top + bounds.bottom) / 2f;
        canvas.drawText(letter, x, y, paint);

        // Generate and bind the texture.
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        int textureId = textureIds[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return textureId;
    }

    /**
     * Updates the letter texture. Call this method if you want to change the displayed string.
     *
     * @param newLetter The new text string.
     */
    public void updateLetter(String newLetter) {
        this.letterText = newLetter;
        mTextureId = loadLetterTexture(newLetter);
    }

    /**
     * Draws the cube using the provided Model-View-Projection (MVP) matrix.
     *
     * @param mvpMatrix The combined MVP matrix.
     */
    public void draw(float[] mvpMatrix) {
        GLES20.glUseProgram(mProgram);

        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "a_Position");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "a_TexCoord");
        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "u_MVPMatrix");
        mTextureUniformHandle = GLES20.glGetUniformLocation(mProgram, "u_Texture");

        // Set up the vertex position attribute.
        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer);

        // Set up the texture coordinate attribute.
        vertexBuffer.position(3);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer);

        // Pass the MVP matrix.
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);

        // Bind the texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
        GLES20.glUniform1i(mTextureUniformHandle, 0);

        // Draw the cube (36 vertices).
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }
}
