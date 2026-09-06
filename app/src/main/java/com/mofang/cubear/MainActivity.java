package com.mofang.cubear;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Size;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import cs.min2phase.Search;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.opencv.android.OpenCVLoader;

public final class MainActivity extends AppCompatActivity {
    /**
     * Shortest quad side, as a share of the frame's short edge, below which a face is refused for
     * capture. This is a floor against the absurd, not a quality gate: the cube's own constraints
     * — nine stickers per colour, piece uniqueness, the sixth face being derivable from five —
     * reject bad readings far more intelligently than any distance threshold could. Eight percent
     * of the frame edge is roughly an arm's length and beyond; closer than that, every honest
     * attempt is recorded and the assembler sorts it out.
     */
    private static final float MIN_CAPTURE_SIDE_FRACTION = 0.08f;

    private PreviewView previewView;
    private CubeOverlayView overlay;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService solverExecutor = Executors.newSingleThreadExecutor();
    private final FaceStabilizer stabilizer = new FaceStabilizer(2);
    private final CubeStateAssembler assembler = new CubeStateAssembler();
    private final DetectionTracker detectionTracker = new DetectionTracker();
    private final List<String> moves = new ArrayList<>();
    private CubeUiState.Phase phase = CubeUiState.Phase.PERMISSION;
    private FaceSample liveFace;
    private DetectedFace liveDetection;
    private boolean tooFar;
    private String cubeState;
    private int moveIndex = -1;
    private boolean moveArmed;
    /** True for the first guidance step after the sixth face was inferred, not scanned. */
    private boolean announceInference;
    private int lastSolvePoolSize = -1;
    /** Consecutive failed six-face verifications; a poisoned pool never recovers on its own. */
    private int failedAssemblies;
    private ActivityResultLauncher<String> cameraPermission;
    private CubeFaceModel faceModel;
    private DetectionDump detectionDump;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(7, 17, 15));
        window.setNavigationBarColor(Color.rgb(7, 17, 15));
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 17, 15));
        previewView = new PreviewView(this);
        // SurfaceView keeps the preview off the GPU compositor: lower latency and smoother motion.
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new CubeOverlayView(this);
        overlay.setOnReset(this::resetSession);
        root.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (!OpenCVLoader.initLocal()) {
            phase = CubeUiState.Phase.ERROR;
            publish("视觉引擎启动失败", "OpenCV 本地库无法加载");
            return;
        }
        // The model holds an OpenCV Mat; constructing it before initLocal() throws
        // UnsatisfiedLinkError and the CNN never runs.
        faceModel = CubeFaceModel.create(this);
        detectionDump = DetectionDump.start(this);
        if (detectionDump != null) {
            detectionDump.log(faceModel == null ? "model FAILED after opencv init" : "model ready");
        }

        cameraPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                phase = CubeUiState.Phase.SCANNING;
                publish("扫描第一个面", "在画面任意位置转动魔方，App 会主动跟踪");
                startCamera();
            } else {
                phase = CubeUiState.Phase.PERMISSION;
                publish("需要相机权限", "点击右上角“重扫”再次授权");
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            phase = CubeUiState.Phase.SCANNING;
            publish("扫描第一个面", "在画面任意位置转动魔方，App 会主动跟踪");
            startCamera();
        } else {
            publish("允许使用相机", "相机只用于本机实时识别，不保存画面");
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder()
                    .setTargetRotation(Surface.ROTATION_0)
                    .setTargetResolution(new Size(720, 1280))
                    .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setTargetRotation(Surface.ROTATION_0)
                    .setTargetResolution(new Size(720, 1280))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setOutputImageRotationEnabled(true)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analysis.setAnalyzer(cameraExecutor,
                    new CubeAnalyzer(this::onDetectedFace, faceModel, detectionDump));
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            } catch (Exception error) {
                phase = CubeUiState.Phase.ERROR;
                publish("相机启动失败", error.getClass().getSimpleName());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void onDetectedFace(DetectedFace detection) {
        liveDetection = detectionTracker.update(detection);
        liveFace = detection == null ? null : detection.sample;
        tooFar = liveDetection != null
            && liveDetection.minSideFraction() < MIN_CAPTURE_SIDE_FRACTION;
        if (tooFar) {
            // Too distant to read honestly: keep the run from building on readings we would discard.
            if (detectionDump != null) detectionDump.note("too_far", liveDetection);
            stabilizer.resetCandidate();
            runOnUiThread(this::publishCurrent);
            return;
        }
        FaceSample stable = stabilizer.push(liveFace);
        if (stable != null && detectionDump != null) detectionDump.note("stable_capture", liveDetection);
        runOnUiThread(() -> {
            if (phase == CubeUiState.Phase.SCANNING && stable != null) acceptScannedFace(stable);
            else if (phase == CubeUiState.Phase.GUIDING && stable != null) acceptGuidedMove(stable);
            else publishCurrent();
        });
    }

    private void acceptScannedFace(FaceSample face) {
        // A provisional name of UNKNOWN no longer disqualifies a face: the readings decide the
        // colours later, and the centre's reliability has already been checked by the stabilizer.
        if (face.lab == null && face.center() == CubeColor.UNKNOWN) return;
        boolean wasNew = assembler.put(face);
        stabilizer.resetCandidate();
        if (wasNew) {
            overlay.onFaceCaptured();
            pulse();
        }
        if (!assembler.isComplete()) {
            if (assembler.size() == 5) {
                int poolSize = assembler.observations().size();
                if (!wasNew && poolSize - lastSolvePoolSize < 4) {
                    publish(wasNew ? "已采集第 5 个面" : "已更新当前面",
                        "转动魔方，露出最后一个面；其余五面也可以直接推算");
                    return;
                }
                lastSolvePoolSize = poolSize;
                // Five faces are enough to attempt an inference, but it is never required:
                // the attempt runs in the background and scanning continues normally. If the
                // user shows the sixth face meanwhile, the six-face path takes over.
                solverExecutor.execute(this::solveInBackground);
            }
            String prefix = wasNew ? "已采集第 " + assembler.size() + " 个面" : "已更新当前面";
            publish(prefix, assembler.size() >= 5
                ? "继续转出第六面，或停在这让 App 直接推算"
                : "转动魔方，露出另一个完整面并保持稳定");
            return;
        }
        int poolSize = assembler.observations().size();
        if (!wasNew && poolSize - lastSolvePoolSize < 1) {
            publish("还差一点就能对上",
                assembler.lastFailure().isEmpty()
                    ? "再把每个面正对镜头各采一次"
                    : assembler.lastFailure() + "。再正对一次");
            return;
        }
        lastSolvePoolSize = poolSize;
        phase = CubeUiState.Phase.SOLVING;
        publish("正在校验魔方", "自动校正六个面的拍摄方向…");
        solverExecutor.execute(this::solveCube);
    }

    private void solveCube() {
        // Five grouped faces must not be force-split into six: that invents a duplicate face
        // whose 54-sticker string can still pass parity and look like a solution.
        boolean inferred = !assembler.isComplete();
        String state = assembler.assemble();
        if (state != null && assembler.palette() != null && assembler.palette().missingColor() != null) {
            inferred = true;
        }
        if (state == null) {
            List<FaceSample> pool = assembler.observations();
            ScanDump.write(new File(getExternalFilesDir(null), "scan-fail.json"), pool);
            failedAssemblies++;
            // More looks usually resolve an ambiguous reading — but not when the pool mixes two
            // cube states, which is what a single layer turn mid-scan does. Three failed
            // verifications on a full pool mean that happened; keeping the observations would
            // strand the user at "6 faces collected" forever, so the honest move is a restart.
            final boolean poisoned = !inferred && failedAssemblies >= 3;
            if (poisoned) {
                assembler.clear();
                lastSolvePoolSize = -1;
                failedAssemblies = 0;
            }
            final int groups = assembler.size();
            final String why = assembler.lastFailure();
            runOnUiThread(() -> {
                phase = CubeUiState.Phase.SCANNING;
                if (poisoned) {
                    publish("读数互相矛盾，已重新开始",
                        "扫描途中是否拧动过魔方的某一层？整体转动没有影响，请重新扫一遍");
                    return;
                }
                String detail = groups == 5
                    ? "再对准一次，或转出最后一个面"
                    : "已看过 " + pool.size() + " 次，再把红/橙两面各对准一次";
                if (why != null && !why.isEmpty()) detail = why + "。" + detail;
                publish("还差一点就能对上", detail);
            });
            return;
        }
        failedAssemblies = 0;
        guideSolution(state, inferred, false);
    }

    /**
     * Background attempt from a five-face pool. Never moves the phase: scanning continues
     * normally, and the result is only announced if the user has not moved on meanwhile.
     */
    private void solveInBackground() {
        String state = assembler.assemble();
        if (state == null) {
            // Silent failures are undebuggable: a refused inference gets the same scan-fail
            // dump as a foreground one, so the pool can be replayed offline.
            List<FaceSample> pool = assembler.observations();
            ScanDump.write(new File(getExternalFilesDir(null), "scan-fail.json"), pool);
            android.util.Log.i("CubeAsm", "background inference refused: " + assembler.lastFailure());
            return;
        }
        guideSolution(state, true, true);
    }

    private void guideSolution(String state, boolean inferred, boolean silent) {
        String solution = new Search().solution(state, 21, 100_000_000L, 0, 0).trim();
        if (solution.startsWith("Error")) {
            runOnUiThread(() -> {
                phase = CubeUiState.Phase.ERROR;
                publish("求解失败", solution);
            });
            return;
        }
        List<String> result = new ArrayList<>();
        if (!solution.isEmpty()) {
            for (String token : solution.split("\\s+")) if (!token.equals(".")) result.add(token);
        }
        final String solvedState = state;
        final boolean wasInferred = inferred;
        runOnUiThread(() -> {
            // A background five-face attempt must not yank the user out of an ongoing scan;
            // it only proceeds while the user is still scanning, and stops the moment a reset
            // or another solve has taken over.
            if (silent && phase != CubeUiState.Phase.SCANNING) return;
            cubeState = solvedState;
            moves.clear();
            moves.addAll(result);
            announceInference = wasInferred;
            if (moves.isEmpty()) {
                phase = CubeUiState.Phase.SOLVED;
                moveIndex = -1;
                overlay.celebrate();
                pulse();
                publish("魔方已经还原", "无需执行任何动作");
            } else {
                phase = CubeUiState.Phase.GUIDING;
                moveIndex = 0;
                moveArmed = false;
                publishCurrent();
            }
        });
    }

    private void acceptGuidedMove(FaceSample raw) {
        if (moveIndex < 0 || moveIndex >= moves.size()) return;
        // Name the live face against the palette this cube produced when it was solved, rather than
        // against fixed thresholds. The colours are already known at this point; not using them
        // would re-introduce the misreads the scan phase was rebuilt to avoid.
        ScanPalette palette = assembler.palette();
        if (palette != null) palette.maybeLearn(raw);
        FaceSample observed = palette == null ? raw : palette.relabel(raw);
        String move = moves.get(moveIndex);
        char targetFace = move.charAt(0);
        if (observed.center().face != targetFace) {
            publish("请对准目标面", "当前步骤需要" + CubeColor.fromFace(targetFace).chinese + "色中心面对镜头");
            return;
        }
        String before = CubeMoves.face(cubeState, targetFace);
        String afterState = CubeMoves.apply(cubeState, move);
        String after = CubeMoves.face(afterState, targetFace);
        if (!moveArmed && CubeMoves.faceMatchesAnyRotation(before, observed)) {
            moveArmed = true;
            stabilizer.resetCandidate();
            publishCurrent();
            return;
        }
        if (moveArmed && CubeMoves.faceMatchesAnyRotation(after, observed)) {
            cubeState = afterState;
            moveIndex++;
            moveArmed = false;
            announceInference = false;
            stabilizer.resetCandidate();
            if (moveIndex >= moves.size()) {
                phase = CubeUiState.Phase.SOLVED;
                overlay.celebrate();
                pulse();
                publish("还原完成！", "六个面已复原，可以点击重扫开始下一次");
            } else publishCurrent();
        } else if (moveArmed) {
            publish("动作未完成", "继续按箭头转动，或将目标面重新放回框内");
        }
    }

    private void resetSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA);
            return;
        }
        lastSolvePoolSize = -1;
        failedAssemblies = 0;
        assembler.clear();
        moves.clear();
        cubeState = null;
        moveIndex = -1;
        moveArmed = false;
        announceInference = false;
        liveFace = null;
        liveDetection = null;
        tooFar = false;
        detectionTracker.reset();
        stabilizer.resetCandidate();
        phase = CubeUiState.Phase.SCANNING;
        publish("扫描第一个面", "在画面任意位置转动魔方，App 会主动跟踪");
    }

    private void publishCurrent() {
        if (phase == CubeUiState.Phase.GUIDING && moveIndex < moves.size()) {
            String move = moves.get(moveIndex);
            CubeColor target = CubeColor.fromFace(move.charAt(0));
            String detail = !moveArmed && announceInference && moveIndex == 0
                ? "第六面已从色块结构推算，对准目标色即可"
                : moveArmed ? "已锁定" + target.chinese + "色面，按右上角动画方向拧"
                : "先将" + target.chinese + "色中心面对镜头，再按动画拧";
            publish("第 " + (moveIndex + 1) + " 步 · " + move, detail);
        } else if (phase == CubeUiState.Phase.SCANNING) {
            if (tooFar) {
                publish("找到魔方了", "再拿近一点，色块读得更准");
            } else if (assembler.isComplete()) {
                // Six faces are in: every frame must not bury the verification outcome the
                // user is waiting for under "keep rotating".
                publish("正在校验魔方", assembler.lastFailure().isEmpty()
                    ? "六个面已集齐，正在核对色块…"
                    : "校验未通过：" + assembler.lastFailure());
            } else {
                publish("扫描魔方", "已采集 " + assembler.size() + " 面，缓慢转到下一面");
            }
        } else {
            publish(stateTitle(), stateDetail());
        }
    }

    private String lastTitle = "寻找魔方";
    private String lastDetail = "将一个完整面放入框内";

    private String stateTitle() { return lastTitle; }
    private String stateDetail() { return lastDetail; }

    private void publish(String title, String detail) {
        lastTitle = title;
        lastDetail = detail;
        // During guidance the scan palette knows what each sticker really is; showing those names
        // instead of the provisional hue guesses keeps red from flickering to orange on screen.
        ScanPalette palette = assembler.palette();
        FaceSample displayFace = palette != null && phase == CubeUiState.Phase.GUIDING
            ? palette.relabel(liveFace) : liveFace;
        overlay.setState(new CubeUiState(phase, title, detail, displayFace, liveDetection,
            assembler.establishedColors(), assembler.size(), new ArrayList<>(moves), moveIndex,
            stabilizer.progress(), tooFar,
            palette == null ? null : palette.missingColor(), cubeState));
    }

    /** A short confirmation tick for a captured face and for the restored cube. */
    private void pulse() {
        Vibrator vibrator = ContextCompat.getSystemService(this, Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        } else {
            vibrator.vibrate(30);
        }
    }

    @Override protected void onDestroy() {
        cameraExecutor.shutdown();
        solverExecutor.shutdown();
        if (detectionDump != null) detectionDump.close();
        if (faceModel != null) faceModel.close();
        super.onDestroy();
    }
}
