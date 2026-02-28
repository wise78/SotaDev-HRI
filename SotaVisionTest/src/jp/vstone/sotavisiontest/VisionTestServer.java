package jp.vstone.sotavisiontest;

import java.io.*;
import java.net.*;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import javax.imageio.ImageIO;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.CvType;
import org.opencv.imgcodecs.Imgcodecs;

import jp.vstone.RobotLib.CRobotMem;
import jp.vstone.RobotLib.CRobotUtil;
import jp.vstone.RobotLib.CSotaMotion;
import jp.vstone.camera.CRoboCamera;
import jp.vstone.camera.CameraCapture;
import jp.vstone.camera.FaceDetectResult;
import jp.pux.lib.PFAGRLibrary;
import jp.pux.lib.PFIDLibrary;

/**
 * VisionTestServer — TCP server running on Sota robot.
 *
 * Streams camera frames + face detection data to a laptop client.
 * Supports commands: START_TRACKING, STOP_TRACKING, ENABLE_PFAGR,
 * DISABLE_PFAGR, GET_FRAME, TAKE_PHOTO, STATUS, QUIT.
 *
 * Protocol:
 *   Client sends text commands terminated by \n
 *   Server responds with:
 *     - "OK:<msg>\n" for simple commands
 *     - "FRAME\n" + 4-byte metaLen + metaJSON + 4-byte imgLen + jpegBytes
 *     - "PHOTO\n" + 4-byte imgLen + jpegBytes
 *     - "STATUS:<json>\n"
 *     - "ERR:<msg>\n" on error
 *
 * Usage: java jp.vstone.sotavisiontest.VisionTestServer [port]
 */
public class VisionTestServer {

    private static final String TAG = "VisionTestServer";
    private static final String CAMERA_DEVICE = "/dev/video0";
    private static final int DEFAULT_PORT = 8889;

    // Robot hardware
    private CRobotMem mem;
    private CSotaMotion motion;
    private CRoboCamera camera;

    // State
    private volatile boolean tracking = false;
    private volatile boolean pfagrEnabled = false;
    private volatile boolean running = true;

    // Ethnicity detection via reflection
    private Object nativeInstance = null;  // CInterfaceFaceDetect
    private Method nativeGetPfagrRecog = null;
    private Object flib = null;            // FaceDetectLib (for width/height + sync)
    private Field flibWidthField = null;
    private Field flibHeightField = null;
    private volatile String lastEthnicity = null;
    private volatile int lastEthScore = 0;
    private int ethDebugCount = 0;         // Log first few calls for debugging

