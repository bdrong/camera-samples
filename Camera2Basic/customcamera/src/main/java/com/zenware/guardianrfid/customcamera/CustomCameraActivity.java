package com.zenware.guardianrfid.customcamera;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.Observer;

import android.content.Context;
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
    private CameraDevice camera;

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
        final View cameraView = findViewById(R.id.lyt_camera);
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

        relativeOrientation = new OrientationLiveData(getApplicationContext(), characteristics);
        relativeOrientation.observe(this, new Observer<Integer>() {
            @Override
            public void onChanged(Integer relativeOrientation) {
                Log.d(TAG, "Orientation changed: " + relativeOrientation);
            }
        });

        try {

            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

            if (cameraManager != null) {

                for (String cameraId : cameraManager.getCameraIdList()) {

                    characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

//                    if (facing != null && facing.equals(CameraCharacteristics.LENS_FACING_FRONT));

                }

            }

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

//        camera = openCamera()

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

//    private CameraDevice openCamera(CameraManager manager, String cameraId, Handler handler) {

//        try {
//            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
//
//                @Override
//                public void onOpened(@NonNull CameraDevice camera) {
//
//                }
//
//                @Override
//                public void onDisconnected(@NonNull CameraDevice camera) {
//
//                }
//
//                @Override
//                public void onError(@NonNull CameraDevice camera, int error) {
//
//                }
//            }, handler);
//        } catch (CameraAccessException exception) {
//            Log.e(TAG, "openCamera(): " + exception.getLocalizedMessage());
//        }
//    }
}
