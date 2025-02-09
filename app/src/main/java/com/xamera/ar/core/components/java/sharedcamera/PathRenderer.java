/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xamera.ar.core.components.java.sharedcamera;

import android.opengl.GLES20;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

/**
 * PathRenderer renders a 3D path using a line strip.
 *
 * Use {@link #addPoint(float, float, float)} to add vertices (for example, when the user taps).
 * Call {@link #draw(float[])} with your current Model-View-Projection (MVP) matrix each frame.
 *
 * This version includes two new methods:
 * <ul>
 *   <li>{@link #loadFromFile(String)} – loads tracking points from a file given its path.</li>
 *   <li>{@link #loadFromStream(InputStream)} – loads tracking points from an InputStream.</li>
 * </ul>
 *
 * Each line in the file/stream should be in one of the following formats:
 * <ul>
 *   <li>"x,y"   – in which case z is assumed to be 0.0f</li>
 *   <li>"x,y,z" – all three coordinates are specified</li>
 * </ul>
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

    private int mProgram;
    private int mPositionHandle;
    private int mMVPMatrixHandle;
    private int mColorHandle;

    private static final int COORDS_PER_VERTEX = 3;
    private static final int VERTEX_STRIDE = COORDS_PER_VERTEX * 4; // 4 bytes per float

    // Raw input points are stored here.
    private final ArrayList<Float> verticesList = new ArrayList<>();
    // The OpenGL buffer (updated from verticesList).
    private FloatBuffer vertexBuffer;
    // The (possibly updated) count of vertices (each having 3 coordinates).
    private int vertexCount = 0;

    // Color for the 3D path (light blue)
    private float[] pathColor = {0.5f, 0.8f, 1.0f, 1.0f};

    // Flag: if true, generate a smoothed path from the raw points.
    // (A smoothing algorithm like Catmull–Rom requires at least 4 points.)
    private boolean smoothPath = true;

    public PathRenderer() {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE);
        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);

        // Start with an empty buffer (will be updated as points are added)
        ByteBuffer bb = ByteBuffer.allocateDirect(0);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
    }

    /**
     * Add a raw point to the path.
     *
     * @param x X-coordinate.
     * @param y Y-coordinate.
     * @param z Z-coordinate.
     */
    public void addPoint(float x, float y, float z) {
        verticesList.add(x);
        verticesList.add(y);
        verticesList.add(z);
        updateVertexBuffer();
    }

    /**
     * Clear the current path.
     */
    public void clearPath() {
        verticesList.clear();
        vertexCount = 0;
        updateVertexBuffer();
    }

    /**
     * Update the vertex buffer. If smoothing is enabled and there are enough points,
     * generate a new (smoothed) vertex buffer via Catmull–Rom interpolation.
     */
    private void updateVertexBuffer() {
        if (smoothPath && verticesList.size() >= 12) { // at least 4 points required
            vertexBuffer = generateSmoothPathBuffer();
        } else {
            ByteBuffer bb = ByteBuffer.allocateDirect(verticesList.size() * 4);
            bb.order(ByteOrder.nativeOrder());
            vertexBuffer = bb.asFloatBuffer();
            float[] verticesArray = new float[verticesList.size()];
            for (int i = 0; i < verticesList.size(); i++) {
                verticesArray[i] = verticesList.get(i);
            }
            vertexBuffer.put(verticesArray);
            vertexBuffer.position(0);
            vertexCount = verticesList.size() / COORDS_PER_VERTEX;
        }
    }

    /**
     * Generate a smoothed vertex buffer using Catmull–Rom spline interpolation.
     * This method goes through each segment of the raw points and generates additional
     * interpolated points.
     *
     * @return A new FloatBuffer containing the smooth path vertices.
     */
    private FloatBuffer generateSmoothPathBuffer() {
        ArrayList<Float> smoothPoints = new ArrayList<>();
        int numPoints = verticesList.size() / 3;
        int steps = 10; // Number of interpolated points between each pair

        // Loop over each segment (using p0, p1, p2, p3 for interpolation)
        for (int i = 0; i < numPoints - 1; i++) {
            // Determine control points indices, clamping at the ends.
            int i0 = Math.max(i - 1, 0);
            int i1 = i;
            int i2 = i + 1;
            int i3 = Math.min(i + 2, numPoints - 1);

            float[] p0 = {
                    verticesList.get(i0 * 3),
                    verticesList.get(i0 * 3 + 1),
                    verticesList.get(i0 * 3 + 2)
            };
            float[] p1 = {
                    verticesList.get(i1 * 3),
                    verticesList.get(i1 * 3 + 1),
                    verticesList.get(i1 * 3 + 2)
            };
            float[] p2 = {
                    verticesList.get(i2 * 3),
                    verticesList.get(i2 * 3 + 1),
                    verticesList.get(i2 * 3 + 2)
            };
            float[] p3 = {
                    verticesList.get(i3 * 3),
                    verticesList.get(i3 * 3 + 1),
                    verticesList.get(i3 * 3 + 2)
            };

            // For each step, compute an interpolated point using the Catmull–Rom formula.
            for (int j = 0; j < steps; j++) {
                float t = j / (float) steps;
                float t2 = t * t;
                float t3 = t2 * t;

                // Basis functions for Catmull–Rom spline.
                float b0 = -0.5f * t3 + t2 - 0.5f * t;
                float b1 =  1.5f * t3 - 2.5f * t2 + 1.0f;
                float b2 = -1.5f * t3 + 2.0f * t2 + 0.5f * t;
                float b3 =  0.5f * t3 - 0.5f * t2;

                float x = b0 * p0[0] + b1 * p1[0] + b2 * p2[0] + b3 * p3[0];
                float y = b0 * p0[1] + b1 * p1[1] + b2 * p2[1] + b3 * p3[1];
                float z = b0 * p0[2] + b1 * p1[2] + b2 * p2[2] + b3 * p3[2];

                smoothPoints.add(x);
                smoothPoints.add(y);
                smoothPoints.add(z);
            }
        }
        // Add the final original point to ensure the path reaches its end.
        smoothPoints.add(verticesList.get(verticesList.size() - 3));
        smoothPoints.add(verticesList.get(verticesList.size() - 2));
        smoothPoints.add(verticesList.get(verticesList.size() - 1));

        // Allocate a new FloatBuffer from the smoothPoints list.
        int size = smoothPoints.size();
        ByteBuffer bb = ByteBuffer.allocateDirect(size * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer buffer = bb.asFloatBuffer();
        float[] smoothArray = new float[size];
        for (int i = 0; i < size; i++) {
            smoothArray[i] = smoothPoints.get(i);
        }
        buffer.put(smoothArray);
        buffer.position(0);
        vertexCount = size / COORDS_PER_VERTEX;
        return buffer;
    }

    /**
     * Draw the path using the provided MVP matrix.
     *
     * @param mvpMatrix The Model-View-Projection matrix.
     */
    public void draw(float[] mvpMatrix) {
        GLES20.glUseProgram(mProgram);

        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "u_MVPMatrix");
        mColorHandle = GLES20.glGetUniformLocation(mProgram, "u_Color");
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "a_Position");

        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniform4fv(mColorHandle, 1, pathColor, 0);

        GLES20.glLineWidth(10.0f);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false, VERTEX_STRIDE, vertexBuffer);

        if (vertexCount >= 2) {
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, vertexCount);
        }

        GLES20.glDisableVertexAttribArray(mPositionHandle);
    }

    /**
     * Utility method for compiling a shader.
     *
     * @param type       The type of shader (vertex or fragment).
     * @param shaderCode The source code of the shader.
     * @return The OpenGL handle of the compiled shader.
     */
    private static int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    /**
     * Loads tracking points from an InputStream.
     * Each line in the file should be in one of the following formats:
     * <ul>
     *   <li>"x,y"   – in which case z is assumed to be 0.0f</li>
     *   <li>"x,y,z" – all three coordinates are specified</li>
     * </ul>
     * A conversion factor is applied so that the saved values (for example, in pixels)
     * are converted to meters for AR rendering.
     *
     * @param in the InputStream for the file.
     * @throws IOException if an I/O error occurs.
     */
    public void loadFromStream(InputStream in) throws IOException {
        clearPath();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line;
        // Example conversion factor: adjust as needed (e.g., from pixels to meters)
        final float conversionFactor = 0.001f;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue; // Skip empty lines.
            String[] parts = line.split(",");
            try {
                // Parse the raw values (e.g., in pixels)
                float x = Float.parseFloat(parts[0].trim());
                float y = Float.parseFloat(parts[1].trim());
                float z = (parts.length >= 3) ? Float.parseFloat(parts[2].trim()) : 0.0f;
                // Convert to AR world units (meters)
                addPoint(x * conversionFactor, y * conversionFactor, z * conversionFactor);
            } catch (NumberFormatException e) {
                System.err.println("Skipping invalid line: " + line);
            }
        }
        reader.close();
    }

    /**
     * (Optional) Legacy method: Loads tracking points from a file path.
     *
     * @param filePath the full path to the text file.
     * @throws IOException if an I/O error occurs.
     */
    public void loadFromFile(String filePath) throws IOException {
        clearPath();
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split(",");
            try {
                float x = Float.parseFloat(parts[0].trim());
                float y = Float.parseFloat(parts[1].trim());
                float z = (parts.length >= 3) ? Float.parseFloat(parts[2].trim()) : 0.0f;
                addPoint(x, y, z);
            } catch (NumberFormatException e) {
                System.err.println("Skipping invalid line: " + line);
            }
        }
        reader.close();
    }
}
