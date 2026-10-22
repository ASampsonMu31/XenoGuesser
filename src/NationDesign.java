/**
 * One of the designs a nation builds (a bollard, a utility pole...), and the neighbouring nation
 * it's from: 0 for its main one, built over most of its land; another is built on the side
 * nearer that neighbour.
 */
record NationDesign<T>(T design, int from) {
}
