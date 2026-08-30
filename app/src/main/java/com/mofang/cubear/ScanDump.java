package com.mofang.cubear;

import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.util.List;

/**
 * Writes the observations behind a failed assembly so the scan can be replayed off the phone.
 *
 * <p>The alternative is guessing. Some phones, this project's test device among them, suppress
 * logcat for third-party apps entirely, so a failure that only reproduces on real footage leaves no
 * trace at all. The file lands in the app's own external directory, which needs no permission and
 * can be pulled with {@code adb pull}.
 */
public final class ScanDump {
    private static final String TAG = "ScanDump";

    private ScanDump() {}

    /** Best effort: a diagnostic that throws would be worse than one that is missing. */
    public static void write(File target, List<FaceSample> pool) {
        try (FileWriter out = new FileWriter(target)) {
            out.write("[\n");
            for (int i = 0; i < pool.size(); i++) {
                FaceSample face = pool.get(i);
                out.write("  {\"confidence\": " + face.confidence + ", \"lab\": [");
                for (int cell = 0; cell < 9; cell++) {
                    float[] lab = face.lab == null ? new float[3] : face.lab[cell];
                    out.write((cell == 0 ? "" : ", ")
                        + "[" + lab[0] + ", " + lab[1] + ", " + lab[2] + "]");
                }
                out.write("], \"reliable\": [");
                for (int cell = 0; cell < 9; cell++) {
                    out.write((cell == 0 ? "" : ", ") + face.reliable[cell]);
                }
                out.write("]}" + (i == pool.size() - 1 ? "\n" : ",\n"));
            }
            out.write("]\n");
            Log.i(TAG, "wrote " + pool.size() + " observations to " + target);
        } catch (Throwable error) {
            Log.e(TAG, "could not write dump: " + error);
        }
    }
}
