package com.xenoguesser.math;

public class Vector2 {
    public float x;
    public float y;

    public Vector2(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public Vector2(Vector2 other) {
        this(other.x, other.y);
    }

    public void add(Vector2 other) {
        x += other.x;
        y += other.y;
    }

    public float[] toArray() {
        return new float[] {x, y};
    }
}