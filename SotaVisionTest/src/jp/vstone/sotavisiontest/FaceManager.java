package jp.vstone.sotavisiontest;

import java.util.ArrayList;

import jp.pux.lib.PFDRLibrary;
import jp.pux.lib.PFIDLibrary;
import jp.vstone.RobotLib.CRobotUtil;
import jp.vstone.RobotLib.CSotaMotion;
import jp.vstone.camera.CRoboCamera;
import jp.vstone.camera.FaceDetectResult;
import jp.vstone.camera.FaceDetectUpdateListener;
import jp.vstone.camera.FaceDetectLib.FaceUser;

/**
 * Manages face detection, recognition, and registration using Sota's camera.
 *
 * Implements FaceDetectUpdateListener to receive callbacks for:
 *   - Face detection events
 *   - Smile estimation
 *   - Blink detection
 *   - Age and sex estimation
 *   - Facial parts detection
 *
 * Uses polling-based face tracking (CRoboCamera.getDetectResult()) wrapped
 * in a detection thread that triggers listener callbacks.
 */
public class FaceManager implements FaceDetectUpdateListener {

    private static final String TAG = "FaceManager";

    // Camera device path on Sota robot
    private static final String CAMERA_DEVICE = "/dev/video0";

    // Polling interval for face detection (ms)
    private static final int POLL_INTERVAL_MS = 300;

    // Consecutive detections required before triggering face event
    private static final int DETECT_THRESHOLD = 3;

    private final CSotaMotion motion;
    private CRoboCamera camera;

    // Detection state
    private volatile boolean running = false;
    private volatile boolean faceDetected = false;
    private volatile FaceDetectResult latestResult = null;
    private volatile FaceUser latestUser = null;
    private volatile int detectedAge = 0;
    private volatile String detectedGender = "unknown";
    private Thread detectionThread;

    // Consecutive detection counter
    private int consecutiveDetections = 0;

    // Prevents spamming callbacks — only fires once per face encounter
    private volatile boolean eventFired = false;

    // Pause detection callbacks while MainController is processing
    private volatile boolean paused = false;

    // Callback for MainController
    private FaceEventCallback faceEventCallback;

    // ----------------------------------------------------------------
    // Callback interface for external consumers
    // ----------------------------------------------------------------

    public interface FaceEventCallback {
        /** Called when a face is stably detected (after threshold). */
        void onFaceDetected(FaceDetectResult result);

        /** Called when a known user is recognized. */
        void onUserRecognized(FaceUser user);

        /** Called when a new (unknown) face is detected. */
        void onNewFaceDetected(FaceDetectResult result);

        /** Called when age/sex estimation completes. */
        void onAgeEstimated(int age, String gender);

        /** Called when no face is detected (after previously having one). */
        void onFaceLost();
    }

    // ----------------------------------------------------------------
    // Constructor
    // ----------------------------------------------------------------

    public FaceManager(CSotaMotion motion) {
        this.motion = motion;
        log("Initialized");
    }

    // ----------------------------------------------------------------
    // Setup & Lifecycle
    // ----------------------------------------------------------------

    /**
     * Initialize the camera and enable detection features.
     * Must be called before startTracking().
     */
    public void initCamera() {
        try {
            camera = new CRoboCamera(CAMERA_DEVICE, motion);
            camera.setEnableSmileDetect(true);
            camera.setEnableFaceSearch(true);
            camera.setEnableAgeSexDetect(true);
            log("Camera initialized with face search + age/sex detection enabled");
        } catch (Exception e) {
            log("ERROR: Failed to initialize camera: " + e.getMessage());
        }
    }

    /**
     * Start face tracking and detection in a background thread.
     */
    public void startTracking() {
        if (camera == null) {
            log("ERROR: Camera not initialized. Call initCamera() first.");
            return;
        }

        // Start Sota's face tracking (moves head to follow faces)
        camera.StartFaceTraking();
        running = true;
        consecutiveDetections = 0;

        detectionThread = new Thread(new Runnable() {
            @Override
            public void run() {
                detectionLoop();
            }
        }, "FaceDetectionThread");
        detectionThread.setDaemon(true);
        detectionThread.start();
        log("Face tracking started");
    }

