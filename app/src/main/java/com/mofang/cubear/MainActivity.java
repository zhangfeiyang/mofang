package com.mofang.cubear;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Size;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.content.pm.ApplicationInfo;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.common.util.concurrent.ListenableFuture;
import cs.min2phase.Search;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scan → review → guided solve.
 *
 * <p>Threading: the camera thread only turns frames into {@link DetectedFace}s. Everything that
 * holds scan state — tracker, gate, stabilizer, assembler, move tracker — lives on the main
 * thread, which takes the latest detection and drops stale ones, so no state is ever shared
 * across threads. Assembly runs on a worker against a snapshot of the pool.
 */
public final class MainActivity extends AppCompatActivity {
    /**
     * Shortest quad side, as a share of the frame's short edge, below which a face is refused
     * for capture. A floor against the absurd, not a quality gate: beyond roughly an arm's length
     * each sticker is a few pixels and its median reads plausible but wrong.
     */
    private static final float MIN_CAPTURE_SIDE_FRACTION = 0.08f;
    /** Two steady frames spanning at least this long make a capture. */
    private static final long CAPTURE_SPAN_NANOS = 90_000_000L;
    /** Consecutive failed six-face verifications after which the pool is presumed poisoned. */
    private static final int POISONED_AFTER = 3;
    private static final String PREFS = "cubear";

    // --- views
    private PreviewView previewView;
    private CubeOverlayView overlay;
    private TextView phaseChip, progressLabel, hintView, titleView, detailView;
    private TextView moveBig, moveDesc, moveNext;
    private ImageButton torchButton, voiceButton;
    private View guideCard, scanSection, tipsView, guideSection, actionSection, helpScrim;
    private GuideCubeView guideCube;
    private CubeNetView cubeNet;
    private ProgressBar stepProgress;
    private Button prevButton, nextButton, primaryButton, secondaryButton;

    // --- threads and camera
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService solverExecutor = Executors.newSingleThreadExecutor();
    private final AtomicReference<Frame> pendingFrame = new AtomicReference<>();
    private final AtomicBoolean frameScheduled = new AtomicBoolean();
    private CubeAnalyzer analyzer;
    private Camera camera;
    private boolean torchOn;
    private long lastMeteringAt;
    private float lastMeteringX = -1, lastMeteringY = -1;
    private CubeFaceModel faceModel;
    private DetectionDump detectionDump;
    private ReplaySource replay;
    private ActivityResultLauncher<String> cameraPermission;

    // --- scan state (main thread only)
    private final FaceStabilizer stabilizer = new FaceStabilizer(2, CAPTURE_SPAN_NANOS);
    private final CubeStateAssembler assembler = new CubeStateAssembler();
    private final DetectionTracker detectionTracker = new DetectionTracker();
    private final CaptureGate gate = new CaptureGate();
    private CubeUiState.Phase phase = CubeUiState.Phase.PERMISSION;
    private DetectedFace liveDetection;
    private FaceSample liveFace;
    private boolean tooFar;
    private long lastSeenAt;
    private int session;
    private boolean busy;
    private int lastAttemptPool = -1;
    private int failedAssemblies;
    private String lastFailure = "";
    private long scanStartedAt;

    // --- solution state
    private ScanPalette palette;
    private String cubeState;
    private CubeColor inferredColor;
    private final List<String> solution = new ArrayList<>();
    private MoveTracker moveTracker;
    private long guideStartedAt;
    private long solvedAfterMillis;
    private String errorTitle = "", errorDetail = "";

    // --- voice
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean voiceOn;
    private SharedPreferences prefs;

    private static final class Frame {
        final DetectedFace face;
        final long nanos;
        Frame(DetectedFace face, long nanos) { this.face = face; this.nanos = nanos; }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        bindViews();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        voiceOn = prefs.getBoolean("voice", true);

