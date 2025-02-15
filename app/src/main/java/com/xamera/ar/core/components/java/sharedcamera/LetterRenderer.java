package com.xamera.ar.core.components.java.sharedcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class LetterRenderer {
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
     * Each face of the cube is represented by two triangles (6 vertices per face, 36 vertices total).
     * Each vertex consists of 5 floats: X, Y, Z, U, V.
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

    private FloatBuffer vertexBuffer;
    private int mProgram;
    private int mTextureId;

    // Handles for shader attributes and uniforms.
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mMVPMatrixHandle;
    private int mTextureUniformHandle;

    public LetterRenderer(Context context, String letter) {
        // Create the texture from a bitmap containing the letter.
        mTextureId = loadLetterTexture(letter);
        // Compile shaders and link the program.
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);
        // Create vertex buffer for the cube.
        ByteBuffer bb = ByteBuffer.allocateDirect(vertexData.length * 4); // 4 bytes per float
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(vertexData);
        vertexBuffer.position(0);
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    // Create a texture by drawing the letter onto a Bitmap.
    private int loadLetterTexture(String letter) {
        Paint paint = new Paint();
        paint.setTextSize(128); // adjust text size as needed
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setAntiAlias(true);
        Rect bounds = new Rect();
        paint.getTextBounds(letter, 0, letter.length(), bounds);
        int bmpWidth = bounds.width() + 20;   // add some padding
        int bmpHeight = bounds.height() + 20;
        Bitmap bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT);
        canvas.drawText(letter, bmpWidth / 2f, bmpHeight - 10, paint);
        int[] textureIds = new int[1];
        GLES20.glGenTextures(1, textureIds, 0);
        int textureId = textureIds[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();
        return textureId;
    }

    // Draw the cube using the provided MVP matrix.
    public void draw(float[] mvpMatrix) {
        GLES20.glUseProgram(mProgram);
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "a_Position");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "a_TexCoord");
        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "u_MVPMatrix");
        mTextureUniformHandle = GLES20.glGetUniformLocation(mProgram, "u_Texture");
        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer);
        vertexBuffer.position(3);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, vertexBuffer);
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
        GLES20.glUniform1i(mTextureUniformHandle, 0);
        // Draw the cube: 36 vertices.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
    }
}