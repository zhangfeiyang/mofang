package com.mofang.cubear;

import static org.junit.Assert.*;
import org.junit.Test;

public class GuideCubeTest {
    private static final String SOLVED = GuideCube.solved();

    @Test public void solvedCentersMatchWesternPalette() {
        assertEquals(CubeColor.RED.argb, GuideCube.cubieColors(SOLVED, 1, 0, 0)[0]);
        assertEquals(CubeColor.ORANGE.argb, GuideCube.cubieColors(SOLVED, -1, 0, 0)[1]);
        assertEquals(CubeColor.WHITE.argb, GuideCube.cubieColors(SOLVED, 0, 1, 0)[2]);
        assertEquals(CubeColor.YELLOW.argb, GuideCube.cubieColors(SOLVED, 0, -1, 0)[3]);
        assertEquals(CubeColor.GREEN.argb, GuideCube.cubieColors(SOLVED, 0, 0, 1)[4]);
        assertEquals(CubeColor.BLUE.argb, GuideCube.cubieColors(SOLVED, 0, 0, -1)[5]);
    }

    @Test public void stickerMapMatchesCubeMovesLayout() {
        assertArrayEquals(new int[]{-1, 1, -1, 0, 1, 0}, GuideCube.stickerPos(0));
        assertArrayEquals(new int[]{0, 0, 1, 0, 0, 1}, GuideCube.stickerPos(22));
        assertArrayEquals(new int[]{1, 0, 0, 1, 0, 0}, GuideCube.stickerPos(13));
    }

    @Test public void cornerCubieCarriesThreeStickers() {
        int[] ufr = GuideCube.cubieColors(SOLVED, 1, 1, 1);
        assertEquals(CubeColor.RED.argb, ufr[0]);
        assertEquals(CubeColor.WHITE.argb, ufr[2]);
        assertEquals(CubeColor.GREEN.argb, ufr[4]);
        assertEquals(0, ufr[1]);
        assertEquals(0, ufr[3]);
        assertEquals(0, ufr[5]);
    }

    @Test public void rightMoveBringsDfrCubieOntoUfr() {
        String after = CubeMoves.apply(SOLVED, "R");
        int[] ufr = GuideCube.cubieColors(after, 1, 1, 1);
        assertEquals(CubeColor.RED.argb, ufr[0]);
        assertEquals(CubeColor.GREEN.argb, ufr[2]);
        assertEquals(CubeColor.YELLOW.argb, ufr[4]);
    }

    /**
     * Three sides of nine cubies face the viewer, each a plastic shell plus a sticker, and a
     * turning layer briefly shows more; all of it must fit the fixed quad buffers.
     */
    @Test public void everyVisibleFaceFitsTheQuadBuffers() {
        GuideCube cube = new GuideCube();
        String scrambled = SOLVED;
        for (String move : "R U F' L2 D B'".split(" ")) scrambled = CubeMoves.apply(scrambled, move);
        for (char face : "URFDLB".toCharArray()) {
            for (float twist = 0f; twist > -3.2f; twist -= 0.4f) {
                int quads = cube.collectFaces(scrambled, face, twist);
                assertTrue(face + " twist " + twist + ": " + quads, quads >= 36 && quads <= 160);
            }
        }
    }

    @Test public void orientPutsEachFaceOnPositiveZ() {
        assertFront('F', 0, 0, 1);
        assertFront('B', 0, 0, -1);
        assertFront('U', 0, 1, 0);
        assertFront('D', 0, -1, 0);
        assertFront('R', 1, 0, 0);
        assertFront('L', -1, 0, 0);
    }

    private static void assertFront(char face, float x, float y, float z) {
        float[] out = new float[3];
        GuideCube.orientFaceToFront(new float[]{x, y, z}, face, out);
        assertEquals(face + " x", 0f, out[0], 1e-5f);
        assertEquals(face + " y", 0f, out[1], 1e-5f);
        assertEquals(face + " z", 1f, out[2], 1e-5f);
    }
}
