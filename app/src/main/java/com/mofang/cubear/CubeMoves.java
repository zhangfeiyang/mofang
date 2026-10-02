package com.mofang.cubear;

import java.util.Arrays;

/** Facelet permutations generated from a small integer 3D cube model. */
public final class CubeMoves {
    private CubeMoves() {}

    public static String apply(String state, String move) {
        if (state == null || state.length() != 54 || move == null || move.isEmpty()) return state;
        char face = move.charAt(0);
        int turns = move.endsWith("2") ? 2 : move.endsWith("'") ? 3 : 1;
        String result = state;
        for (int i = 0; i < turns; i++) result = clockwise(result, face);
        return result;
    }

    public static String face(String state, char face) {
        int offset = "URFDLB".indexOf(face) * 9;
        return offset < 0 ? "" : state.substring(offset, offset + 9);
    }

    private static String clockwise(String state, char face) {
        Vec axis = normalForFace(face);
        if (axis == null) return state;
        char[] output = new char[54];
        Arrays.fill(output, '?');
        for (int index = 0; index < 54; index++) {
            Sticker sticker = stickerAt(index);
            Sticker moved = sticker;
            if (sticker.position.dot(axis) == 1) {
                moved = new Sticker(rotateMinus90(sticker.position, axis), rotateMinus90(sticker.normal, axis));
            }
            output[indexOf(moved)] = state.charAt(index);
        }
        return new String(output);
    }

    private static Vec rotateMinus90(Vec vector, Vec axis) {
        Vec cross = axis.cross(vector);
        int projection = axis.dot(vector);
        return new Vec(
            -cross.x + axis.x * projection,
            -cross.y + axis.y * projection,
            -cross.z + axis.z * projection
        );
    }

    private static Vec normalForFace(char face) {
        switch (face) {
            case 'U': return new Vec(0, 1, 0);
            case 'R': return new Vec(1, 0, 0);
            case 'F': return new Vec(0, 0, 1);
            case 'D': return new Vec(0, -1, 0);
            case 'L': return new Vec(-1, 0, 0);
            case 'B': return new Vec(0, 0, -1);
            default: return null;
        }
    }

    private static Sticker stickerAt(int index) {
        int face = index / 9, local = index % 9, row = local / 3, col = local % 3;
        switch (face) {
            case 0: return new Sticker(new Vec(col - 1, 1, row - 1), new Vec(0, 1, 0));
            case 1: return new Sticker(new Vec(1, 1 - row, 1 - col), new Vec(1, 0, 0));
            case 2: return new Sticker(new Vec(col - 1, 1 - row, 1), new Vec(0, 0, 1));
            case 3: return new Sticker(new Vec(col - 1, -1, 1 - row), new Vec(0, -1, 0));
            case 4: return new Sticker(new Vec(-1, 1 - row, col - 1), new Vec(-1, 0, 0));
            default: return new Sticker(new Vec(1 - col, 1 - row, -1), new Vec(0, 0, -1));
        }
    }

    private static int indexOf(Sticker target) {
        for (int i = 0; i < 54; i++) if (stickerAt(i).equals(target)) return i;
        throw new IllegalStateException("Invalid sticker coordinate");
    }

    private static final class Sticker {
        final Vec position, normal;
        Sticker(Vec position, Vec normal) { this.position = position; this.normal = normal; }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Sticker)) return false;
            Sticker other = (Sticker) value;
            return position.equals(other.position) && normal.equals(other.normal);
        }
    }

    private static final class Vec {
        final int x, y, z;
        Vec(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        int dot(Vec other) { return x * other.x + y * other.y + z * other.z; }
        Vec cross(Vec other) {
            return new Vec(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Vec)) return false;
            Vec other = (Vec) value;
            return x == other.x && y == other.y && z == other.z;
        }
    }
}
