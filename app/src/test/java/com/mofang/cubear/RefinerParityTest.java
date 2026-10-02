package com.mofang.cubear;

import static org.junit.Assert.*;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assume;
import org.junit.Test;

/**
 * The Java refiner against the Python prototype it was ported from, on the labelled real frames.
 *
 * <p>{@code refiner-parity.json} holds, per frame, the network's coarse quad and the prototype's
 * refined lattice (tools/pipeline_sim.py). The prototype is what was measured on the demo videos;
 * this pins the app's implementation to it. Skipped when the dataset is not checked out.
 */
public class RefinerParityTest {
    private static final File IMAGES = new File("../data/real_cubes/images");

    @Test public void javaSamplerReadsWhatThePrototypeRead() throws Exception {
        Assume.assumeTrue("real frames not available", IMAGES.isDirectory());
        String json;
        try (InputStream in = getClass().getResourceAsStream("/refiner-parity.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher entry = Pattern.compile("\"file\": \"([^\"]+)\",\\s*\"coarse\": \\[[^\\]]+\\],"
            + "\\s*\"refined\": \\[([^\\]]+)\\],\\s*\"lab\": \\[([^\\]]+)\\],\\s*\"reliable\": \\[([^\\]]+)\\]")
            .matcher(json);
        int faces = 0, cells = 0, close = 0;
        double total = 0;
        while (entry.find()) {
            File file = new File(IMAGES, entry.group(1));
            if (!file.exists()) continue;
            double[] quad = parse(entry.group(2));
            double[] lab = parse(entry.group(3));
            String[] reliable = entry.group(4).split(",");
            FaceSample sample = FaceSampler.sample(load(file), 720, 1280, quad, true, 1f);
            faces++;
            for (int cell = 0; cell < 9; cell++) {
                if (!reliable[cell].trim().equals("true") || !sample.reliable[cell]) continue;
                double d = Math.sqrt(sq(sample.lab[cell][0] - lab[cell * 3])
                    + sq(sample.lab[cell][1] - lab[cell * 3 + 1]) + sq(sample.lab[cell][2] - lab[cell * 3 + 2]));
                total += d;
                cells++;
                if (d < 6) close++;
            }
        }
        System.out.println("sampler parity: " + faces + " faces, mean dLab " + (total / Math.max(1, cells))
            + ", within 6: " + close + "/" + cells);
        assertTrue(faces > 50);
        assertTrue("mean Lab difference " + total / cells, total / cells < 3.0);
        assertTrue(close >= cells * 0.97);
    }

    private static double sq(double v) { return v * v; }

    @Test public void javaRefinerMatchesThePrototypeOnRealFrames() throws Exception {
        Assume.assumeTrue("real frames not available", IMAGES.isDirectory());
        String json;
        try (InputStream in = getClass().getResourceAsStream("/refiner-parity.json")) {
            assertNotNull(in);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher entry = Pattern.compile(
            "\"file\": \"([^\"]+)\",\\s*\"coarse\": \\[([^\\]]+)\\],\\s*\"refined\": (null|\\[([^\\]]+)\\])")
            .matcher(json);
        int frames = 0, bothRefined = 0, agreeOnSuccess = 0, close = 0;
        double worst = 0;
        FaceRefiner refiner = new FaceRefiner();
        while (entry.find()) {
            File file = new File(IMAGES, entry.group(1));
            if (!file.exists()) continue;
            frames++;
            byte[] frame = load(file);
            double[] coarse = parse(entry.group(2));
            double[] expected = entry.group(4) == null ? null : parse(entry.group(4));
            FaceRefiner.Result result = refiner.refine(frame, 720, 1280, coarse);
            if ((result != null) == (expected != null)) agreeOnSuccess++;
            if (result != null && expected != null) {
                bothRefined++;
                double shift = FaceRefiner.maxCentreShift(expected, result.quad) / result.pitch;
                worst = Math.max(worst, shift);
                if (shift < 0.08) close++;
            }
        }
        System.out.println("refiner parity: success agrees " + agreeOnSuccess + "/" + frames
            + ", lattices within 0.08 pitch " + close + "/" + bothRefined + ", worst " + worst);
        assertTrue("fixture frames found", frames > 50);
        assertTrue("success agrees on " + agreeOnSuccess + "/" + frames, agreeOnSuccess >= frames * 0.92);
        assertTrue("lattices within 0.08 pitch on " + close + "/" + bothRefined + ", worst " + worst,
            close >= bothRefined * 0.95);
    }

    /**
     * JPEG at the dataset's 1080x1920, area-downscaled to the 720x1280 analysis frame. ImageIO is
     * reached by reflection: unit tests compile against android.jar, which lacks it, but run on a
     * desktop JVM, which has it.
     */
    private static byte[] load(File file) throws Exception {
        Object image = Class.forName("javax.imageio.ImageIO").getMethod("read", File.class)
            .invoke(null, file);
        int w = (Integer) image.getClass().getMethod("getWidth").invoke(image);
        int h = (Integer) image.getClass().getMethod("getHeight").invoke(image);
        java.lang.reflect.Method getRgb = image.getClass().getMethod("getRGB",
            int.class, int.class, int.class, int.class, int[].class, int.class, int.class);
        byte[] rgba = new byte[w * h * 4];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            getRgb.invoke(image, 0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x], at = (y * w + x) * 4;
                rgba[at] = (byte) (p >> 16);
                rgba[at + 1] = (byte) (p >> 8);
                rgba[at + 2] = (byte) p;
                rgba[at + 3] = (byte) 255;
            }
        }
        return new AreaResizer(w, h, 720, 1280).resizeToRgba(rgba);
    }

    private static double[] parse(String list) {
        String[] parts = list.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Double.parseDouble(parts[i].trim());
        return out;
    }
}
