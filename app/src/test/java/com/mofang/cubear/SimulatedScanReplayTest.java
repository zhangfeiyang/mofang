package com.mofang.cubear;

import static org.junit.Assert.*;
import cs.min2phase.Tools;
import java.util.List;
import org.junit.Test;

/**
 * End-to-end regression on real footage: the two demo videos were pushed through the actual
 * detection model (same cubeface.onnx via onnxruntime), a faithful port of the sampler and
 * stabilizer, and the emitted captures frozen into sim-scan.json. The real assembler must turn
 * them into a legal cube — this is the cheapest full-pipeline check that exists without a phone.
 */
public class SimulatedScanReplayTest {

    @Test public void simulatedCapturesAssembleToALegalCube() throws Exception {
        List<FaceSample> dump = ScanFailReplayTest.load("/sim-scan.json");
        assertEquals(15, dump.size());

        CubeStateAssembler assembler = new CubeStateAssembler();
        for (FaceSample face : dump) assembler.put(face);
        assertEquals("six faces must be recognised", 6, assembler.size());
        assertTrue(assembler.isComplete());
        assertEquals("every colour's dot must light", 6, assembler.establishedColors().size());

        String state = assembler.assemble();
        assertNotNull("video captures must assemble: " + assembler.lastFailure(), state);
        assertEquals(0, Tools.verify(state));
    }
}
