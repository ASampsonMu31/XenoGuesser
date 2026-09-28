package com.xenoguesser.math;

public final class Matrix4Transform {
    private Matrix4Transform() {}

    public static Matrix4 translate(float x, float y, float z) {
        Matrix4 result = new Matrix4(1.0f);
        float[] values = result.toFloatArrayForGLSL();
        values[12] = x;
        values[13] = y;
        values[14] = z;
        return new Matrix4(values);
    }

    public static Matrix4 translate(Vector3 translation) {
        return translate(translation.x, translation.y, translation.z);
    }

    public static Matrix4 scale(float x, float y, float z) {
        Matrix4 result = new Matrix4(1.0f);
        float[] values = result.toFloatArrayForGLSL();
        values[0] = x;
        values[5] = y;
        values[10] = z;
        return new Matrix4(values);
    }

    public static Matrix4 rotateAroundX(float degrees) {
        float radians = (float) Math.toRadians(degrees);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        Matrix4 result = new Matrix4(1.0f);
        float[] values = result.toFloatArrayForGLSL();
        values[5] = cosine;
        values[6] = sine;
        values[9] = -sine;
        values[10] = cosine;
        return new Matrix4(values);
    }

    public static Matrix4 rotateAroundY(float degrees) {
        float radians = (float) Math.toRadians(degrees);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        Matrix4 result = new Matrix4(1.0f);
        float[] values = result.toFloatArrayForGLSL();
        values[0] = cosine;
        values[2] = -sine;
        values[8] = sine;
        values[10] = cosine;
        return new Matrix4(values);
    }

    public static Matrix4 rotateAroundZ(float degrees) {
        float radians = (float) Math.toRadians(degrees);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        Matrix4 result = new Matrix4(1.0f);
        float[] values = result.toFloatArrayForGLSL();
        values[0] = cosine;
        values[1] = sine;
        values[4] = -sine;
        values[5] = cosine;
        return new Matrix4(values);
    }

    public static Matrix4 perspective(float verticalFovDegrees, float aspectRatio, float near, float far) {
        float tangent = (float) Math.tan(Math.toRadians(verticalFovDegrees) * 0.5);
        float[] values = new float[16];
        values[0] = 1.0f / (aspectRatio * tangent);
        values[5] = 1.0f / tangent;
        values[10] = -(far + near) / (far - near);
        values[11] = -1.0f;
        values[14] = -(2.0f * far * near) / (far - near);
        return new Matrix4(values);
    }

    public static Matrix4 lookAt(Vector3 eye, Vector3 target, Vector3 up) {
        Vector3 forward = Vector3.subtract(target, eye);
        forward.normalize();

        Vector3 side = Vector3.crossProduct(forward, up);
        side.normalize();

        Vector3 correctedUp = Vector3.crossProduct(side, forward);
        float[] values = new float[16];
        values[0] = side.x;
        values[1] = correctedUp.x;
        values[2] = -forward.x;
        values[4] = side.y;
        values[5] = correctedUp.y;
        values[6] = -forward.y;
        values[8] = side.z;
        values[9] = correctedUp.z;
        values[10] = -forward.z;
        values[12] = -(side.x * eye.x + side.y * eye.y + side.z * eye.z);
        values[13] = -(correctedUp.x * eye.x + correctedUp.y * eye.y + correctedUp.z * eye.z);
        values[14] = forward.x * eye.x + forward.y * eye.y + forward.z * eye.z;
        values[15] = 1.0f;
        return new Matrix4(values);
    }
}