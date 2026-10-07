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
     * turning layer briefly shows more, cut faces included; all of it must fit the quad buffers
     * however the cube is held and whichever layer turns.
     */
    @Test public void everyVisibleFaceFitsTheQuadBuffers() {
        GuideCube cube = new GuideCube();
        String scrambled = SOLVED;
        for (String move : "R U F' L2 D B'".split(" ")) scrambled = CubeMoves.apply(scrambled, move);
        for (char front : "URFDLB".toCharArray()) {
            CubeFrame held = CubeFrame.seen(front, front % 4);
            for (String moves : new String[]{"U", "R", "F", "D", "L", "B", "R L'", "U D'"}) {
                GuideStep step = GuideStep.plan(java.util.Arrays.asList(moves.split(" ")), null, 0, held, 1).get(0);
                GuideStep wide = GuideStep.wide(moves.split(" ")[0], 0, held);
                for (float twist = 0f; twist > -3.2f; twist -= 0.4f) {
                    for (GuideStep turning : new GuideStep[]{step, wide}) {
                        int quads = cube.collectFaces(scrambled, held, turning.axis, turning.layer, twist);
                        assertTrue(front + " " + moves + " twist " + twist + ": " + quads,
                            quads >= 36 && quads < 256);
                    }
                }
            }
        }
    }
}
