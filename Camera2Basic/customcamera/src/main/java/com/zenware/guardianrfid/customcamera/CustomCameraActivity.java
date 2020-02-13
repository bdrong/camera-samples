package com.zenware.guardianrfid.customcamera;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.example.android.camera2.common.AutoFitSurfaceView;
import com.example.android.camera2.common.CameraSizesKt;
import com.example.android.camera2.common.OrientationLiveData;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Observable;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class CustomCameraActivity extends AppCompatActivity {

    /**
     * Combination of all flags required to put activity into immersive mode
     */
    private static final int FLAGS_FULLSCREEN =
            View.SYSTEM_UI_FLAG_LOW_PROFILE |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    private static final long IMMERSIVE_FLAG_TIMEOUT = 500L;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final String TAG = CustomCameraActivity.class.getSimpleName();

    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    /**
     * {@link CameraCharacteristics} corresponding to the provided Camera ID
     */
    private CameraCharacteristics characteristics;

    /**
     * Internal ref. to the ongoing {@link CameraCaptureSession} configured with our parameters.
     */
    private CameraCaptureSession cameraCaptureSessions;
    private int cameraFacing;

    private String cameraId;

    /**
     * The @link{CameraDevice} that will be opened.
     */
    private CameraDevice cameraDevice;
    /**
     * {@link Handler} corresponding to cameraThread.
     */
    private Handler cameraHandler;
    /**
     * Detects, characterizes, and connects to a CameraDevice (used for all camera operations)
     */
    private CameraManager cameraManager;


    /**
     * {@link HandlerThread} where all camera operations run.
     */
    private HandlerThread cameraThread;

    private CaptureRequest captureRequest;
    private CaptureRequest.Builder captureRequestBuilder;
    private File file;
    private boolean mFlashSupported;
    private Size imageDimension;

    /**
     * Readers used as buffers for camera still shots.
     */
    private ImageReader imageReader;

    /**
     * {@link HandlerThread} where all buffer reading operations run
     */
    private HandlerThread imageReaderThread;

    /**
     * {@link Handler} corresponding to imageReaderThread.
     */
    private Handler imageReaderHandler;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    /**
     * Where the camera preview is displayed.
     */
    private SurfaceView viewFinder;
    private Button btnTakePicture;
    private TextureView textureView;


    /**
     * Live data listener for changes in the device orientation relative to the camera.
     */
    private OrientationLiveData relativeOrientation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = (TextureView) findViewById(R.id.view_texture);
        if (textureView != null) {
            textureView.setSurfaceTextureListener(textureListener);
        }

        btnTakePicture = findViewById(R.id.btn_take_picture);
        if (btnTakePicture != null) {
            btnTakePicture.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    takePicture();
                }
            });
        }

    }

    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
        //closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(
                        CustomCameraActivity.this,
                        "You must grant app permission to use camera in order to take a photo.",
                        Toast.LENGTH_LONG
                ).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume");
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    private void closeCamera() {
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
    }

    protected void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == cameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(CustomCameraActivity.this, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        Log.e(TAG, "is camera open");
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(CustomCameraActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.e(TAG, "openCamera X");
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {

            Log.d(TAG, "Camera " + camera.getId() + " opened");

            cameraDevice = camera;

            createCameraPreview();

        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {

        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {

            cameraDevice.close();

            cameraDevice = null;

            String errorMessage;

            switch (error) {

                case ERROR_CAMERA_DEVICE:
                    errorMessage = "Fatal (device)";
                    break;

                case ERROR_CAMERA_DISABLED:
                    errorMessage = "Device Policy";
                    break;

                case ERROR_CAMERA_IN_USE:
                    errorMessage = "Camera in use";
                    break;

                case ERROR_CAMERA_SERVICE:
                    errorMessage = "Fatal (service)";
                    break;

                case ERROR_MAX_CAMERAS_IN_USE:
                    errorMessage = "Maximum number of cameras in use";
                    break;

                default:
                    errorMessage = "Unknown error";
                    break;

            }

            RuntimeException runtimeException = new RuntimeException("Camera " +
                    camera.getId() + " error: (" + error + ") " + errorMessage);

            Log.e(TAG, runtimeException.getMessage(), runtimeException);

            throw runtimeException;
        }

    };

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    protected void takePicture() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Size[] jpegSizes = null;
            if (characteristics != null) {
                jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            }
            int width = 640;
            int height = 480;
            if (jpegSizes != null && 0 < jpegSizes.length) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }
            ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(reader.getSurface());
            outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(reader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            // Orientation
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
            final File file = new File(Environment.getExternalStorageDirectory() + "/pic.jpg");
            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }

                private void save(byte[] bytes) throws IOException {
                    OutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } finally {
                        if (null != output) {
                            output.close();
                        }
                    }
                }
            };
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler);
            final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Toast.makeText(CustomCameraActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
                    createCameraPreview();
                }
            };
            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        session.capture(captureBuilder.build(), captureListener, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //open your camera here

            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    protected void updatePreview() {
        if (null == cameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Begin all camera operations.This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
//    private void initializeCamera() throws CameraAccessException {
//
//        openCamera(cameraManager, String.valueOf(cameraFacing), cameraHandler);
//
//        // Get largest size
//        Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
//                .getOutputSizes(256);
//
//        int largestSize = 0;
//
//        Size size = null;
//
//        for (Size s : sizes) {
//            if ((s.getHeight() * s.getWidth()) > largestSize) {
//                largestSize = s.getHeight() * s.getWidth();
//                size = s;
//            }
//        }
//
//        imageReader = ImageReader.newInstance(
//                size.getWidth(), size.getHeight(), 256, 3
//        );
//
//        List<Surface> targets =
//                Arrays.asList(viewFinder.getHolder().getSurface(), imageReader.getSurface());
//
//        createCaptureSession(cameraDevice, targets, cameraHandler);
//
//        CaptureRequest.Builder captureRequest =
//                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
//        captureRequest.addTarget(viewFinder.getHolder().getSurface());
//
//        cameraCaptureSession.setRepeatingRequest(captureRequest.build(), null, cameraHandler);
//
//        viewFinder.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                v.setEnabled(false);
//            }
//        });
//
//    }

//    @Override
//    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
//                                           int[] grantResults) {
//
//        for (int i = 0; i < permissions.length; i++) {
//            if (permissions[i].equals(Manifest.permission.CAMERA) &&
//                    grantResults[i] == PackageManager.PERMISSION_GRANTED) {
//                try {
//                    initializeCamera();
//                } catch (CameraAccessException e) {
//                    e.printStackTrace();
//                }
//            } else {
//                // Alert user they must give permission
//                AlertDialog.Builder builder = new AlertDialog.Builder(this);
//                builder.setMessage("Camera permission is required to take photo.");
//                builder.show();
//            }
//        }
//
//    }
//        @Override
//        protected void onResume() {
//
//            super.onResume();

//        final ConstraintLayout layout = findViewById(R.id.lyt_camera);
//        layout.postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                layout.setSystemUiVisibility(FLAGS_FULLSCREEN);
//            }
//        }, IMMERSIVE_FLAG_TIMEOUT);
//        }

    /**
     * Starts a {@link CameraCaptureSession} and returns the configured session
     */
//    private void createCaptureSession(CameraDevice device, List<Surface> targets,
//                                                      Handler handler) throws CameraAccessException {
//
//        device.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
//            @Override
//            public void onConfigured(@NonNull CameraCaptureSession session) {
//                cameraCaptureSession = session;
//            }
//
//            @Override
//            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
//                RuntimeException runtimeException = new RuntimeException("Camera " +
//                        session.getDevice().getId() + " session configuration failed");
//                Log.e(TAG, runtimeException.getMessage(), runtimeException);
//                throw runtimeException;
//            }
//        }, handler);
//
//    }

//    private void openCamera(CameraManager manager, String cameraId, Handler handler) {
//
//        try {
//
//            int permission = ContextCompat.checkSelfPermission(this,
//                    Manifest.permission.CAMERA);
//            int packageManagerPermission = PackageManager.PERMISSION_GRANTED;
//
//            if (permission == PackageManager.PERMISSION_GRANTED) {
//
//                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
//
//
//                    @Override
//                    public void onOpened(@NonNull CameraDevice camera) {
//                        Log.d(TAG, "Camera " + camera.getId() + " Opened");
//                        cameraDevice = camera;
//                    }
//
//                    @Override
//                    public void onDisconnected(@NonNull CameraDevice camera) {
//
//                        Log.w(TAG, "Camera " + camera.getId() + " disconnected.");
//                        finish();
//
//                    }
//
//                    @Override
//                    public void onError(@NonNull CameraDevice camera, int error) {
//
//                        String errorMessage;
//
//                        switch (error) {
//
//                            case ERROR_CAMERA_DEVICE:
//                                errorMessage = "Fatal (device)";
//                                break;
//
//                            case ERROR_CAMERA_DISABLED:
//                                errorMessage = "Device Policy";
//                                break;
//
//                            case ERROR_CAMERA_IN_USE:
//                                errorMessage = "Camera in use";
//                                break;
//
//                            case ERROR_CAMERA_SERVICE:
//                                errorMessage = "Fatal (service)";
//                                break;
//
//                            case ERROR_MAX_CAMERAS_IN_USE:
//                                errorMessage = "Maximum number of cameras in use";
//                                break;
//
//                            default:
//                                errorMessage = "Unknown error";
//                                break;
//
//                        }
//
//                        RuntimeException runtimeException = new RuntimeException("Camera " +
//                                camera.getId() + " error: (" + error + ") " + errorMessage);
//
//                        Log.e(TAG, runtimeException.getMessage(), runtimeException);
//
//                        throw runtimeException;
//
//                    }
//                }, handler);
//
//            } else {
//
////                final Activity activity = (CustomCameraActivity) this.getApplicationContext();
//                Log.i(TAG, "Permission to use camera denied");
//                // Ask for camera permission
//                AlertDialog.Builder builder = new AlertDialog.Builder(this);
//                ActivityCompat.requestPermissions(
//                        this, new String[] {Manifest.permission.CAMERA}, 1234
//                );
////                builder.setMessage("Mobile Command requires the following permissions to be " +
////                        "configured to function correctly.");
////                builder.setPositiveButton("OK", new DialogInterface.OnClickListener()
////                {
////                    @Override public void onClick(DialogInterface dialogInterface, int i)
////                    {
////                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
////                            requestPermissions(new String[]{Manifest.permission.CAMERA},
////                                    1);
////                        } else {
////                            ActivityCompat.requestPermissions(activity,
////                                    new String[] {Manifest.permission.CAMERA},
////                                    1
////                                    );
////                        }
////                    }
////                });
////                builder.show();
//            }
//
//        } catch (CameraAccessException exception) {
//            Log.e(TAG, "openCamera(): " + exception.getLocalizedMessage());
//        }
//    }
}
