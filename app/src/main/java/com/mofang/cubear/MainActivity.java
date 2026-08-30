package com.mofang.cubear;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
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
    private PreviewView previewView;
    private CubeOverlayView overlay;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService solverExecutor = Executors.newSingleThreadExecutor();
    private final FaceStabilizer stabilizer = new FaceStabilizer(3);
    private final CubeStateAssembler assembler = new CubeStateAssembler();
    private final DetectionTracker detectionTracker = new DetectionTracker();
    private final List<String> moves = new ArrayList<>();
    private CubeUiState.Phase phase = CubeUiState.Phase.PERMISSION;
    private FaceSample liveFace;
    private DetectedFace liveDetection;
    private String cubeState;
    private int moveIndex = -1;
    private boolean moveArmed;
    private ActivityResultLauncher<String> cameraPermission;
    private CubeFaceModel faceModel;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(7, 17, 15));
        window.setNavigationBarColor(Color.rgb(7, 17, 15));
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(7, 17, 15));
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new CubeOverlayView(this);
        overlay.setOnReset(this::resetSession);
        root.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        faceModel = CubeFaceModel.create(this);

        if (!OpenCVLoader.initLocal()) {
            phase = CubeUiState.Phase.ERROR;
            publish("视觉引擎启动失败", "OpenCV 本地库无法加载");
            return;
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
                analysis.setAnalyzer(cameraExecutor, new CubeAnalyzer(this::onDetectedFace, faceModel));
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
        FaceSample stable = stabilizer.push(liveFace);
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
        if (!assembler.isComplete()) {
            String prefix = wasNew ? "已采集第 " + assembler.size() + " 个面" : "已更新当前面";
            publish(prefix, "转动魔方，露出另一个完整面并保持稳定");
            return;
        }
        phase = CubeUiState.Phase.SOLVING;
        publish("正在校验魔方", "自动校正六个面的拍摄方向…");
        solverExecutor.execute(this::solveCube);
    }

    private void solveCube() {
        String state = assembler.assembleLegalState();
        if (state == null) {
            List<FaceSample> pool = assembler.observations();
            ScanDump.write(new File(getExternalFilesDir(null), "scan-fail.json"), pool);
            // Keep every observation and stay in scanning. More looks at the same cube are what
            // resolve an ambiguous reading, so throwing the scan away here would be the worst move.
            runOnUiThread(() -> {
                phase = CubeUiState.Phase.SCANNING;
                publish("还差一点就能对上",
                    "已看过 " + pool.size() + " 次，再各面对准一次，光线均匀些");
            });
            return;
        }
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
        runOnUiThread(() -> {
            cubeState = state;
            moves.clear();
            moves.addAll(result);
            if (moves.isEmpty()) {
                phase = CubeUiState.Phase.SOLVED;
                moveIndex = -1;
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
            stabilizer.resetCandidate();
            if (moveIndex >= moves.size()) {
                phase = CubeUiState.Phase.SOLVED;
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
        assembler.clear();
        moves.clear();
        cubeState = null;
        moveIndex = -1;
        moveArmed = false;
        liveFace = null;
        liveDetection = null;
        detectionTracker.reset();
        stabilizer.resetCandidate();
        phase = CubeUiState.Phase.SCANNING;
        publish("扫描第一个面", "在画面任意位置转动魔方，App 会主动跟踪");
    }

    private void publishCurrent() {
        if (phase == CubeUiState.Phase.GUIDING && moveIndex < moves.size()) {
            String move = moves.get(moveIndex);
            CubeColor target = CubeColor.fromFace(move.charAt(0));
            publish("第 " + (moveIndex + 1) + " 步 · " + move,
                moveArmed ? "已锁定" + target.chinese + "色面，请按箭头转动" : "先将" + target.chinese + "色中心面对镜头");
        } else if (phase == CubeUiState.Phase.SCANNING) {
            publish("扫描魔方", "已采集 " + assembler.size() + " 面，缓慢转到下一面");
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
        overlay.setState(new CubeUiState(phase, title, detail, liveFace, liveDetection,
            assembler.scannedColors(), assembler.size(), new ArrayList<>(moves), moveIndex));
    }

    @Override protected void onDestroy() {
        cameraExecutor.shutdown();
        solverExecutor.shutdown();
        if (faceModel != null) faceModel.close();
        super.onDestroy();
    }
}
