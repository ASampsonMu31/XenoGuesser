package com.xenoguesser.math;

public class Vector3 {
    public float x;
    public float y;
    public float z;

    public Vector3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3(Vector3 other) {
        this(other.x, other.y, other.z);
    }

    public static Vector3 add(Vector3 first, Vector3 second) {
        return new Vector3(first.x + second.x, first.y + second.y, first.z + second.z);
    }

    public static Vector3 subtract(Vector3 first, Vector3 second) {
        return new Vector3(first.x - second.x, first.y - second.y, first.z - second.z);
    }

    public static Vector3 multiply(Vector3 vector, float scalar) {
        return new Vector3(vector.x * scalar, vector.y * scalar, vector.z * scalar);
    }

    public static Vector3 crossProduct(Vector3 first, Vector3 second) {
        return new Vector3(
            first.y * second.z - first.z * second.y,
            first.z * second.x - first.x * second.z,
            first.x * second.y - first.y * second.x
        );
    }

    public void add(Vector3 other) {
        x += other.x;
        y += other.y;
        z += other.z;
    }

    public float magnitude() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public void normalize() {
        float length = magnitude();
        if (length != 0.0f) {
            x /= length;
            y /= length;
            z /= length;
        }
    }

    public float[] toArray() {
        return new float[] {x, y, z};
    }
}