    /**
     * Stop face tracking and detection.
     */
    public void stopTracking() {
        running = false;
        if (camera != null) {
            try {
                camera.StopFaceTraking();
            } catch (Exception e) {
                log("WARN: Error stopping face tracking: " + e.getMessage());
            }
        }
        if (detectionThread != null) {
            try {
                detectionThread.join(2000);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        log("Face tracking stopped");
    }

    /** Set the callback for face events. */
    public void setFaceEventCallback(FaceEventCallback callback) {
        this.faceEventCallback = callback;
    }

    /** Pause callbacks (call when MainController is processing an interaction). */
    public void pauseCallbacks() {
        paused = true;
    }

    /** Resume callbacks (call when MainController returns to IDLE). */
    public void resumeCallbacks() {
        paused = false;
        eventFired = false;  // allow new face events next encounter
    }

    // ----------------------------------------------------------------
    // Detection loop (polling -> listener callbacks)
    // ----------------------------------------------------------------

    private void detectionLoop() {
        boolean hadFace = false;

        while (running) {
            try {
                FaceDetectResult result = camera.getDetectResult();
                latestResult = result;

                if (result != null && result.isDetect()) {
                    consecutiveDetections++;
                    faceDetected = true;

                    // Always update age/sex data silently (for later use)
                    if (result.isAgeSexDetect()) {
                        detectedAge = result.getAge();
                        Boolean isMale = result.isMale();
                        detectedGender = (isMale != null && isMale) ? "male" : "female";
                    }

                    // Always update face recognition data silently
                    FaceUser user = camera.getUser(result);
                    latestUser = user;

                    // Only fire callbacks ONCE after threshold, and only if not paused
                    if (consecutiveDetections >= DETECT_THRESHOLD && !eventFired && !paused) {
                        eventFired = true;  // prevent repeated firing

                        // Log via FaceDetectUpdateListener
                        DetectFinish(result, result.getRawImage());
                        if (result.isAgeSexDetect()) {
                            AgeandSexFinish(true, detectedAge,
                                "male".equals(detectedGender) ? 1 : 0);
                        }

                        // Fire callbacks to MainController
                        if (faceEventCallback != null) {
                            // 1. Notify face detected (triggers state transition)
                            faceEventCallback.onFaceDetected(result);

                            // 2. Notify age estimation
                            faceEventCallback.onAgeEstimated(detectedAge, detectedGender);

                            // 3. Notify known/unknown user
                            if (user != null) {
                                if (user.isNewUser()) {
                                    faceEventCallback.onNewFaceDetected(result);
                                } else {
                                    faceEventCallback.onUserRecognized(user);
                                }
                            }
                        }
                    }

                    hadFace = true;

                } else {
                    // No face detected
                    if (hadFace) {
                        faceDetected = false;
                        latestUser = null;
                        consecutiveDetections = 0;
                        hadFace = false;

                        // Only fire onFaceLost if not paused
                        if (!paused && faceEventCallback != null) {
                            faceEventCallback.onFaceLost();
                        }

                        // Reset eventFired so next face encounter triggers again
                        if (!paused) {
                            eventFired = false;
                        }
                    }
                }

            } catch (Exception e) {
                log("WARN: Detection loop error: " + e.getMessage());
            }

            CRobotUtil.wait(POLL_INTERVAL_MS);
        }
    }

    // ----------------------------------------------------------------
    // Face registration (for new users)
    // ----------------------------------------------------------------

    /**
     * Register a new face with the given name.
     * Must be called while a face is currently detected.
     *
     * @param name  The user's name to associate with this face
     * @return true if registration succeeded
     */
    public boolean registerFace(String name) {
        if (camera == null || latestResult == null) {
            log("ERROR: Cannot register — no camera or no detection result");
            return false;
        }

        try {
            FaceUser user = camera.getUser(latestResult);
            if (user == null) {
                log("WARN: No face user available for registration");
                return false;
            }

            user.setName(name);
            boolean success = camera.addUser(user);
            if (success) {
                log("Registered face: " + name);
            } else {
                log("WARN: Face registration failed (face not frontal?)");

                // Retry with fresh detection
                CRobotUtil.wait(500);
                FaceDetectResult freshResult = camera.getDetectResult();
                if (freshResult != null && freshResult.isDetect()) {
                    FaceUser freshUser = camera.getUser(freshResult);
                    if (freshUser != null && freshUser.isNewUser()) {
                        freshUser.setName(name);
                        success = camera.addUser(freshUser);
                        if (success) {
                            log("Registered face on retry: " + name);
                        } else {
                            log("ERROR: Face registration failed on retry");
                        }
                    }
                }
            }
            return success;

        } catch (Exception e) {
            log("ERROR: Face registration exception: " + e.getMessage());
            return false;
        }
    }

    // ----------------------------------------------------------------
    // Query methods
    // ----------------------------------------------------------------

    /** Is a face currently detected? */
    public boolean isFaceDetected() {
        return faceDetected;
    }

    /** Get the latest detection result (may be null). */
    public FaceDetectResult getLatestResult() {
        return latestResult;
    }

    /** Get the latest recognized user (null if unknown or no face). */
    public FaceUser getLatestUser() {
        return latestUser;
    }

    /** Get estimated age from the latest detection. */
    public int getDetectedAge() {
        return detectedAge;
    }

    /** Get estimated gender from the latest detection. */
    public String getDetectedGender() {
        return detectedGender;
    }

    /** Get all registered user names. */
    public String[] getAllRegisteredNames() {
        if (camera == null) return new String[0];
        try {
            return camera.getAllUserNames();
        } catch (Exception e) {
            log("WARN: Could not get registered names: " + e.getMessage());
            return new String[0];
        }
    }

    // ----------------------------------------------------------------
    // FaceDetectUpdateListener implementation
    // ----------------------------------------------------------------

    @Override
    public void DetectFinish(FaceDetectResult result, byte[] image) {
        if (result != null && result.isDetect()) {
            log("DetectFinish: face detected, faceNum=" + result.getFaceNum());
        }
    }

    @Override
    public void SmileFinish(boolean e, int smile) {
        if (e) {
            log("SmileFinish: smile=" + smile);
        }
    }

    @Override
    public void BlinkFinish(boolean e, int left, int right) {
        if (e) {
            log("BlinkFinish: left=" + left + ", right=" + right);
        }
    }

    @Override
    public void AgeandSexFinish(boolean e, int age, int sex) {
        if (e) {
            String gender = (sex == 1) ? "male" : "female";
            log("AgeandSexFinish: age=" + age + ", gender=" + gender);
        }
    }

    @Override
    public void PartsDetectFinish(boolean e,
            ArrayList<PFIDLibrary.PFID_POINT> points,
            PFDRLibrary.PFDR_FACE_ANGLE faceangle) {
        if (e) {
            log("PartsDetectFinish: parts detected"
                + (points != null ? ", points=" + points.size() : ""));
        }
    }

    // ----------------------------------------------------------------
    // Logging
    // ----------------------------------------------------------------

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
