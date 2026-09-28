package gmaths;

import java.util.Locale;

/** A 4 by 4 matrix stored as rows of column values. */
public class Mat4 {
  private final float[][] values = new float[4][4];

  public Mat4() {
    this(0.0f);
  }

  public Mat4(float diagonal) {
    for (int index = 0; index < 4; index++) {
      values[index][index] = diagonal;
    }
  }

  public Mat4(Mat4 source) {
    for (int row = 0; row < 4; row++) {
      System.arraycopy(source.values[row], 0, values[row], 0, 4);
    }
  }

  public void set(int row, int column, float value) {
    values[row][column] = value;
  }

  public float get(int row, int column) {
    return values[row][column];
  }

  public void transpose() {
    for (int row = 0; row < 4; row++) {
      for (int column = row + 1; column < 4; column++) {
        float value = values[row][column];
        values[row][column] = values[column][row];
        values[column][row] = value;
      }
    }
  }

  public static Mat4 transpose(Mat4 matrix) {
    Mat4 result = new Mat4(matrix);
    result.transpose();
    return result;
  }

  public static Mat4 multiply(Mat4 left, Mat4 right) {
    Mat4 product = new Mat4();
    for (int row = 0; row < 4; row++) {
      for (int column = 0; column < 4; column++) {
        float sum = 0.0f;
        for (int inner = 0; inner < 4; inner++) {
          sum += left.values[row][inner] * right.values[inner][column];
        }
        product.values[row][column] = sum;
      }
    }
    return product;
  }

  public static Vec3 multiply(Mat4 matrix, Vec3 vector) {
    float x = matrix.values[0][0] * vector.x
        + matrix.values[0][1] * vector.y
        + matrix.values[0][2] * vector.z;
    float y = matrix.values[1][0] * vector.x
        + matrix.values[1][1] * vector.y
        + matrix.values[1][2] * vector.z;
    float z = matrix.values[2][0] * vector.x
        + matrix.values[2][1] * vector.y
        + matrix.values[2][2] * vector.z;
    return new Vec3(x, y, z);
  }

  public float[] toFloatArrayForGLSL() {
    float[] flattened = new float[16];
    for (int column = 0; column < 4; column++) {
      for (int row = 0; row < 4; row++) {
        flattened[column * 4 + row] = values[row][column];
      }
    }
    return flattened;
  }

  public String asFloatArrayForGLSL() {
    StringBuilder text = new StringBuilder("{");
    for (int column = 0; column < 4; column++) {
      for (int row = 0; row < 4; row++) {
        if (column != 0 || row != 0) {
          text.append(',');
        }
        text.append(String.format(Locale.ROOT, "%.2f", values[row][column]));
      }
    }
    return text.append('}').toString();
  }

  @Override
  public String toString() {
    StringBuilder text = new StringBuilder("{");
    for (int row = 0; row < 4; row++) {
      if (row > 0) {
        text.append(" ");
      }
      text.append('{');
      for (int column = 0; column < 4; column++) {
        if (column > 0) {
          text.append(", ");
        }
        text.append(String.format(Locale.ROOT, "%.2f", values[row][column]));
      }
      text.append(row == 3 ? "}" : "},\n");
    }
    return text.append('}').toString();
  }
}