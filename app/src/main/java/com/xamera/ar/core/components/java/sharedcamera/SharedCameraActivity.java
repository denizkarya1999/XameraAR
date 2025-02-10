package com.xamera.ar.core.components.java.sharedcamera;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.sharedcamera.R; // Ensure correct import or rename to your own R.
import com.xamera.ar.core.components.java.common.helpers.CameraPermissionHelper;
import com.xamera.ar.core.components.java.common.helpers.DisplayRotationHelper;
import com.xamera.ar.core.components.java.common.helpers.FullScreenHelper;
import com.xamera.ar.core.components.java.common.helpers.SnackbarHelper;
import com.xamera.ar.core.components.java.common.helpers.TapHelper;
import com.xamera.ar.core.components.java.common.helpers.TrackingStateHelper;
import com.xamera.ar.core.components.java.common.rendering.BackgroundRenderer;
import com.xamera.ar.core.components.java.common.rendering.PlaneRenderer;
import com.xamera.ar.core.components.java.common.rendering.PointCloudRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class SharedCameraActivity extends AppCompatActivity
        implements GLSurfaceView.Renderer,
        ImageReader.OnImageAvailableListener,
        SurfaceTexture.OnFrameAvailableListener {

  private static final String TAG = SharedCameraActivity.class.getSimpleName();

  // AR runs automatically.
  private boolean arMode = true;
  private final AtomicBoolean isFirstFrameWithoutArcore = new AtomicBoolean(true);

  // UI elements.
  private GLSurfaceView surfaceView;
  private LinearLayout imageTextLinearLayout;

  // ARCore session and shared camera.
  private Session sharedSession;
  private CameraCaptureSession captureSession;
  private CameraDevice cameraDevice;
  private CameraManager cameraManager;
  private SharedCamera sharedCamera;
  private String cameraId;
  private HandlerThread backgroundThread;
  private Handler backgroundHandler;
  private ImageReader cpuImageReader;
  private int cpuImagesProcessed;
  private boolean arcoreActive;
  private boolean surfaceCreated;
  private boolean errorCreatingSession = false;
  private CaptureRequest.Builder previewCaptureRequestBuilder;
  private List<CaptureRequest.Key<?>> keysThatCanCauseCaptureDelaysWhenModified;

  // Helper classes.
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private TapHelper tapHelper;

  // Renderers.
  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final PlaneRenderer planeRenderer = new PlaneRenderer();
  private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

  // Matrix for anchor poses.
  private final float[] anchorMatrix = new float[16];

  // Anchors created from taps.
  private final ArrayList<ColoredAnchor> anchors = new ArrayList<>();

  // For testing automation.
  private static final Short AUTOMATOR_DEFAULT = 0;
  private static final String AUTOMATOR_KEY = "automator";
  private final AtomicBoolean automatorRun = new AtomicBoolean(false);

  // Synchronization.
  private boolean captureSessionChangesPossible = true;
  private final ConditionVariable safeToExitApp = new ConditionVariable();

  // The LetterRenderer for our 2D letter cube.
  private LetterRenderer letterRenderer;
  // The PathRenderer for our 3D path.
  private PathRenderer pathRenderer;

  // Matrices for computing the final MVP.
  private final float[] mModelMatrix = new float[16];
  private final float[] mMVPMatrix = new float[16];

  // The obtained letter (set via file selection).
  // Instead of a hard-coded value, we initialize it as empty.
  private String obtainedLetter = "";

  // Flag for controlling the rendering mode.
  // If true, 2D Letter Box mode is active; if false, 3D Path mode is active.
  private boolean show2DLetterBox = true;

  // Two distinct request codes for file selection.
  private static final int READ_PATH_REQUEST_CODE = 42;
  private static final int READ_LETTER_REQUEST_CODE = 43;

  // Helper class to associate an anchor with a color.
  private static class ColoredAnchor {
    public final Anchor anchor;
    public final float[] color;
    public ColoredAnchor(Anchor a, float[] color4f) {
      this.anchor = a;
      this.color = color4f;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Activity Lifecycle
  // ---------------------------------------------------------------------------------------------
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    Bundle extraBundle = getIntent().getExtras();
    if (extraBundle != null && 1 == extraBundle.getShort(AUTOMATOR_KEY, AUTOMATOR_DEFAULT)) {
      automatorRun.set(true);
    }

    surfaceView = findViewById(R.id.glsurfaceview);
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

    displayRotationHelper = new DisplayRotationHelper(this);
    tapHelper = new TapHelper(this);
    surfaceView.setOnTouchListener(tapHelper);

    imageTextLinearLayout = findViewById(R.id.image_text_layout);

    CheckBox modeCheckbox = findViewById(R.id.checkbox_mode);
    modeCheckbox.setChecked(true);
    modeCheckbox.setText("2D Letter Box");
    modeCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        show2DLetterBox = isChecked;
        modeCheckbox.setText(isChecked ? "2D Letter Box" : "3D Path");
      }
    });

    messageSnackbarHelper.setMaxLines(4);
    updateSnackbarMessage();

    // Optionally, trigger the letter file selection as early as possible.
    performLetterFileSearch();
  }

  @Override
  protected void onDestroy() {
    if (sharedSession != null) {
      sharedSession.close();
      sharedSession = null;
    }
    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    waitUntilCameraCaptureSessionIsActive();
    startBackgroundThread();
    surfaceView.onResume();
    if (surfaceCreated) {
      openCamera();
    }
    displayRotationHelper.onResume();
  }

  @Override
  protected void onPause() {
    shouldUpdateSurfaceTexture.set(false);
    surfaceView.onPause();
    waitUntilCameraCaptureSessionIsActive();
    displayRotationHelper.onPause();
    if (arMode) {
      pauseARCore();
    }
    closeCamera();
    stopBackgroundThread();
    super.onPause();
  }

  // ---------------------------------------------------------------------------------------------
  // Synchronization Utility
  // ---------------------------------------------------------------------------------------------
  private synchronized void waitUntilCameraCaptureSessionIsActive() {
    while (!captureSessionChangesPossible) {
      try {
        this.wait();
      } catch (InterruptedException e) {
        Log.e(TAG, "Unable to wait for a safe time to make changes to the capture session", e);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Background Thread
  // ---------------------------------------------------------------------------------------------
  private void startBackgroundThread() {
    backgroundThread = new HandlerThread("sharedCameraBackground");
    backgroundThread.start();
    backgroundHandler = new Handler(backgroundThread.getLooper());
  }

  private void stopBackgroundThread() {
    if (backgroundThread != null) {
      backgroundThread.quitSafely();
      try {
        backgroundThread.join();
        backgroundThread = null;
        backgroundHandler = null;
      } catch (InterruptedException e) {
        Log.e(TAG, "Interrupted while trying to join background handler thread", e);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Camera and ARCore setup
  // ---------------------------------------------------------------------------------------------
  private final CameraDevice.StateCallback cameraDeviceCallback =
          new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice cameraDevice) {
              Log.d(TAG, "Camera device ID " + cameraDevice.getId() + " opened.");
              SharedCameraActivity.this.cameraDevice = cameraDevice;
              createCameraPreviewSession();
            }

            @Override
            public void onClosed(@NonNull CameraDevice cameraDevice) {
              Log.d(TAG, "Camera device ID " + cameraDevice.getId() + " closed.");
              SharedCameraActivity.this.cameraDevice = null;
              safeToExitApp.open();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice cameraDevice) {
              Log.w(TAG, "Camera device ID " + cameraDevice.getId() + " disconnected.");
              cameraDevice.close();
              SharedCameraActivity.this.cameraDevice = null;
            }

            @Override
            public void onError(@NonNull CameraDevice cameraDevice, int error) {
              Log.e(TAG, "Camera device ID " + cameraDevice.getId() + " error " + error);
              cameraDevice.close();
              SharedCameraActivity.this.cameraDevice = null;
              finish();
            }
          };

  private final CameraCaptureSession.StateCallback cameraSessionStateCallback =
          new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
              Log.d(TAG, "Camera capture session configured.");
              captureSession = session;
              setRepeatingCaptureRequest();
            }

            @Override
            public void onSurfacePrepared(@NonNull CameraCaptureSession session, @NonNull Surface surface) {
              Log.d(TAG, "Camera capture surface prepared.");
            }

            @Override
            public void onReady(@NonNull CameraCaptureSession session) {
              Log.d(TAG, "Camera capture session ready.");
            }

            @Override
            public void onActive(@NonNull CameraCaptureSession session) {
              Log.d(TAG, "Camera capture session active.");
              if (arMode && !arcoreActive) {
                resumeARCore();
              }
              synchronized (SharedCameraActivity.this) {
                captureSessionChangesPossible = true;
                SharedCameraActivity.this.notify();
              }
              updateSnackbarMessage();
            }

            @Override
            public void onCaptureQueueEmpty(@NonNull CameraCaptureSession session) {
              Log.w(TAG, "Camera capture queue empty.");
            }

            @Override
            public void onClosed(@NonNull CameraCaptureSession session) {
              Log.d(TAG, "Camera capture session closed.");
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
              Log.e(TAG, "Failed to configure camera capture session.");
            }
          };

  private final CameraCaptureSession.CaptureCallback cameraCaptureCallback =
          new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(
                    @NonNull CameraCaptureSession session,
                    @NonNull CaptureRequest request,
                    @NonNull TotalCaptureResult result) {
              shouldUpdateSurfaceTexture.set(true);
            }

            @Override
            public void onCaptureBufferLost(
                    @NonNull CameraCaptureSession session,
                    @NonNull CaptureRequest request,
                    @NonNull Surface target,
                    long frameNumber) {
              Log.e(TAG, "onCaptureBufferLost: " + frameNumber);
            }

            @Override
            public void onCaptureFailed(
                    @NonNull CameraCaptureSession session,
                    @NonNull CaptureRequest request,
                    @NonNull CaptureFailure failure) {
              Log.e(TAG, "onCaptureFailed: " + failure.getFrameNumber() + " " + failure.getReason());
            }

            @Override
            public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId) {
              Log.e(TAG, "onCaptureSequenceAborted: " + sequenceId + " " + session);
            }
          };

  private void openCamera() {
    if (cameraDevice != null) return;
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      CameraPermissionHelper.requestCameraPermission(this);
      return;
    }
    if (!isARCoreSupportedAndUpToDate()) return;

    if (sharedSession == null) {
      try {
        sharedSession = new Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA));
      } catch (Exception e) {
        errorCreatingSession = true;
        messageSnackbarHelper.showError(
                this, "Failed to create ARCore session that supports camera sharing");
        Log.e(TAG, "Failed to create ARCore session that supports camera sharing", e);
        return;
      }
      errorCreatingSession = false;

      Config config = sharedSession.getConfig();
      config.setFocusMode(Config.FocusMode.AUTO);
      sharedSession.configure(config);
    }

    sharedCamera = sharedSession.getSharedCamera();
    cameraId = sharedSession.getCameraConfig().getCameraId();

    Size desiredCpuImageSize = sharedSession.getCameraConfig().getImageSize();
    cpuImageReader = ImageReader.newInstance(
            desiredCpuImageSize.getWidth(),
            desiredCpuImageSize.getHeight(),
            ImageFormat.YUV_420_888,
            2);
    cpuImageReader.setOnImageAvailableListener(this, backgroundHandler);

    sharedCamera.setAppSurfaces(this.cameraId, Arrays.asList(cpuImageReader.getSurface()));

    try {
      CameraDevice.StateCallback wrappedCallback =
              sharedCamera.createARDeviceStateCallback(cameraDeviceCallback, backgroundHandler);
      cameraManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
      CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(this.cameraId);

      if (Build.VERSION.SDK_INT >= 28) {
        keysThatCanCauseCaptureDelaysWhenModified = characteristics.getAvailableSessionKeys();
        if (keysThatCanCauseCaptureDelaysWhenModified == null) {
          keysThatCanCauseCaptureDelaysWhenModified = new ArrayList<>();
        }
      }

      captureSessionChangesPossible = false;
      cameraManager.openCamera(cameraId, wrappedCallback, backgroundHandler);

    } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
      Log.e(TAG, "Failed to open camera", e);
    }
  }

  private <T> boolean checkIfKeyCanCauseDelay(CaptureRequest.Key<T> key) {
    if (Build.VERSION.SDK_INT >= 28) {
      return keysThatCanCauseCaptureDelaysWhenModified.contains(key);
    } else {
      Log.w(TAG, "Changing " + key +
              " may cause a noticeable capture delay on pre-Android P devices. Verify on target device.");
      return false;
    }
  }

  private void setCameraEffects(CaptureRequest.Builder captureBuilder) {
    if (checkIfKeyCanCauseDelay(CaptureRequest.CONTROL_EFFECT_MODE)) {
      Log.w(TAG, "Not setting CONTROL_EFFECT_MODE since it can cause delays.");
    } else {
      Log.d(TAG, "Setting CONTROL_EFFECT_MODE to SEPIA in non-AR mode.");
      captureBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_SEPIA);
    }
  }

  private void createCameraPreviewSession() {
    try {
      sharedSession.setCameraTextureName(backgroundRenderer.getTextureId());
      sharedCamera.getSurfaceTexture().setOnFrameAvailableListener(this);

      previewCaptureRequestBuilder =
              cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

      List<Surface> surfaceList = sharedCamera.getArCoreSurfaces();
      surfaceList.add(cpuImageReader.getSurface());
      for (Surface surface : surfaceList) {
        previewCaptureRequestBuilder.addTarget(surface);
      }

      CameraCaptureSession.StateCallback wrappedCallback =
              sharedCamera.createARSessionStateCallback(cameraSessionStateCallback, backgroundHandler);

      cameraDevice.createCaptureSession(surfaceList, wrappedCallback, backgroundHandler);

    } catch (CameraAccessException e) {
      Log.e(TAG, "CameraAccessException", e);
    }
  }

  private void setRepeatingCaptureRequest() {
    try {
      setCameraEffects(previewCaptureRequestBuilder);
      captureSession.setRepeatingRequest(
              previewCaptureRequestBuilder.build(), cameraCaptureCallback, backgroundHandler);
    } catch (CameraAccessException e) {
      Log.e(TAG, "Failed to set repeating request", e);
    }
  }

  private boolean isARCoreSupportedAndUpToDate() {
    ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this);
    switch (availability) {
      case SUPPORTED_INSTALLED:
        break;
      case SUPPORTED_APK_TOO_OLD:
      case SUPPORTED_NOT_INSTALLED:
        try {
          ArCoreApk.InstallStatus installStatus =
                  ArCoreApk.getInstance().requestInstall(this, /*userRequestedInstall=*/ true);
          switch (installStatus) {
            case INSTALL_REQUESTED:
              Log.e(TAG, "ARCore installation requested.");
              return false;
            case INSTALLED:
              break;
          }
        } catch (UnavailableException e) {
          Log.e(TAG, "ARCore not installed", e);
          runOnUiThread(() ->
                  Toast.makeText(
                                  getApplicationContext(),
                                  "ARCore not installed\n" + e,
                                  Toast.LENGTH_LONG)
                          .show());
          finish();
          return false;
        }
        break;
      case UNKNOWN_ERROR:
      case UNKNOWN_CHECKING:
      case UNKNOWN_TIMED_OUT:
      case UNSUPPORTED_DEVICE_NOT_CAPABLE:
        Log.e(TAG, "ARCore is not supported on this device, result: " + availability);
        runOnUiThread(() ->
                Toast.makeText(
                                getApplicationContext(),
                                "ARCore is not supported on this device\nAvailability: " + availability,
                                Toast.LENGTH_LONG)
                        .show());
        return false;
    }
    return true;
  }

  private void closeCamera() {
    if (captureSession != null) {
      captureSession.close();
      captureSession = null;
    }
    if (cameraDevice != null) {
      waitUntilCameraCaptureSessionIsActive();
      safeToExitApp.close();
      cameraDevice.close();
      safeToExitApp.block();
    }
    if (cpuImageReader != null) {
      cpuImageReader.close();
      cpuImageReader = null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // ARCore Pause/Resume
  // ---------------------------------------------------------------------------------------------
  private void pauseARCore() {
    if (arcoreActive) {
      sharedSession.pause();
      isFirstFrameWithoutArcore.set(true);
      arcoreActive = false;
      updateSnackbarMessage();
    }
  }

  private void resumeARCore() {
    if (sharedSession == null) return;
    if (!arcoreActive) {
      try {
        backgroundRenderer.suppressTimestampZeroRendering(false);
        sharedSession.resume();
        arcoreActive = true;
        updateSnackbarMessage();

        sharedCamera.setCaptureCallback(cameraCaptureCallback, backgroundHandler);

      } catch (CameraNotAvailableException e) {
        Log.e(TAG, "Failed to resume ARCore session", e);
        return;
      }
    }
  }

  private void updateSnackbarMessage() {
    messageSnackbarHelper.showMessage(
            this,
            arcoreActive
                    ? "ARCore is active.\nTap on a detected surface to place the letter or add to the path."
                    : "ARCore is paused.");
  }

  // ---------------------------------------------------------------------------------------------
  // GL Renderer Methods
  // ---------------------------------------------------------------------------------------------
  private final AtomicBoolean shouldUpdateSurfaceTexture = new AtomicBoolean(false);

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    surfaceCreated = true;
    GLES20.glClearColor(0f, 0f, 0f, 1.0f);

    try {
      backgroundRenderer.createOnGlThread(this);
      planeRenderer.createOnGlThread(this, "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(this);

      openCamera();

      // Create the LetterRenderer using the instance variable (which is initially empty).
      letterRenderer = new LetterRenderer(this, obtainedLetter);

      // Initialize the 3D path renderer.
      pathRenderer = new PathRenderer();

      // Optionally prompt for file selection for letter and path.
      // (The letter file chooser was already triggered in onCreate.)
      performPathFileSearch();

    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    GLES20.glViewport(0, 0, width, height);
    displayRotationHelper.onSurfaceChanged(width, height);
    runOnUiThread(() ->
            imageTextLinearLayout.setOrientation(
                    width > height ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL));
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    if (!shouldUpdateSurfaceTexture.get()) return;

    displayRotationHelper.updateSessionIfNeeded(sharedSession);
    try {
      if (arMode) {
        onDrawFrameARCore();
      } else {
        onDrawFrameCamera2();
      }
    } catch (Throwable t) {
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  public void onDrawFrameCamera2() {
    SurfaceTexture texture = sharedCamera.getSurfaceTexture();
    if (isFirstFrameWithoutArcore.getAndSet(false)) {
      try {
        texture.detachFromGLContext();
      } catch (RuntimeException e) {
        // Ignore if fails
      }
      texture.attachToGLContext(backgroundRenderer.getTextureId());
    }
    texture.updateTexImage();

    int rotationDegrees = displayRotationHelper.getCameraSensorToDisplayRotation(cameraId);
    Size size = sharedSession.getCameraConfig().getTextureSize();
    float displayAspectRatio =
            displayRotationHelper.getCameraSensorRelativeViewportAspectRatio(cameraId);

    backgroundRenderer.draw(
            size.getWidth(), size.getHeight(), displayAspectRatio, rotationDegrees);
  }

  public void onDrawFrameARCore() throws CameraNotAvailableException {
    if (!arcoreActive) return;
    if (errorCreatingSession) return;

    Frame frame = sharedSession.update();
    com.google.ar.core.Camera camera = frame.getCamera();

    // Handle taps
    handleTap(frame, camera);

    backgroundRenderer.draw(frame);
    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

    if (camera.getTrackingState() == TrackingState.PAUSED) return;

    float[] projmtx = new float[16];
    camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

    float[] viewmtx = new float[16];
    camera.getViewMatrix(viewmtx, 0);

    final float[] colorCorrectionRgba = new float[4];
    frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

    // Point Clouds
    try (PointCloud pointCloud = frame.acquirePointCloud()) {
      pointCloudRenderer.update(pointCloud);
      pointCloudRenderer.draw(viewmtx, projmtx);
    }

    if (messageSnackbarHelper.isShowing()) {
      for (Plane plane : sharedSession.getAllTrackables(Plane.class)) {
        if (plane.getTrackingState() == TrackingState.TRACKING) {
          messageSnackbarHelper.hide(this);
          break;
        }
      }
    }

    planeRenderer.drawPlanes(
            sharedSession.getAllTrackables(Plane.class),
            camera.getDisplayOrientedPose(),
            projmtx);

    // Render: either the 2D Letter cube or the 3D path, depending on show2DLetterBox.
    if (show2DLetterBox) {
      // Draw the letter cube at each AR anchor.
      for (ColoredAnchor coloredAnchor : anchors) {
        if (coloredAnchor.anchor.getTrackingState() != TrackingState.TRACKING) {
          continue;
        }
        coloredAnchor.anchor.getPose().toMatrix(anchorMatrix, 0);

        // Example of an upward offset:
        Matrix.translateM(anchorMatrix, 0, 0, 0.2f, 0);
        float letterScale = 1.0f; // Adjust as needed
        Matrix.scaleM(anchorMatrix, 0, letterScale, letterScale, letterScale);

        // Combine MVP
        float[] mvp = new float[16];
        Matrix.multiplyMM(mvp, 0, viewmtx, 0, anchorMatrix, 0);
        Matrix.multiplyMM(mvp, 0, projmtx, 0, mvp, 0);

        // Draw the cube with the letter.
        letterRenderer.draw(mvp);
      }

    } else {
      // 3D Path mode
      float[] mvpPathMatrix = new float[16];
      Matrix.multiplyMM(mvpPathMatrix, 0, projmtx, 0, viewmtx, 0);
      pathRenderer.draw(mvpPathMatrix);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Handle Touch Input
  // ---------------------------------------------------------------------------------------------
  private void handleTap(Frame frame, com.google.ar.core.Camera camera) {
    MotionEvent tap = tapHelper.poll();
    if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
      if (show2DLetterBox) {
        // 2D Letter Cube mode: Create an AR anchor.
        for (HitResult hit : frame.hitTest(tap)) {
          Trackable trackable = hit.getTrackable();
          if ((trackable instanceof Plane
                  && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                  && (PlaneRenderer.calculateDistanceToPlane(
                  hit.getHitPose(), camera.getPose()) > 0))
                  || (trackable instanceof Point
                  && ((Point) trackable).getOrientationMode()
                  == OrientationMode.ESTIMATED_SURFACE_NORMAL)) {

            if (anchors.size() >= 20) {
              anchors.get(0).anchor.detach();
              anchors.remove(0);
            }
            float[] objColor = new float[] {255f, 255f, 255f, 255f};
            anchors.add(new ColoredAnchor(hit.createAnchor(), objColor));
            break;
          }
        }
      } else {
        // 3D Path mode: add a point to the path.
        for (HitResult hit : frame.hitTest(tap)) {
          Trackable trackable = hit.getTrackable();
          if ((trackable instanceof Plane
                  && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                  && (PlaneRenderer.calculateDistanceToPlane(
                  hit.getHitPose(), camera.getPose()) > 0))
                  || (trackable instanceof Point
                  && ((Point) trackable).getOrientationMode()
                  == OrientationMode.ESTIMATED_SURFACE_NORMAL)) {

            float x = hit.getHitPose().tx();
            float y = hit.getHitPose().ty();
            float z = hit.getHitPose().tz();
            pathRenderer.addPoint(x, y, z);
            break;
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // CPU Image Handling
  // ---------------------------------------------------------------------------------------------
  @Override
  public void onImageAvailable(ImageReader imageReader) {
    Image image = imageReader.acquireLatestImage();
    if (image == null) {
      Log.w(TAG, "onImageAvailable: Skipping null image.");
      return;
    }
    // For demonstration, we just close it.
    image.close();
    cpuImagesProcessed++;
  }

  @Override
  public void onFrameAvailable(SurfaceTexture surfaceTexture) {
    // Not used in AR mode, only used when ARCore is paused.
  }

  // ---------------------------------------------------------------------------------------------
  // Permissions
  // ---------------------------------------------------------------------------------------------
  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(
                      getApplicationContext(),
                      "Camera permission is needed to run this application",
                      Toast.LENGTH_LONG)
              .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Utilities
  // ---------------------------------------------------------------------------------------------
  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  // ---------------------------------------------------------------------------------------------
  // File Selection for Letter and Path
  // ---------------------------------------------------------------------------------------------
  private void performPathFileSearch() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("text/plain");
    startActivityForResult(intent, READ_PATH_REQUEST_CODE);
  }

  private void performLetterFileSearch() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("text/plain");
    startActivityForResult(intent, READ_LETTER_REQUEST_CODE);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode == RESULT_OK && data != null) {
      Uri uri = data.getData();
      try {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in != null) {
          if (requestCode == READ_LETTER_REQUEST_CODE) {
            // Read the letter from the file.
            String letter = convertStreamToString(in).trim();
            // Update the instance variable.
            obtainedLetter = letter;
            // Queue the update on the GL thread.
            final String letterToSet = letter;
            surfaceView.queueEvent(new Runnable() {
              @Override
              public void run() {
                if (letterRenderer != null) {
                  letterRenderer.updateLetter(letterToSet);
                }
              }
            });
          } else if (requestCode == READ_PATH_REQUEST_CODE) {
            // Load the path from the file if you have a PathRenderer.
            pathRenderer.loadFromStream(in);
          }
          in.close();
        }
      } catch (IOException e) {
        Log.e(TAG, "Error loading file: " + e.getMessage());
      }
    }
  }

  private String convertStreamToString(InputStream is) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      sb.append(line);
    }
    reader.close();
    return sb.toString();
  }
}
