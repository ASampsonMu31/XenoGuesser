package com.xenoguesser.math;

public class Vector4 {
    public float x;
    public float y;
    public float z;
    public float w;

    public Vector4(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public Vector4(Vector4 other) {
        this(other.x, other.y, other.z, other.w);
    }

    public float[] toArray() {
        return new float[] {x, y, z, w};
    }
}