        detectionDump = DetectionDump.start(this);
        analyzer = new CubeAnalyzer(this::onDetection, null, detectionDump);
        // The network loads, and compiles its accelerator plan, on the camera thread before the
        // first frame is analysed: off the main thread, and ordered ahead of every analyze().
        cameraExecutor.execute(() -> {
            faceModel = CubeFaceModel.create(getApplicationContext());
            analyzer.setModel(faceModel);
            if (detectionDump != null) {
                detectionDump.log(faceModel == null ? "model FAILED to load" : "model ready on " + faceModel.provider);
            }
        });
        // min2phase builds its pruning tables on first use; doing it now keeps the first solve fast.
        solverExecutor.execute(Search::init);
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) return;
            int result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                && result != TextToSpeech.LANG_NOT_SUPPORTED;
            runOnUiThread(this::publish);
        });

        cameraPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) beginScanning();
            else publish();
        });
        if (startDebugSession()) {
            // Replay or a preset cube: no camera.
        } else if (hasCameraPermission()) {
            beginScanning();
        } else {
            publish();
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
        if (!prefs.getBoolean("help_seen", false)) showHelp(true);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (helpScrim.getVisibility() == View.VISIBLE) {
                    showHelp(false);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void bindViews() {
        previewView = findViewById(R.id.preview);
        // SurfaceView keeps the preview off the GPU compositor: lower latency, smoother motion.
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        overlay = findViewById(R.id.overlay);
        phaseChip = findViewById(R.id.phase_chip);
        progressLabel = findViewById(R.id.progress_label);
        hintView = findViewById(R.id.hint);
        titleView = findViewById(R.id.title);
        detailView = findViewById(R.id.detail);
        moveBig = findViewById(R.id.move_big);
        moveDesc = findViewById(R.id.move_desc);
        moveNext = findViewById(R.id.move_next);
        torchButton = findViewById(R.id.btn_torch);
        voiceButton = findViewById(R.id.btn_voice);
        guideCard = findViewById(R.id.guide_card);
        guideCube = findViewById(R.id.guide_cube);
        scanSection = findViewById(R.id.scan_section);
        tipsView = findViewById(R.id.tips);
        cubeNet = findViewById(R.id.cube_net);
        guideSection = findViewById(R.id.guide_section);
        stepProgress = findViewById(R.id.step_progress);
        prevButton = findViewById(R.id.btn_prev);
        nextButton = findViewById(R.id.btn_next);
        actionSection = findViewById(R.id.action_section);
        primaryButton = findViewById(R.id.btn_primary);
        secondaryButton = findViewById(R.id.btn_secondary);
        helpScrim = findViewById(R.id.help_scrim);

        View hud = findViewById(R.id.hud);
        ViewCompat.setOnApplyWindowInsetsListener(hud, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        torchButton.setVisibility(View.GONE);
        torchButton.setOnClickListener(v -> setTorch(!torchOn));
        voiceButton.setOnClickListener(v -> {
            voiceOn = !voiceOn;
            prefs.edit().putBoolean("voice", voiceOn).apply();
            if (voiceOn) speakCurrentMove(); else if (tts != null) tts.stop();
            publish();
        });
        findViewById(R.id.btn_help).setOnClickListener(v -> showHelp(true));
        findViewById(R.id.btn_help_close).setOnClickListener(v -> showHelp(false));
        findViewById(R.id.btn_restart).setOnClickListener(v -> confirmRestart());
        prevButton.setOnClickListener(v -> stepBy(-1));
        nextButton.setOnClickListener(v -> stepBy(+1));
        primaryButton.setOnClickListener(v -> onPrimaryAction());
        secondaryButton.setOnClickListener(v -> resetSession());
    }

    // ------------------------------------------------------------------------------ camera

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void beginScanning() {
        resetSession();
        startCamera();
    }

    /**
     * Debuggable builds accept two adb extras: {@code replay}, a video to scan instead of the
     * camera, and {@code state}, a 54-facelet cube to jump straight to the review screen with.
     */
    private boolean startDebugSession() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return false;
        String video = getIntent().getStringExtra("replay");
        String preset = getIntent().getStringExtra("state");
        if (video != null) {
            ImageView screen = findViewById(R.id.replay_screen);
            screen.setVisibility(View.VISIBLE);
            previewView.setVisibility(View.GONE);
            resetSession();
            phase = CubeUiState.Phase.SCANNING;
            replay = new ReplaySource(video, analyzer, cameraExecutor, screen);
            replay.start();
            publish();
            return true;
        }
        if (preset != null && preset.length() == 54) {
            resetSession();
            phase = CubeUiState.Phase.SCANNING;
            busy = true;
            final int generation = session;
            solverExecutor.execute(() -> {
                List<String> moves = solve(preset);
                runOnUiThread(() -> onAssembled(generation, false, preset, moves, null, ""));
            });
            publish();
            return true;
        }
        return false;
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
                analysis.setAnalyzer(cameraExecutor, analyzer);
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis);
                boolean flash = camera.getCameraInfo().hasFlashUnit();
                torchButton.setVisibility(flash ? View.VISIBLE : View.GONE);
                torchOn = false;
            } catch (Exception error) {
                showError("相机启动失败", error.getClass().getSimpleName());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setTorch(boolean on) {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchOn = on;
        camera.getCameraControl().enableTorch(on);
        publish();
    }

    /**
     * Points focus and exposure at the cube. Continuous autofocus favours whatever dominates the
     * frame — often the background behind a cube held close — and centre-weighted exposure lets
     * a bright desk darken the stickers. Re-metering on the face whenever it settles somewhere
     * new keeps the stickers sharp and correctly exposed, which the readings depend on.
     */
    private void meterOn(DetectedFace face) {
        if (camera == null || face == null || overlay.getWidth() == 0) return;
        long now = SystemClock.uptimeMillis();
        float scale = Math.max(overlay.getWidth() / (float) face.imageWidth,
            overlay.getHeight() / (float) face.imageHeight);
        float x = (overlay.getWidth() - face.imageWidth * scale) / 2f + face.centerX() * scale;
        float y = (overlay.getHeight() - face.imageHeight * scale) / 2f + face.centerY() * scale;
        float moved = lastMeteringX < 0 ? Float.MAX_VALUE
            : (float) Math.hypot(x - lastMeteringX, y - lastMeteringY) / overlay.getWidth();
        if (now - lastMeteringAt < 1500 || (moved < 0.12f && now - lastMeteringAt < 6000)) return;
        lastMeteringAt = now;
        lastMeteringX = x;
        lastMeteringY = y;
        MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y, 0.25f);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point,
            FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build();
        try {
            camera.getCameraControl().startFocusAndMetering(action);
        } catch (Exception ignored) {
            // Metering is an optimisation; a device that refuses it still scans.
        }
    }

    // ------------------------------------------------------------------------------ frames

    /** Camera thread. Hands the latest detection to the main thread, never queueing a backlog. */
    private void onDetection(DetectedFace face) {
        pendingFrame.set(new Frame(face, System.nanoTime()));
        if (frameScheduled.compareAndSet(false, true)) {
            runOnUiThread(() -> {
                frameScheduled.set(false);
                Frame frame = pendingFrame.getAndSet(null);
                if (frame != null) handleFrame(frame.face, frame.nanos);
            });
        }
    }

    private void handleFrame(DetectedFace face, long nanos) {
        liveDetection = detectionTracker.update(face);
        liveFace = face == null ? null : face.sample;
        if (face != null) lastSeenAt = SystemClock.uptimeMillis();
        tooFar = liveDetection != null && liveDetection.minSideFraction() < MIN_CAPTURE_SIDE_FRACTION;
        boolean tracking = phase == CubeUiState.Phase.SCANNING || phase == CubeUiState.Phase.GUIDING;
        if (tracking) {
            boolean admitted = gate.admit(face) && !tooFar;
            FaceSample stable = stabilizer.push(admitted ? face.sample : null, nanos);
            if (stable != null) {
                stabilizer.resetCandidate();
                if (phase == CubeUiState.Phase.SCANNING) onCapture(stable);
                else onGuideObservation(stable, face);
            }
            meterOn(face);
        }
        publish();
    }

    // ------------------------------------------------------------------------------ scanning

    private void onCapture(FaceSample face) {
        if (face.lab == null && face.center() == CubeColor.UNKNOWN) return;
        boolean wasNew = assembler.put(face);
        if (wasNew) {
            overlay.onFaceCaptured();
            pulse(false);
        }
        maybeAssemble();
    }

    /**
     * Starts an assembly when the pool can support one and has changed since the last try.
     * Six collected faces verify directly; five infer the sixth, retried as looks accumulate.
     */
    private void maybeAssemble() {
        if (busy || phase != CubeUiState.Phase.SCANNING) return;
        int pool = assembler.observations().size();
        boolean complete = assembler.isComplete();
        if (complete) {
            if (pool == lastAttemptPool) return;
        } else if (assembler.size() == 5) {
            if (lastAttemptPool >= 0 && pool - lastAttemptPool < 3) return;
        } else {
            return;
        }
        lastAttemptPool = pool;
        busy = true;
        final int generation = session;
        final CubeStateAssembler snapshot = assembler.copy();
        solverExecutor.execute(() -> {
            String state = snapshot.assemble();
            List<String> moves = state == null ? null : solve(state);
            if (state == null) {
                ScanDump.write(new File(getExternalFilesDir(null), "scan-fail.json"),
                    snapshot.observations());
            }
            final ScanPalette found = snapshot.palette();
            final String why = snapshot.lastFailure();
            // A search that ran out of time proves nothing about the pool.
            final boolean conclusive = complete && !snapshot.lastAttemptTimedOut();
            runOnUiThread(() -> onAssembled(generation, conclusive, state, moves, found, why));
        });
        publish();
    }

    /** Worker thread. Null when min2phase refuses the state. */
    private static List<String> solve(String state) {
        String result = new Search().solution(state, 21, 100_000_000L, 0, 0).trim();
        if (result.startsWith("Error")) return null;
        List<String> moves = new ArrayList<>();
        if (!result.isEmpty()) {
            for (String token : result.split("\\s+")) if (!token.equals(".")) moves.add(token);
        }
        return moves;
    }

    /**
     * @param conclusive a six-face attempt that searched to the end, so its failure counts
     *     towards declaring the pool poisoned
     */
    private void onAssembled(int generation, boolean conclusive, String state, List<String> moves,
                             ScanPalette found, String why) {
        if (generation != session) return;
        busy = false;
        if (phase != CubeUiState.Phase.SCANNING) return;
        if (state == null || moves == null) {
            lastFailure = why == null ? "" : why;
            if (conclusive && ++failedAssemblies >= POISONED_AFTER) {
                // Six well-looked faces that still contradict each other: the cube changed during
                // the scan (a layer turned mid-way). More looks cannot fix a mixed pool.
                resetSession();
                lastFailure = "读数互相矛盾，已重新开始。扫描时请不要拧动魔方的层";
            }
            publish();
            maybeAssemble();
            return;
        }
        failedAssemblies = 0;
        palette = found;
        cubeState = state;
        inferredColor = found == null ? null : found.missingColor();
        solution.clear();
        solution.addAll(moves);
        pulse(true);
        if (solution.isEmpty()) {
            phase = CubeUiState.Phase.SOLVED;
            solvedAfterMillis = 0;
            overlay.celebrate();
        } else {
            phase = CubeUiState.Phase.READY;
        }
        speak(solution.isEmpty() ? "魔方已经是还原状态" : "识别完成，共 " + solution.size() + " 步");
        publish();
    }

    // ------------------------------------------------------------------------------ guidance

    private void startGuiding() {
        if (cubeState == null) return;
        moveTracker = new MoveTracker(cubeState, solution);
        guideStartedAt = SystemClock.uptimeMillis();
        stabilizer.resetCandidate();
        phase = CubeUiState.Phase.GUIDING;
        speakCurrentMove();
        publish();
    }

    private void onGuideObservation(FaceSample stable, DetectedFace face) {
        if (moveTracker == null) return;
        if (palette != null) palette.maybeLearn(stable);
        FaceSample named = palette == null ? stable : palette.relabel(stable);
        MoveTracker.Outcome outcome = moveTracker.observe(named,
            face != null && MoveTracker.rollStable(face.corners));
        if (outcome == MoveTracker.Outcome.ADVANCED) onStepChanged(true);
    }

    private void stepBy(int delta) {
        if (moveTracker == null) return;
        if (delta > 0) moveTracker.next(); else moveTracker.previous();
        stabilizer.resetCandidate();
        onStepChanged(delta > 0);
    }

    private void onStepChanged(boolean forward) {
        if (moveTracker.done()) {
            phase = CubeUiState.Phase.SOLVED;
            solvedAfterMillis = SystemClock.uptimeMillis() - guideStartedAt;
            overlay.celebrate();
            pulse(true);
            speak("还原完成");
        } else {
            if (forward) pulse(false);
            speakCurrentMove();
        }
        publish();
    }

    private void speakCurrentMove() {
        if (moveTracker == null || moveTracker.done()) return;
        String move = moveTracker.currentMove();
        speak("第 " + (moveTracker.index() + 1) + " 步，" + faceName(move) + "，" + turnName(move));
    }

    private void speak(String text) {
        if (!voiceOn || !ttsReady || tts == null) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cubear");
    }

    // ------------------------------------------------------------------------------ actions

    private void onPrimaryAction() {
        switch (phase) {
            case READY: startGuiding(); break;
            case SOLVED:
            case ERROR: resetSession(); break;
            case PERMISSION:
                if (hasCameraPermission()) beginScanning();
                else cameraPermission.launch(Manifest.permission.CAMERA);
                break;
            default: break;
        }
    }

    private void confirmRestart() {
        boolean losesProgress = phase == CubeUiState.Phase.GUIDING
            || phase == CubeUiState.Phase.READY;
        if (!losesProgress) {
            if (hasCameraPermission()) resetSession();
            else cameraPermission.launch(Manifest.permission.CAMERA);
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("重新扫描？")
            .setMessage("当前的还原进度会被放弃。")
            .setNegativeButton("取消", null)
            .setPositiveButton("重新扫描", (dialog, which) -> resetSession())
            .show();
    }

    private void resetSession() {
        session++;
        busy = false;
        lastAttemptPool = -1;
        failedAssemblies = 0;
        lastFailure = "";
        assembler.clear();
        stabilizer.resetCandidate();
        detectionTracker.reset();
        gate.reset();
        palette = null;
        cubeState = null;
        inferredColor = null;
        solution.clear();
        moveTracker = null;
        liveFace = null;
        liveDetection = null;
        tooFar = false;
        scanStartedAt = SystemClock.uptimeMillis();
        lastSeenAt = scanStartedAt;
        phase = hasCameraPermission() ? CubeUiState.Phase.SCANNING : CubeUiState.Phase.PERMISSION;
        if (tts != null) tts.stop();
        publish();
    }

    private void showError(String title, String detail) {
        phase = CubeUiState.Phase.ERROR;
        errorTitle = title;
        errorDetail = detail;
        publish();
    }

    private void showHelp(boolean show) {
        helpScrim.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) prefs.edit().putBoolean("help_seen", true).apply();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Volume keys step through the solution, so both hands can stay on the cube.
        if (phase == CubeUiState.Phase.GUIDING
                && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            stepBy(keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ? +1 : -1);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------------------ rendering

    private void publish() {
        CubeUiState state = buildState();
        overlay.setState(state);
        render(state);
    }

    private CubeUiState buildState() {
        CubeUiState.Builder b = CubeUiState.builder(phase);
        FaceSample display = liveFace;
        if (palette != null && liveFace != null && phase == CubeUiState.Phase.GUIDING) {
            display = palette.relabel(liveFace);
        }
        b.detection(phase == CubeUiState.Phase.SCANNING || phase == CubeUiState.Phase.GUIDING
            ? liveDetection : null, display);
        b.progress(stabilizer.progress(), tooFar);
        b.scan(assembler.establishedColors(), assembler.size(), assembler.preview());
        b.cube(cubeState, inferredColor);
        b.busy(busy);
        switch (phase) {
            case PERMISSION:
                b.text("需要相机权限", "相机只用于在本机实时识别魔方，不保存、不上传画面");
                break;
            case SCANNING: {
                int faces = assembler.size();
                String title = faces == 0 ? "对准魔方的一个面"
                    : faces < 5 ? "转到下一个面"
                    : faces == 5 ? "再转出最后一个面" : "正在核对颜色";
                String detail;
                if (!lastFailure.isEmpty() && !busy) {
                    detail = lastFailure + (faces >= 5 ? "，再把各面正对镜头看一眼" : "");
                } else if (busy) {
                    detail = faces == 5 ? "正在根据五个面推算第六面…" : "六个面已集齐，正在校验…";
                } else if (faces == 0) {
                    detail = "整个面露出来，保持不动，圆环走满就采好了";
                } else {
                    detail = "已采集 " + faces + " 个面，顺序随意";
                }
                b.text(title, detail);
                b.hint(scanHint());
                break;
            }
            case READY:
                b.text(inferredColor == null ? "识别完成" : "识别完成 · 第六面已推算",
                    "核对一下展开图的颜色，然后开始还原。共 " + solution.size() + " 步");
                break;
            case GUIDING: {
                String move = moveTracker.currentMove();
                b.moves(moveTracker.moves(), moveTracker.index(), targetInView(move));
                String detail = targetInView(move)
                    ? "按箭头方向拧" + faceName(move) + "，完成后自动进入下一步"
                    : "把" + faceName(move) + "（" + CubeColor.fromFace(move.charAt(0)).chinese
                        + "色中心）对准镜头，或直接按动画拧";
                b.text("第 " + (moveTracker.index() + 1) + " 步", detail);
                if (moveTracker.mismatches() >= 4) b.hint("画面和预期不一致：拧错了可以点“上一步”");
                break;
            }
            case SOLVED:
                b.text("还原完成！", solution.isEmpty() ? "魔方本来就是还原状态"
                    : "共 " + solution.size() + " 步" + (solvedAfterMillis > 0
                        ? " · 用时 " + formatDuration(solvedAfterMillis) : ""));
                break;
            case ERROR:
                b.text(errorTitle, errorDetail);
                break;
        }
        return b.build();
    }

    /** A situational nudge for the scan, most urgent first. */
    private String scanHint() {
        if (tooFar) return "再靠近一点，色块会读得更准";
        if (liveDetection != null && gate.struggling()) return "让整个面露出来，手指别挡住色块";
        if (liveDetection == null && SystemClock.uptimeMillis() - lastSeenAt > 3000) {
            return "把魔方放在画面中间，离镜头 20–30 厘米";
        }
        return null;
    }

    private boolean targetInView(String move) {
        if (move.isEmpty() || liveFace == null || liveDetection == null) return false;
        FaceSample named = palette == null ? liveFace : palette.relabel(liveFace);
        return named.center().face == move.charAt(0);
    }

    private void render(CubeUiState s) {
        boolean scanning = s.phase == CubeUiState.Phase.SCANNING;
        boolean guiding = s.phase == CubeUiState.Phase.GUIDING;
        boolean ready = s.phase == CubeUiState.Phase.READY;

        phaseChip.setText(phaseLabel(s.phase));
        int accent = s.phase == CubeUiState.Phase.ERROR ? getColor(R.color.coral)
            : s.busy ? getColor(R.color.amber) : getColor(R.color.mint);
        phaseChip.setTextColor(accent);
        progressLabel.setText(progressText(s));

        voiceButton.setVisibility((guiding || ready) && ttsReady ? View.VISIBLE : View.GONE);
        voiceButton.setImageResource(voiceOn ? R.drawable.ic_voice : R.drawable.ic_voice_off);
        voiceButton.setActivated(voiceOn);
        torchButton.setActivated(torchOn);
        torchButton.setImageResource(torchOn ? R.drawable.ic_flash : R.drawable.ic_flash_off);

        setText(titleView, s.title);
        setText(detailView, s.detail);
        hintView.setVisibility(s.hint == null ? View.GONE : View.VISIBLE);
        if (s.hint != null) setText(hintView, s.hint);

        scanSection.setVisibility(scanning || ready ? View.VISIBLE : View.GONE);
        tipsView.setVisibility(scanning && s.scannedCount < 3 ? View.VISIBLE : View.GONE);
        sizeNet(ready);
        if (ready && s.cubeState != null) {
            cubeNet.setState(s.cubeState);
            cubeNet.setInferred(s.inferred == null ? '?' : s.inferred.face);
            cubeNet.setHighlight('?');
        } else if (scanning) {
            cubeNet.setFaces(s.preview);
            cubeNet.setInferred('?');
            FaceSample live = s.liveFace;
            cubeNet.setHighlight(s.detectedFace != null && live != null && live.center() != CubeColor.UNKNOWN
                ? live.center().face : '?');
        }

        guideSection.setVisibility(guiding ? View.VISIBLE : View.GONE);
        guideCard.setVisibility(guiding ? View.VISIBLE : View.GONE);
        if (guiding) {
            String move = s.currentMove();
            guideCube.show(moveTracker.state(), move);
            stepProgress.setProgress((int) (1000L * s.moveIndex / Math.max(1, s.moves.size())));
            setText(moveBig, prettyMove(move));
            setText(moveDesc, faceName(move) + " · " + turnName(move));
            moveDesc.setCompoundDrawablesRelativeWithIntrinsicBounds(swatch(move), null, null, null);
            StringBuilder next = new StringBuilder("接下来");
            for (int i = s.moveIndex + 1; i < Math.min(s.moves.size(), s.moveIndex + 5); i++) {
                next.append("   ").append(prettyMove(s.moves.get(i)));
            }
            setText(moveNext, s.moveIndex + 1 < s.moves.size() ? next.toString() : "最后一步");
            prevButton.setEnabled(s.moveIndex > 0);
            prevButton.setAlpha(s.moveIndex > 0 ? 1f : 0.45f);
        }

        boolean actions = ready || s.phase == CubeUiState.Phase.SOLVED
            || s.phase == CubeUiState.Phase.ERROR || s.phase == CubeUiState.Phase.PERMISSION;
        actionSection.setVisibility(actions ? View.VISIBLE : View.GONE);
        secondaryButton.setVisibility(ready ? View.VISIBLE : View.GONE);
        setText(primaryButton, getString(ready ? R.string.action_start
            : s.phase == CubeUiState.Phase.SOLVED ? R.string.action_again
            : s.phase == CubeUiState.Phase.PERMISSION ? R.string.action_grant : R.string.action_rescan));
    }

    private boolean netLarge;

    /** The review screen is about checking the net, so it gets the whole panel width. */
    private void sizeNet(boolean large) {
        if (large == netLarge) return;
        netLarge = large;
        float d = getResources().getDisplayMetrics().density;
        android.view.ViewGroup.LayoutParams params = cubeNet.getLayoutParams();
        params.width = large ? android.view.ViewGroup.LayoutParams.MATCH_PARENT : Math.round(144 * d);
        params.height = Math.round((large ? 200 : 108) * d);
        cubeNet.setLayoutParams(params);
    }

    private android.graphics.drawable.Drawable swatchCache;
    private char swatchFace = '?';

    /** A small dot in the colour of the face to turn, next to its description. */
    private android.graphics.drawable.Drawable swatch(String move) {
        if (move.isEmpty()) return null;
        if (swatchCache == null || swatchFace != move.charAt(0)) {
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(CubeColor.fromFace(move.charAt(0)).argb);
            dot.setStroke(Math.round(getResources().getDisplayMetrics().density), 0x66FFFFFF);
            int size = Math.round(14 * getResources().getDisplayMetrics().density);
            dot.setSize(size, size);
            swatchCache = dot;
            swatchFace = move.charAt(0);
        }
        return swatchCache;
    }

    private static void setText(TextView view, CharSequence text) {
        if (!text.toString().contentEquals(view.getText())) view.setText(text);
    }

    private String phaseLabel(CubeUiState.Phase p) {
        switch (p) {
            case SCANNING: return getString(R.string.phase_scan);
            case READY: return getString(R.string.phase_ready);
            case GUIDING: return getString(R.string.phase_guide);
            case SOLVED: return getString(R.string.phase_done);
            case ERROR: return getString(R.string.phase_error);
            default: return "授权";
        }
    }

    private String progressText(CubeUiState s) {
        switch (s.phase) {
            case SCANNING: return "已采集 " + s.scannedCount + " / 6 面";
            case READY: return "共 " + solution.size() + " 步";
            case GUIDING: return (s.moveIndex + 1) + " / " + s.moves.size() + " 步";
            case SOLVED: return solvedAfterMillis > 0 ? "用时 " + formatDuration(solvedAfterMillis) : "";
            default: return "";
        }
    }

    static String prettyMove(String move) {
        if (move == null || move.isEmpty()) return "";
        return move.endsWith("'") ? move.charAt(0) + "′" : move;
    }

    static String faceName(String move) {
        if (move == null || move.isEmpty()) return "";
        return CubeColor.fromFace(move.charAt(0)).chinese + "色面";
    }

    static String turnName(String move) {
        if (move == null || move.isEmpty()) return "";
        if (move.endsWith("'")) return "逆时针 90°";
        if (move.endsWith("2")) return "转 180°";
        return "顺时针 90°";
    }

    private static String formatDuration(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** A short tick for a captured face or a step, a firmer one for a known or solved cube. */
    private void pulse(boolean strong) {
        Vibrator vibrator = ContextCompat.getSystemService(this, Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(strong
                ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK));
        } else {
            vibrator.vibrate(strong ? 45 : 25);
        }
    }

    @Override protected void onDestroy() {
        if (replay != null) replay.stop();
        cameraExecutor.shutdown();
        solverExecutor.shutdown();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        if (detectionDump != null) detectionDump.close();
        // The model is used on the camera thread; release it only after any frame in flight.
        if (faceModel != null) {
            final CubeFaceModel model = faceModel;
            new Thread(() -> {
                try {
                    cameraExecutor.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                model.close();
            }, "cubear-release").start();
        }
        super.onDestroy();
    }
}