    // ================================================================
    // Entry point
    // ================================================================

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Usage: VisionTestServer [port]");
                System.out.println("  Default port: " + DEFAULT_PORT);
            }
        }
        new VisionTestServer().run(port);
    }

    // ================================================================
    // Main run loop
    // ================================================================

    private void run(int port) {
        log("========================================");
        log("  Sota Vision Test Server");
        log("========================================");

        // Initialize robot
        initRobot();

        // Initialize camera
        initCamera();

        // Start TCP server
        ServerSocket server = null;
        try {
            server = new ServerSocket(port);
            log("Server listening on port " + port);
            log("Waiting for client connection...");

            while (running) {
                Socket client = server.accept();
                log("Client connected: " + client.getInetAddress().getHostAddress());
                try {
                    handleClient(client);
                } catch (Exception e) {
                    log("Client session error: " + e.getMessage());
                } finally {
                    // Safety: stop tracking when client disconnects
                    if (tracking) {
                        stopTracking();
                    }
                    try { client.close(); } catch (Exception e2) {}
                    log("Client disconnected. Waiting for next connection...");
                }
            }
        } catch (Exception e) {
            log("Server fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
            if (server != null) {
                try { server.close(); } catch (Exception e) {}
            }
        }
    }

    // ================================================================
    // Robot & camera initialization
    // ================================================================

    private void initRobot() {
        mem = new CRobotMem();
        if (mem.Connect()) {
            motion = new CSotaMotion(mem);
            motion.InitRobot_Sota();
            log("Robot connected and initialized");
        } else {
            log("ERROR: Cannot connect to robot memory");
            log("  Make sure this is running on the Sota robot.");
        }
    }

    private void initCamera() {
        if (motion == null) {
            log("WARN: No motion controller — camera not initialized");
            return;
        }
        try {
            camera = new CRoboCamera(CAMERA_DEVICE, motion);
            log("Camera initialized on " + CAMERA_DEVICE);
        } catch (Exception e) {
            log("ERROR: Camera init failed: " + e.getMessage());
        }
    }

    // ================================================================
    // Client handler (one client at a time)
    // ================================================================

    private void handleClient(Socket client) throws IOException {
        client.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(
            new InputStreamReader(client.getInputStream(), "UTF-8"));
        DataOutputStream out = new DataOutputStream(
            new BufferedOutputStream(client.getOutputStream()));

        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim().toUpperCase();
            if (line.isEmpty()) continue;

            try {
                switch (line) {
                    case "START_TRACKING":
                        startTracking();
                        sendOK(out, "TRACKING_STARTED");
                        break;

                    case "STOP_TRACKING":
                        stopTracking();
                        sendOK(out, "TRACKING_STOPPED");
                        break;

                    case "ENABLE_PFAGR":
                        enablePfagr();
                        sendOK(out, "PFAGR_ENABLED");
                        break;

                    case "DISABLE_PFAGR":
                        disablePfagr();
                        sendOK(out, "PFAGR_DISABLED");
                        break;

                    case "GET_FRAME":
                        sendFrame(out);
                        break;

                    case "TAKE_PHOTO":
                        sendPhoto(out);
                        break;

                    case "STATUS":
                        sendStatus(out);
                        break;

                    case "QUIT":
                        sendOK(out, "BYE");
                        return;

                    default:
                        sendError(out, "UNKNOWN_CMD:" + line);
                        break;
                }
            } catch (IOException ioe) {
                throw ioe; // Rethrow IO errors (connection lost)
            } catch (Exception e) {
                log("CMD error [" + line + "]: " + e.getMessage());
                try {
                    sendError(out, e.getMessage());
                } catch (IOException ioe) {
                    throw ioe;
                }
            }
        }
    }

    // ================================================================
    // Command handlers
    // ================================================================

    private void startTracking() {
        if (camera == null) {
            log("WARN: No camera — cannot start tracking");
            return;
        }
        if (!tracking) {
            camera.setEnableSmileDetect(true);
            camera.setEnableFaceSearch(true);
            if (pfagrEnabled) {
                camera.setEnableAgeSexDetect(true);
            }
            camera.StartFaceTraking();
            tracking = true;
            log("Face tracking STARTED");
        }
    }

    private void stopTracking() {
        if (camera != null && tracking) {
            try {
                camera.StopFaceTraking();
            } catch (Exception e) {
                log("WARN: Error stopping tracking: " + e.getMessage());
            }
            tracking = false;
            log("Face tracking STOPPED");
        }
    }

    private void enablePfagr() {
        pfagrEnabled = true;
        if (camera != null) {
            camera.setEnableAgeSexDetect(true);
            initEthnicityDetection();
        }
        log("PFAGR ENABLED (Age/Gender/Race estimation active)");
    }

    private void disablePfagr() {
        pfagrEnabled = false;
        if (camera != null) {
            camera.setEnableAgeSexDetect(false);
        }
        lastEthnicity = null;
        lastEthScore = 0;
        log("PFAGR DISABLED");
    }

    // ================================================================
    // Ethnicity detection via reflection
    // ================================================================

    /**
     * Use reflection to access CRoboCamera.flib (FaceDetectLib)
     * -> flib.libinstance (CInterfaceFaceDetect JNA native interface)
     * which has get_PFAGR_Recog() that returns ethnicity data.
     *
     * The high-level API (FaceDetectLib.getAgeandSex) calls get_PFAGR_Recog
     * with PFAGR_ETH_RESULT but discards it. We call it directly.
     */
    private void initEthnicityDetection() {
        if (nativeInstance != null) return; // Already initialized

        try {
            // Step 1: CRoboCamera.flib (private FaceDetectLib)
            Field flibField = CRoboCamera.class.getDeclaredField("flib");
            flibField.setAccessible(true);
            flib = flibField.get(camera);
            if (flib == null) {
                log("WARN: CRoboCamera.flib is null — cannot init ethnicity");
                return;
            }
            log("  Reflection: got flib = " + flib.getClass().getName());

            // Step 2: FaceDetectLib.libinstance (CInterfaceFaceDetect - JNA native)
            Field libinstanceField = flib.getClass().getDeclaredField("libinstance");
            libinstanceField.setAccessible(true);
            nativeInstance = libinstanceField.get(flib);

            if (nativeInstance == null) {
                log("WARN: FaceDetectLib.libinstance is null — cannot init ethnicity");
                return;
            }
            log("  Reflection: got libinstance = " + nativeInstance.getClass().getName());

            // Step 3: Get width/height fields for image dimensions
            flibWidthField = flib.getClass().getDeclaredField("width");
            flibWidthField.setAccessible(true);
            flibHeightField = flib.getClass().getDeclaredField("height");
            flibHeightField.setAccessible(true);
            log("  Reflection: got width/height fields");

            // Step 4: Find get_PFAGR_Recog method
            for (Method m : nativeInstance.getClass().getMethods()) {
                if (m.getName().equals("get_PFAGR_Recog")) {
                    nativeGetPfagrRecog = m;
                    break;
                }
            }

            if (nativeGetPfagrRecog != null) {
                nativeGetPfagrRecog.setAccessible(true);
                log("Ethnicity detection initialized via native PFAGR");
                log("  Method: " + nativeGetPfagrRecog.toGenericString());
            } else {
                log("WARN: get_PFAGR_Recog method not found on native instance");
                // List available methods for debugging
                for (Method m : nativeInstance.getClass().getMethods()) {
                    if (m.getName().startsWith("get_PFAGR") || m.getName().contains("PFAGR")) {
                        log("  Available: " + m.getName());
                    }
                }
                nativeInstance = null;
            }
        } catch (Exception e) {
            log("WARN: Ethnicity init failed: " + e.getMessage());
            e.printStackTrace();
            nativeInstance = null;
        }
    }

    /**
     * Detect ethnicity using the native PFAGR library directly.
     * Uses face parts from FaceDetectResult (index 4=left eye, 5=right eye).
     * Image dimensions from FaceDetectLib.width/height fields.
     */
    @SuppressWarnings("unchecked")
    private void detectEthnicity(FaceDetectResult result, byte[] rawImage) {
        if (nativeInstance == null || nativeGetPfagrRecog == null) return;
        if (result == null || !result.isDetect()) return;

        try {
            // Get face parts (eye positions) from detection result
            // PFAGR expects: index 4 = left eye, index 5 = right eye
            ArrayList<PFIDLibrary.PFID_POINT> faceparts = result.getFaceparts();
            if (faceparts == null || faceparts.size() < 6) {
                // Fallback: estimate eye positions from face rectangle
                Rectangle[] faces = result.getFaceList();
                if (faces == null || faces.length == 0 || faces[0] == null) return;
                Rectangle face = faces[0];

                PFAGRLibrary.PFAGR_FACE_POSITION facePos = new PFAGRLibrary.PFAGR_FACE_POSITION();
                // Approximate eye positions: 30% and 70% horizontally, 35% vertically
                facePos.eye_lx = (short) (face.x + face.width * 0.30);
                facePos.eye_ly = (short) (face.y + face.height * 0.35);
                facePos.eye_rx = (short) (face.x + face.width * 0.70);
                facePos.eye_ry = (short) (face.y + face.height * 0.35);

                callNativePfagr(rawImage, facePos);
                return;
            }

            // Build PFAGR_FACE_POSITION from eye coordinates (index 4=left, 5=right)
            PFAGRLibrary.PFAGR_FACE_POSITION facePos = new PFAGRLibrary.PFAGR_FACE_POSITION();
            PFIDLibrary.PFID_POINT leftEye = faceparts.get(4);
            PFIDLibrary.PFID_POINT rightEye = faceparts.get(5);
            facePos.eye_lx = leftEye.x;
            facePos.eye_ly = leftEye.y;
            facePos.eye_rx = rightEye.x;
            facePos.eye_ry = rightEye.y;

            callNativePfagr(rawImage, facePos);

        } catch (Exception e) {
            log("WARN: Ethnicity detection error: " + e.getMessage());
        }
    }

    /**
     * Call the native get_PFAGR_Recog function.
     */
    private void callNativePfagr(byte[] rawImage, PFAGRLibrary.PFAGR_FACE_POSITION facePos) {
        try {
            // Get raw image for native call
            if (rawImage == null || rawImage.length == 0) return;

            // Get image dimensions from FaceDetectLib
            short width = 0, height = 0;
            if (flib != null && flibWidthField != null && flibHeightField != null) {
                width = flibWidthField.getShort(flib);
                height = flibHeightField.getShort(flib);
            }
            // Fallback: guess from raw size
            if (width == 0 || height == 0) {
                int[][] sizes = {{640, 480}, {320, 240}, {1280, 720}};
                for (int[] s : sizes) {
                    if (rawImage.length == s[0] * s[1] || rawImage.length == s[0] * s[1] * 3) {
                        width = (short) s[0];
                        height = (short) s[1];
                        break;
                    }
                }
            }
            if (width == 0 || height == 0) return;

            // Prepare result structures
            PFAGRLibrary.PFAGR_AGE_RESULT ageResult = new PFAGRLibrary.PFAGR_AGE_RESULT();
            PFAGRLibrary.PFAGR_GEN_RESULT genResult = new PFAGRLibrary.PFAGR_GEN_RESULT();
            PFAGRLibrary.PFAGR_ETH_RESULT ethResult = new PFAGRLibrary.PFAGR_ETH_RESULT();

            // Write JNA Structures to native memory before call
            facePos.write();
            ageResult.write();
            genResult.write();
            ethResult.write();

            short status;

            // Synchronize on flib to avoid concurrent access with pipeline thread
            synchronized (flib) {
                // Call native: get_PFAGR_Recog(image, width, height, facePos, ageResult, genResult, ethResult)
                status = (Short) nativeGetPfagrRecog.invoke(
                    nativeInstance, rawImage, width, height,
                    facePos, ageResult, genResult, ethResult);
            }

            // Read back JNA Structures from native memory after call
            ageResult.read();
            genResult.read();
            ethResult.read();

            // Diagnostic logging (first 10 calls)
            if (ethDebugCount < 10) {
                ethDebugCount++;
                log("PFAGR debug [" + ethDebugCount + "]: status=" + status
                    + " age=" + ageResult.age + " ageScore=" + ageResult.score
                    + " gen=" + genResult.gen + " genScore=" + genResult.score
                    + " eth=" + ethResult.eth + " ethScore=" + ethResult.score
                    + " imgSize=" + rawImage.length + " w=" + width + " h=" + height
                    + " eyeL=(" + facePos.eye_lx + "," + facePos.eye_ly + ")"
                    + " eyeR=(" + facePos.eye_rx + "," + facePos.eye_ry + ")");
            }

            if (status >= 0) {  // PFAGR_STATUS_OK = 0, PFAGR_STATUS_NG = -1
                lastEthScore = ethResult.score;
                int eth = ethResult.eth;
                // PFAGR_NEGROID=0, PFAGR_MONGOLOID=1, PFAGR_CAUCASOID=2
                if (eth == 0) {
                    lastEthnicity = "Negroid";
                } else if (eth == 1) {
                    lastEthnicity = "Mongoloid";
                } else if (eth == 2) {
                    lastEthnicity = "Caucasoid";
                } else {
                    lastEthnicity = "Unknown(" + eth + ")";
                }
            } else {
                if (ethDebugCount <= 10) {
                    log("PFAGR returned NG status: " + status);
                }
            }
        } catch (Exception e) {
            log("WARN: Native PFAGR call error: " + e.getMessage());
        }
    }

    // ================================================================
    // Frame streaming
    // ================================================================

    /**
     * Send current camera frame + face detection metadata.
     * Protocol: "FRAME\n" + metaLen(4) + metaJSON + imgLen(4) + jpegBytes
     */
    private void sendFrame(DataOutputStream out) throws IOException {
        FaceDetectResult result = null;
        if (tracking && camera != null) {
            result = camera.getDetectResult();
        }

        // Detect ethnicity via native PFAGR (runs alongside age/gender)
        if (pfagrEnabled && result != null && result.isDetect()) {
            byte[] rawForEth = null;
            try { rawForEth = result.getRawImage(); } catch (Exception e) {}
            detectEthnicity(result, rawForEth);
        }

        // Build metadata JSON
        String meta = buildMetadata(result);
        byte[] metaBytes = meta.getBytes("UTF-8");

        // Get JPEG image data
        byte[] jpegData = toJpeg(result);
        int imgLen = (jpegData != null) ? jpegData.length : 0;

        // Write response
        out.writeBytes("FRAME\n");
        out.writeInt(metaBytes.length);
        out.write(metaBytes);
        out.writeInt(imgLen);
        if (imgLen > 0) {
            out.write(jpegData);
        }
        out.flush();
    }

    /**
     * Build JSON metadata string from face detection result.
     */
    private String buildMetadata(FaceDetectResult result) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{");
        sb.append("\"tracking\":").append(tracking);
        sb.append(",\"pfagrEnabled\":").append(pfagrEnabled);

        if (result != null && result.isDetect()) {
            sb.append(",\"faceDetected\":true");
            int faceNum = result.getFaceNum();
            sb.append(",\"faceNum\":").append(faceNum);

            // Face bounding boxes
            sb.append(",\"faces\":[");
            Rectangle[] faceList = result.getFaceList();
            if (faceList != null) {
                boolean first = true;
                for (int i = 0; i < faceList.length; i++) {
                    if (faceList[i] == null) continue;
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"x\":").append(faceList[i].x);
                    sb.append(",\"y\":").append(faceList[i].y);
                    sb.append(",\"w\":").append(faceList[i].width);
                    sb.append(",\"h\":").append(faceList[i].height);
                    sb.append("}");
                }
            }
            sb.append("]");

            // Smile score
            sb.append(",\"smile\":").append(result.getSmile());

            // Face angles (head pose)
            sb.append(",\"pitch\":").append(result.getAnglePitch());
            sb.append(",\"yaw\":").append(result.getAngleYaw());
            sb.append(",\"roll\":").append(result.getAngleRoll());

            // PFAGR data (Age / Gender / Race)
            if (pfagrEnabled && result.isAgeSexDetect()) {
                sb.append(",\"ageDetected\":true");
                sb.append(",\"age\":").append(result.getAge());

                Boolean male = result.isMale();
                String gender;
                if (male == null) {
                    gender = "Unknown";
                } else {
                    gender = male ? "Male" : "Female";
                }
                sb.append(",\"gender\":\"").append(gender).append("\"");

                // Ethnicity (Race) — detected via native PFAGR
                if (lastEthnicity != null) {
                    sb.append(",\"race\":\"").append(lastEthnicity).append("\"");
                    sb.append(",\"raceScore\":").append(lastEthScore);
                }
            }

            // FPS from camera
            double fps = result.getFPS();
            sb.append(",\"fps\":").append(String.format("%.1f", fps));

        } else {
            sb.append(",\"faceDetected\":false");
        }

        sb.append("}");
        return sb.toString();
    }

    // ================================================================
    // Photo capture
    // ================================================================

    /**
     * Capture a high-resolution still photo and send to client.
     * Protocol: "PHOTO\n" + imgLen(4) + jpegBytes
     *
     * Note: Face tracking must be stopped before still capture.
     * It will be resumed automatically after capture.
     */
    private void sendPhoto(DataOutputStream out) throws IOException {
        if (camera == null) {
            sendError(out, "NO_CAMERA");
            return;
        }

        boolean wasTracking = tracking;
        byte[] photoData = null;

        try {
            // Stop tracking (required before still capture)
            if (wasTracking) {
                camera.StopFaceTraking();
                tracking = false;
                CRobotUtil.wait(300);
            }

            // Initialize still capture (5MP, MJPEG)
            camera.initStill(new CameraCapture(
                CameraCapture.CAP_IMAGE_SIZE_5Mpixel,
                CameraCapture.CAP_FORMAT_MJPG));
            CRobotUtil.wait(500); // Let camera stabilize

            // Capture
            BufferedImage img = camera.StillPicture();
            if (img != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(img, "jpg", bos);
                photoData = bos.toByteArray();
                log("Photo captured: " + img.getWidth() + "x" + img.getHeight()
                    + " (" + photoData.length + " bytes)");
            } else {
                log("WARN: StillPicture returned null");
            }

        } catch (Exception e) {
            log("Photo capture error: " + e.getMessage());
        }

        // Resume tracking if it was on
        if (wasTracking) {
            try {
                CRobotUtil.wait(200);
                camera.setEnableSmileDetect(true);
                camera.setEnableFaceSearch(true);
                if (pfagrEnabled) {
                    camera.setEnableAgeSexDetect(true);
                }
                camera.StartFaceTraking();
                tracking = true;
                log("Face tracking resumed after photo");
            } catch (Exception e) {
                log("WARN: Could not resume tracking: " + e.getMessage());
            }
        }

        // Send photo data
        if (photoData != null) {
            out.writeBytes("PHOTO\n");
            out.writeInt(photoData.length);
            out.write(photoData);
            out.flush();
        } else {
            sendError(out, "CAPTURE_FAILED");
        }
    }

    // ================================================================
    // Image conversion
    // ================================================================

    /**
     * Convert FaceDetectResult raw image to JPEG bytes.
     * The camera returns raw BGR pixel data from OpenCV Mat.
     * We use OpenCV imencode to convert to JPEG.
     */
    private byte[] toJpeg(FaceDetectResult result) {
        if (result == null) return null;

        byte[] raw = null;
        try {
            raw = result.getRawImage();
        } catch (Exception e) {
            log("WARN: getRawImage() failed: " + e.getMessage());
            return null;
        }

        if (raw == null || raw.length == 0) return null;

        // Check if already JPEG (magic bytes: FF D8)
        if (raw.length > 2
                && (raw[0] & 0xFF) == 0xFF
                && (raw[1] & 0xFF) == 0xD8) {
            return raw;
        }

        // Try encoding raw BGR pixel data using OpenCV
        try {
            // Detect image dimensions from raw byte count
            // raw = width * height * channels (BGR = 3)
            int[][] knownSizes = {
                {640, 480},    // VGA
                {320, 240},    // QVGA
                {1280, 720},   // HD
                {1920, 1080},  // Full HD
                {800, 600},    // SVGA
                {1024, 768},   // XGA
            };

            for (int[] size : knownSizes) {
                int w = size[0], h = size[1];
                if (raw.length == w * h * 3) {
                    Mat mat = new Mat(h, w, CvType.CV_8UC3);
                    mat.put(0, 0, raw);
                    MatOfByte buf = new MatOfByte();
                    Imgcodecs.imencode(".jpg", mat, buf);
                    byte[] jpg = buf.toArray();
                    mat.release();
                    buf.release();
                    return jpg;
                }
            }

            // If not matching BGR sizes, try as grayscale
            for (int[] size : knownSizes) {
                int w = size[0], h = size[1];
                if (raw.length == w * h) {
                    Mat mat = new Mat(h, w, CvType.CV_8UC1);
                    mat.put(0, 0, raw);
                    MatOfByte buf = new MatOfByte();
                    Imgcodecs.imencode(".jpg", mat, buf);
                    byte[] jpg = buf.toArray();
                    mat.release();
                    buf.release();
                    return jpg;
                }
            }

            log("WARN: Raw image size " + raw.length + " doesn't match any known resolution");
        } catch (Exception e) {
            log("WARN: OpenCV encode failed: " + e.getMessage());
        }

        // Fallback: try ImageIO decode
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
            if (img != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(img, "jpg", bos);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            // Not a standard image format
        }

        log("WARN: Could not convert raw image (" + raw.length + " bytes) to JPEG");
        return null;
    }

    // ================================================================
    // Protocol helpers
    // ================================================================

    private void sendOK(DataOutputStream out, String msg) throws IOException {
        out.writeBytes("OK:" + msg + "\n");
        out.flush();
    }

    private void sendError(DataOutputStream out, String msg) throws IOException {
        out.writeBytes("ERR:" + msg + "\n");
        out.flush();
    }

    private void sendStatus(DataOutputStream out) throws IOException {
        String json = "{"
            + "\"tracking\":" + tracking
            + ",\"pfagrEnabled\":" + pfagrEnabled
            + ",\"cameraReady\":" + (camera != null)
            + ",\"robotConnected\":" + (mem != null)
            + "}";
        out.writeBytes("STATUS:" + json + "\n");
        out.flush();
    }

    // ================================================================
    // Cleanup
    // ================================================================

    private void cleanup() {
        log("Cleaning up...");
        if (tracking && camera != null) {
            try { camera.StopFaceTraking(); } catch (Exception e) {}
        }
        if (camera != null) {
            try { camera.closeCapture(); } catch (Exception e) {}
        }
        log("Cleanup complete");
    }

    // ================================================================
    // Logging
    // ================================================================

    private void log(String msg) {
        System.out.println("[" + TAG + "] " + msg);
    }
}
