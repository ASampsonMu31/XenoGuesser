package com.xenoguesser.math;

public class Matrix4 {
    private final float[] values;

    public Matrix4(float diagonal) {
        values = new float[16];
        values[0] = diagonal;
        values[5] = diagonal;
        values[10] = diagonal;
        values[15] = diagonal;
    }

    Matrix4(float[] values) {
        if (values.length != 16) {
            throw new IllegalArgumentException("A 4x4 matrix requires 16 values");
        }
        this.values = values.clone();
    }

    /** A matrix from 16 values, column by column (as OpenGL stores them). */
    public static Matrix4 fromColumns(float[] values) {
        return new Matrix4(values);
    }

    public void set(int row, int column, float value) {
        if (row < 0 || row >= 4 || column < 0 || column >= 4) {
            throw new IndexOutOfBoundsException("Matrix indices must be between 0 and 3");
        }
        values[column * 4 + row] = value;
    }

    public static Matrix4 multiply(Matrix4 left, Matrix4 right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float value = 0.0f;
                for (int index = 0; index < 4; index++) {
                    value += left.values[index * 4 + row] * right.values[column * 4 + index];
                }
                result[column * 4 + row] = value;
            }
        }
        return new Matrix4(result);
    }

    public float[] toFloatArrayForGLSL() {
        return values.clone();
    }
}