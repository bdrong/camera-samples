package com.zenware.guardianrfid.customcamera;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraMetadata;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import com.example.android.camera2.common.AutoFitSurfaceView;
import com.example.android.camera2.common.CameraSizesKt;
import com.example.android.camera2.common.OrientationLiveData;

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
    private static final String TAG = CustomCameraActivity.class.getSimpleName();

    /**
     * {@link CameraCharacteristics} corresponding to the provided Camera ID
     */
    private CameraCharacteristics characteristics;

    private int cameraFacing;

    /**
     * Detects, characterizes, and connects to a CameraDevice (used for all camera operations)
     */
    private CameraManager cameraManager;

    /**
     * {@link Handler} corresponding to cameraThread.
     */
    private Handler cameraHandler;

    /**
     * {@link HandlerThread} where all camera operations run.
     */
    private HandlerThread cameraThread;

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

    /**
     * Where the camera preview is displayed.
     */
    private SurfaceView viewFinder;

    /**
     * The @link{CameraDevice} that will be opened.
     */
    private CameraDevice cameraDevice;

    /**
     * Internal ref. to the ongoing {@link CameraCaptureSession} configured with our parameters.
     */
    private CameraCaptureSession session;

    /**
     * Live data listener for changes in the device orientation relative to the camera.
     */
    private OrientationLiveData relativeOrientation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        final SurfaceView cameraView = findViewById(R.id.surface_view);
        viewFinder = (AutoFitSurfaceView) cameraView;

        viewFinder.getHolder().addCallback(new SurfaceHolder.Callback() {

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceCreated(SurfaceHolder holder) {

                Display display = viewFinder.getDisplay();

                Size previewSize = CameraSizesKt.getPreviewOutputSize(
                        display, characteristics, SurfaceHolder.class, null
                );

                Log.d(TAG, "View finder size: " +
                        viewFinder.getWidth() +
                        " x " + viewFinder.getHeight());
                Log.d(TAG, "Selected preview size: " + previewSize);

                viewFinder.getHolder().setFixedSize(previewSize.getWidth(), previewSize.getHeight());
                ((AutoFitSurfaceView) cameraView).setAspectRatio(
                        previewSize.getWidth(), previewSize.getHeight()
                );

                cameraView.post(new Runnable() {
                    @Override
                    public void run() {
                        initializeCamera();
                    }
                });

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {

            }
        });


        try {

            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

            if (cameraManager != null) {

                for (String cameraId : cameraManager.getCameraIdList()) {

                    characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    cameraFacing = characteristics.get(CameraCharacteristics.LENS_FACING);

                }

            }

            relativeOrientation = new OrientationLiveData(getApplicationContext(), characteristics);
            relativeOrientation.observe(this, new Observer<Integer>() {
                @Override
                public void onChanged(Integer relativeOrientation) {
                    Log.d(TAG, "Orientation changed: " + relativeOrientation);
                }
            });

            cameraThread = new HandlerThread("CameraThread");
            cameraThread.start();

            cameraHandler = new Handler(cameraThread.getLooper());

            imageReaderThread = new HandlerThread("imageReaderThread");
            imageReaderThread.start();

            imageReaderHandler = new Handler(imageReaderThread.getLooper());

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
    private void initializeCamera() {

        openCamera(cameraManager, String.valueOf(cameraFacing), cameraHandler);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           int[] grantResults) {

        for (int i = 0; i < permissions.length; i++) {
            if (permissions[i].equals(Manifest.permission.CAMERA) &&
                    grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                initializeCamera();
            } else {
                // Alert user they must give permission
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage("Camera permission is required to take photo.");
                builder.show();
            }
        }

    }

    @Override
    protected void onResume() {

        super.onResume();

        final ConstraintLayout layout = findViewById(R.id.lyt_camera);
        layout.postDelayed(new Runnable() {
            @Override
            public void run() {
                layout.setSystemUiVisibility(FLAGS_FULLSCREEN);
            }
        }, IMMERSIVE_FLAG_TIMEOUT);
    }

    private void openCamera(CameraManager manager, String cameraId, Handler handler) {

        try {

            if (ContextCompat.checkSelfPermission(getApplicationContext(),
                    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

                manager.openCamera(cameraId, new CameraDevice.StateCallback() {


                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        Log.d(TAG, "Camera " + camera.getId() + " Opened");
                        cameraDevice = camera;
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {

                        Log.w(TAG, "Camera " + camera.getId() + " disconnected.");
                        finish();

                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {

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
                }, handler);

            } else {

                // Ask for camera permission
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage("Mobile Command requires the following permissions to be " +
                        "configured to function correctly.");
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener()
                {
                    @Override public void onClick(DialogInterface dialogInterface, int i)
                    {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        {
                            requestPermissions(new String[]{Manifest.permission.CAMERA},
                                    1);
                        }
                    }
                });
                builder.show();
            }

        } catch (CameraAccessException exception) {
            Log.e(TAG, "openCamera(): " + exception.getLocalizedMessage());
        }
    }

}
