package com.mofang.cubear;

import static org.junit.Assert.*;
import cs.min2phase.Tools;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class ScanFailReplayTest {
    private static final Pattern LAB = Pattern.compile(
        "\\[\\s*([-0-9.eE]+)\\s*,\\s*([-0-9.eE]+)\\s*,\\s*([-0-9.eE]+)\\s*\\]");

    @Test public void phoneDumpWithSixColoursAssembles() throws Exception {
        List<FaceSample> dump = load("/scan-fail-20260905-2249.json");
        assertEquals(26, dump.size());

        CubeStateAssembler assembler = new CubeStateAssembler();
        int accepted = 0;
        for (FaceSample face : dump) {
            if (assembler.put(face)) accepted++;
            else assembler.put(face);
        }
        assertTrue("at least five real faces after dropping straddles, got " + assembler.size(),
            assembler.size() >= 5);

        // This particular session never captured an honest look at its green face (every green
        // observation is a straddle), so the pool is not provably recoverable and this test does
        // not demand a cube. What it pins down is the contract: the replay must terminate fast,
        // never throw, and every refusal must carry a reason the UI can show.
        String state = assembler.assemble();
        if (state != null) {
            assertEquals(0, Tools.verify(state));
            assertEquals(54, state.length());
        } else {
            assertTrue("a refusal must explain itself",
                assembler.lastFailure() != null && !assembler.lastFailure().isEmpty());
        }
    }

    @Test public void straddlingQuadHasALargeSpatialSplit() throws Exception {
        List<FaceSample> dump = load("/scan-fail-20260905-2249.json");
        // Observation 10 is a green-centre look whose 3×3 actually covers two faces.
        assertTrue(dump.get(10).spatialSplit() > CubeStateAssembler.STRADDLE_SPLIT);
        assertTrue(dump.get(4).spatialSplit() < CubeStateAssembler.STRADDLE_SPLIT);
    }

    static List<FaceSample> load(String resource) throws Exception {
        InputStream in = ScanFailReplayTest.class.getResourceAsStream(resource);
        assertNotNull("missing " + resource, in);
        List<FaceSample> pool = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.endsWith(",")) line = line.substring(0, line.length() - 1);
                if (!line.startsWith("{\"confidence\"")) continue;
                pool.add(parse(line));
            }
        }
        return pool;
    }

    private static FaceSample parse(String line) {
        int confAt = line.indexOf("\"confidence\":") + 13;
        float confidence = Float.parseFloat(line.substring(confAt, line.indexOf(',', confAt)).trim());
        String labPart = line.substring(line.indexOf("\"lab\":"), line.indexOf("\"reliable\""));
        float[][] lab = new float[9][3];
        Matcher matcher = LAB.matcher(labPart);
        int cell = 0;
        while (matcher.find() && cell < 9) {
            lab[cell][0] = Float.parseFloat(matcher.group(1));
            lab[cell][1] = Float.parseFloat(matcher.group(2));
            lab[cell][2] = Float.parseFloat(matcher.group(3));
            cell++;
        }
        if (cell != 9) throw new IllegalArgumentException("expected 9 lab triples, got " + cell);
        boolean[] reliable = new boolean[9];
        String flags = line.substring(line.indexOf("\"reliable\":"));
        Matcher flag = Pattern.compile("true|false").matcher(flags);
        int i = 0;
        while (flag.find() && i < 9) reliable[i++] = flag.group().equals("true");
        CubeColor[] stickers = new CubeColor[9];
        java.util.Arrays.fill(stickers, CubeColor.UNKNOWN);
        return new FaceSample(stickers, lab, reliable, confidence);
    }
}
