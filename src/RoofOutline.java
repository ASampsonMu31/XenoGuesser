import java.util.ArrayList;
import java.util.List;

/**
 * A house's roof as its architecture dresses it: the corners round its eaves (house-local, y
 * above the base) and which of them sweep out into horns, the edges cresting runs along
 * ({a, b}), its ridges ({a, b}), its middle and its highest point.
 */
final class RoofOutline {
    float[][] eaves;
    boolean[] horned;
    final List<float[][]> edges = new ArrayList<>();
    final List<float[][]> ridges = new ArrayList<>();
    float[] centre, peak;
}
