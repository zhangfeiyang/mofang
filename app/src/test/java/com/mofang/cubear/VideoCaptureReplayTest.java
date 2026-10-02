package com.mofang.cubear;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

/**
 * The whole scan, minus the camera: every capture the app's pipeline makes over the 46-second
 * demo video (video_20260816_204654), fed to the assembler with the app's own retry cadence.
 *
 * <p>{@code video-captures.json} was produced by tools/replay_bench.py: the shipped detector,
 * the lattice refiner with the agreement check, the capture gate, the sampler and the
 * stabilizer, at the app's analysis rate. About one capture in twenty-five is still off by a
 * sticker or two, which is what the assembler has to survive. Before the pool fixes a single junk
 * look evicted a real face, and before the gate a thumb over the face formed a junk group that
 * stalled the five-face inference: the same video needed 72 captures and seconds of failed
 * attempts. Now the cube must come out right as soon as the fifth face is collected, and fast.
 */
public class VideoCaptureReplayTest {
    /** The demo cube, recovered from an earlier full assembly and confirmed by every capture. */
    private static final String TRUTH = "RRRBUFUFDBRDURUBUUBULDFBLLLFBUDDRDFLFRRDLLRBDBDULBFFLF";

    @Test public void demoVideoAssemblesTheRightCubeEarly() throws Exception {
        List<FaceSample> captures = ScanFailReplayTest.load("/video-captures.json");
        assertTrue(captures.size() > 150);

        CubeStateAssembler assembler = new CubeStateAssembler();
        int lastAttempt = -1, solvedAt = -1, attempts = 0;
        String state = null;
        long spent = 0;
        for (int i = 0; i < captures.size() && state == null; i++) {
            assembler.put(captures.get(i));
            // MainActivity.maybeAssemble: six collected faces whenever the pool changed, five
            // once the pool has grown by three since the last try.
            int pool = assembler.observations().size();
            boolean due = assembler.isComplete() ? pool != lastAttempt
                : assembler.size() == 5 && (lastAttempt < 0 || pool - lastAttempt >= 3);
            if (!due) continue;
            lastAttempt = pool;
            attempts++;
            long start = System.nanoTime();
            state = assembler.copy().assemble();
            spent += System.nanoTime() - start;
            if (state != null) solvedAt = i;
        }
        System.out.println("video replay: solved at capture " + solvedAt + " after " + attempts
            + " attempts, " + spent / 1_000_000 + " ms of assembly");
        assertEquals("the demo cube", TRUTH, state);
        assertTrue("solved by capture " + solvedAt, solvedAt >= 0 && solvedAt < 40);
        assertTrue(attempts + " attempts took " + spent / 1_000_000 + " ms", spent < 1_000_000_000L);
    }
}
