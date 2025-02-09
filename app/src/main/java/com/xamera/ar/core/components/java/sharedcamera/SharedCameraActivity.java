/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
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
import com.google.ar.core.examples.java.sharedcamera.R;
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

import java.io.IOException;
import java.io.InputStream;
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
  private TextView statusTextView;       // Not used (hidden)
  private LinearLayout imageTextLinearLayout;

  // ARCore session and shared camera.
  private Session sharedSession;
  private CameraCaptureSession captureSession;
  private CameraManager cameraManager;
  private List<CaptureRequest.Key<?>> keysThatCanCauseCaptureDelaysWhenModified;
  private CameraDevice cameraDevice;
  private HandlerThread backgroundThread;
  private Handler backgroundHandler;
  private SharedCamera sharedCamera;
  private String cameraId;
  private final AtomicBoolean shouldUpdateSurfaceTexture = new AtomicBoolean(false);
  private boolean arcoreActive;
  private boolean surfaceCreated;
  private boolean errorCreatingSession = false;
  private CaptureRequest.Builder previewCaptureRequestBuilder;
  private ImageReader cpuImageReader;
  private int cpuImagesProcessed;

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
  private static final float[] DEFAULT_COLOR = new float[] {0f, 0f, 0f, 0f};

  // Anchors created from taps.
  private final ArrayList<ColoredAnchor> anchors = new ArrayList<>();

  // For testing.
  private static final Short AUTOMATOR_DEFAULT = 0;
  private static final String AUTOMATOR_KEY = "automator";
  private final AtomicBoolean automatorRun = new AtomicBoolean(false);

  // Synchronization.
  private boolean captureSessionChangesPossible = true;
  private final ConditionVariable safeToExitApp = new ConditionVariable();

  // The LetterRenderer for our 2D letter.
  private LetterRenderer letterRenderer;
  // The PathRenderer for our 3D path.
  private PathRenderer pathRenderer;

  // Matrices for computing the final MVP.
  private final float[] mModelMatrix = new float[16];
  private final float[] mMVPMatrix = new float[16];

  // The obtained letter (set via AssignLetter).
  private static String ObtainedLetter = "DENIZ";

  // Flag for controlling the rendering mode.
  // If true, 2D Letter Cube mode is active; if false, 3D Path mode is active.
  private boolean show2DLetterBox = true;

  // Helper class to associate an anchor with a color.
  private static class ColoredAnchor {
    public final Anchor anchor;
    public final float[] color;
    public ColoredAnchor(Anchor a, float[] color4f) {
      this.anchor = a;
      this.color = color4f;
    }
  }

  // ----- Camera and Session Callbacks -----
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
            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                           @NonNull CaptureRequest request,
                                           @NonNull TotalCaptureResult result) {
              shouldUpdateSurfaceTexture.set(true);
            }
            @Override
            public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull Surface target,
                                            long frameNumber) {
              Log.e(TAG, "onCaptureBufferLost: " + frameNumber);
            }
            @Override
            public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureFailure failure) {
              Log.e(TAG, "onCaptureFailed: " + failure.getFrameNumber() + " " + failure.getReason());
            }
            @Override
            public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId) {
              Log.e(TAG, "onCaptureSequenceAborted: " + sequenceId + " " + session);
            }
          };

  // ----- Activity Lifecycle -----
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    // Automatically assign the letter.
    AssignLetter("DENIZ");
    // (Disable status window.)
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

    // Initialize the mode toggle CheckBox.
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

    // (If you have an AR toggle switch in your layout, you can remove it.)
    messageSnackbarHelper.setMaxLines(4);
    updateSnackbarMessage();
  }

  @Override
  protected void onDestroy() {
    if (sharedSession != null) {
      sharedSession.close();
      sharedSession = null;
    }
    super.onDestroy();
  }

  private synchronized void waitUntilCameraCaptureSessionIsActive() {
    while (!captureSessionChangesPossible) {
      try {
        this.wait();
      } catch (InterruptedException e) {
        Log.e(TAG, "Unable to wait for a safe time to make changes to the capture session", e);
      }
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    waitUntilCameraCaptureSessionIsActive();
    startBackgroundThread();
    surfaceView.onResume();
    if (surfaceCreated) openCamera();
    displayRotationHelper.onResume();
    // Optionally, you could also call performFileSearch() here if you prefer.
  }

  @Override
  public void onPause() {
    shouldUpdateSurfaceTexture.set(false);
    surfaceView.onPause();
    waitUntilCameraCaptureSessionIsActive();
    displayRotationHelper.onPause();
    if (arMode) pauseARCore();
    closeCamera();
    stopBackgroundThread();
    super.onPause();
  }

  // ----- Camera and ARCore Methods -----
  private void resumeCamera2() {
    setRepeatingCaptureRequest();
    sharedCamera.getSurfaceTexture().setOnFrameAvailableListener(this);
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

  private void pauseARCore() {
    if (arcoreActive) {
      sharedSession.pause();
      isFirstFrameWithoutArcore.set(true);
      arcoreActive = false;
      updateSnackbarMessage();
    }
  }

  private void updateSnackbarMessage() {
    messageSnackbarHelper.showMessage(
            this,
            arcoreActive
                    ? "ARCore is active.\nTap on a detected surface to place the letter or add to the path."
                    : "ARCore is paused.");
  }

  private void setRepeatingCaptureRequest() {
    try {
      setCameraEffects(previewCaptureRequestBuilder);
      captureSession.setRepeatingRequest(previewCaptureRequestBuilder.build(),
              cameraCaptureCallback, backgroundHandler);
    } catch (CameraAccessException e) {
      Log.e(TAG, "Failed to set repeating request", e);
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
        messageSnackbarHelper.showError(this,
                "Failed to create ARCore session that supports camera sharing");
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
    cpuImageReader = ImageReader.newInstance(desiredCpuImageSize.getWidth(),
            desiredCpuImageSize.getHeight(),
            ImageFormat.YUV_420_888, 2);
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
              " may cause a noticeable capture delay. Please verify actual runtime behavior on specific pre-Android P devices.");
      return false;
    }
  }

  private void setCameraEffects(CaptureRequest.Builder captureBuilder) {
    if (checkIfKeyCanCauseDelay(CaptureRequest.CONTROL_EFFECT_MODE)) {
      Log.w(TAG, "Not setting CONTROL_EFFECT_MODE since it can cause delays between transitions.");
    } else {
      Log.d(TAG, "Setting CONTROL_EFFECT_MODE to SEPIA in non-AR mode.");
      captureBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE,
              CaptureRequest.CONTROL_EFFECT_MODE_SEPIA);
    }
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

  @Override
  public void onFrameAvailable(SurfaceTexture surfaceTexture) {
    // Not used in AR mode.
  }

  @Override
  public void onImageAvailable(ImageReader imageReader) {
    // Status updates are disabled.
    Image image = imageReader.acquireLatestImage();
    if (image == null) {
      Log.w(TAG, "onImageAvailable: Skipping null image.");
      return;
    }
    image.close();
    cpuImagesProcessed++;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(getApplicationContext(),
              "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    surfaceCreated = true;
    GLES20.glClearColor(0f, 0f, 0f, 1.0f);
    try {
      backgroundRenderer.createOnGlThread(this);
      planeRenderer.createOnGlThread(this, "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(this);
      openCamera();
      // Initialize the 2D letter renderer with the obtained letter.
      letterRenderer = new LetterRenderer(this, ObtainedLetter);
      // Initialize the 3D path renderer.
      pathRenderer = new PathRenderer();
      // Automatically prompt for file selection each time this activity is opened.
      performFileSearch();
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    GLES20.glViewport(0, 0, width, height);
    displayRotationHelper.onSurfaceChanged(width, height);
    runOnUiThread(() ->
            imageTextLinearLayout.setOrientation(width > height ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL));
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
        // Ignore if fails.
      }
      texture.attachToGLContext(backgroundRenderer.getTextureId());
    }
    texture.updateTexImage();
    int rotationDegrees = displayRotationHelper.getCameraSensorToDisplayRotation(cameraId);
    Size size = sharedSession.getCameraConfig().getTextureSize();
    float displayAspectRatio = displayRotationHelper.getCameraSensorRelativeViewportAspectRatio(cameraId);
    backgroundRenderer.draw(size.getWidth(), size.getHeight(), displayAspectRatio, rotationDegrees);
  }

  public void onDrawFrameARCore() throws CameraNotAvailableException {
    if (!arcoreActive) return;
    if (errorCreatingSession) return;
    Frame frame = sharedSession.update();
    Camera camera = frame.getCamera();

    // Create an AR anchor or add a path point when the user taps.
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
    planeRenderer.drawPlanes(sharedSession.getAllTrackables(Plane.class),
            camera.getDisplayOrientedPose(), projmtx);

    // Render either the 2D letter(s) or the 3D path based on the current mode.
    if (show2DLetterBox) {
      // Draw the letter at each AR anchor.
      for (ColoredAnchor coloredAnchor : anchors) {
        if (coloredAnchor.anchor.getTrackingState() != TrackingState.TRACKING) continue;
        coloredAnchor.anchor.getPose().toMatrix(anchorMatrix, 0);
        Matrix.translateM(anchorMatrix, 0, 0, 0.2f, 0); // upward offset for floating effect
        float letterScale = 1.0f; // adjust as needed
        Matrix.scaleM(anchorMatrix, 0, letterScale, letterScale, letterScale);
        Matrix.multiplyMM(mMVPMatrix, 0, viewmtx, 0, anchorMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, projmtx, 0, mMVPMatrix, 0);
        letterRenderer.draw(mMVPMatrix);
      }
    } else {
      // Compute a combined MVP matrix as projection * view for the 3D path.
      float[] mvpPathMatrix = new float[16];
      Matrix.multiplyMM(mvpPathMatrix, 0, projmtx, 0, viewmtx, 0);
      pathRenderer.draw(mvpPathMatrix);
    }
  }

  // Modified handleTap:
  // If in 2D Letter Cube mode, create an AR anchor and add it to the anchors list.
  // If in 3D Path mode, add a point to the path using the hit pose.
  private void handleTap(Frame frame, Camera camera) {
    MotionEvent tap = tapHelper.poll();
    if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
      if (show2DLetterBox) {
        // 2D Letter Cube mode: Create an AR anchor for the letter.
        for (HitResult hit : frame.hitTest(tap)) {
          Trackable trackable = hit.getTrackable();
          if ((trackable instanceof Plane
                  && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                  && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
                  || (trackable instanceof Point
                  && ((Point) trackable).getOrientationMode() == OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
            if (anchors.size() >= 20) {
              anchors.get(0).anchor.detach();
              anchors.remove(0);
            }
            float[] objColor = new float[] {255f, 255f, 255f, 255f}; // Unused color
            anchors.add(new ColoredAnchor(hit.createAnchor(), objColor));
            break;
          }
        }
      } else {
        // 3D Path mode: Add a point to the path.
        for (HitResult hit : frame.hitTest(tap)) {
          Trackable trackable = hit.getTrackable();
          if ((trackable instanceof Plane
                  && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                  && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
                  || (trackable instanceof Point
                  && ((Point) trackable).getOrientationMode() == OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
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

  // Utility to check ARCore support.
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
                  Toast.makeText(getApplicationContext(), "ARCore not installed\n" + e, Toast.LENGTH_LONG).show());
          finish();
          return false;
        }
        break;
      case UNKNOWN_ERROR:
      case UNKNOWN_CHECKING:
      case UNKNOWN_TIMED_OUT:
      case UNSUPPORTED_DEVICE_NOT_CAPABLE:
        Log.e(TAG, "ARCore is not supported on this device, ArCoreApk.checkAvailability() returned " + availability);
        runOnUiThread(() ->
                Toast.makeText(getApplicationContext(), "ARCore is not supported on this device, ArCoreApk.checkAvailability() returned " + availability, Toast.LENGTH_LONG).show());
        return false;
    }
    return true;
  }

  // Helper function to assign a letter (or word) to be drawn.
  private static void AssignLetter(String targetLetter) {
    ObtainedLetter = targetLetter;
  }

  // --- New Code for File Selection and Loading Tracking Data ---
  private static final int READ_REQUEST_CODE = 42;

  // Launch file chooser to let the user pick a text file.
  private void performFileSearch() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("text/plain");
    startActivityForResult(intent, READ_REQUEST_CODE);
  }

  // Use the InputStream approach to load the file data.
  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == READ_REQUEST_CODE && resultCode == RESULT_OK) {
      if (data != null) {
        // Obtain the URI for the selected file.
        Uri uri = data.getData();
        try {
          InputStream in = getContentResolver().openInputStream(uri);
          if (in != null) {
            // Load the points into the renderer using the stream.
            pathRenderer.loadFromStream(in);
            in.close();
          }
        } catch (IOException e) {
          Log.e(TAG, "Error loading tracking data: " + e.getMessage());
        }
      }
    }
  }
}
