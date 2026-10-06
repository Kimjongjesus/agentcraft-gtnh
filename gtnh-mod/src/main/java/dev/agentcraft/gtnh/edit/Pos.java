package dev.agentcraft.gtnh.edit;

/** A block position in the HQ dimension; ordered by y, then z, then x (rows read top-down in files). */
public final class Pos implements Comparable<Pos> {

    public final int x, y, z;

    public Pos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Pos add(int dx, int dy, int dz) {
        return new Pos(x + dx, y + dy, z + dz);
    }

    public String key() {
        return x + "," + y + "," + z;
    }

    /** "x,y,z" (also "x y z"); null when malformed. */
    public static Pos parse(String s) {
        if (s == null) return null;
        String[] p = s.trim()
            .split("[,\\s]+");
        if (p.length != 3) return null;
        try {
            return new Pos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public double distSq(double px, double py, double pz) {
        double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public int compareTo(Pos o) {
        if (y != o.y) return Integer.compare(o.y, y);
        if (z != o.z) return Integer.compare(z, o.z);
        return Integer.compare(x, o.x);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Pos)) return false;
        Pos p = (Pos) o;
        return p.x == x && p.y == y && p.z == z;
    }

    @Override
    public int hashCode() {
        return (x * 31 + y) * 31 + z;
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
