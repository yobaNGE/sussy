package com.example.animalsclassify;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Bundle;
import android.Manifest;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import com.example.animalsclassify.databinding.ActivityMainBinding;
import com.example.animalsclassify.ml.AnimalsSize150x200;
import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class Main extends AppCompatActivity {
    private ActivityMainBinding binding;
    private PreviewView previewView;

    private Camera camera;
    private int[] size = {150, 200};
    private ActivityResultLauncher<PickVisualMediaRequest> pickVisualLauncher;
    private boolean isFlashOn = false;

    private ImageCapture imageCapture;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        openCamera();
        previewView = binding.previewView;
        registerActivityForPickImage();




        binding.takePic.setOnClickListener( v -> {
                    String name = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(System.currentTimeMillis());

                    ContentValues contentValues = new ContentValues();
                    contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                    contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CNN-Images");

                    ImageCapture.OutputFileOptions outputOptions =
                            new ImageCapture.OutputFileOptions.Builder(getContentResolver(),
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    contentValues).build();

                    imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                            String text = "Success! File location: " + outputFileResults.getSavedUri();
                            classifyImage(getBitmapFromUri(outputFileResults.getSavedUri()));
                            Toast.makeText(getBaseContext(), text, Toast.LENGTH_SHORT).show();
                            Log.d("PhotoPicker", text);
                        }

                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            String text = "Error: " + exception.getMessage();
                            Toast.makeText(getBaseContext(), text, Toast.LENGTH_SHORT).show();
                            Log.e("PhotoPicker", text);
                        }
                    });
        }
        );

        binding.flash.setOnClickListener(v -> {
            if (isFlashOn){
                camera.getCameraControl().enableTorch(false);
                isFlashOn = false;
            }else{
                camera.getCameraControl().enableTorch(true);
                isFlashOn = true;
            }
                }
        );

        binding.gallery.setOnClickListener(v -> {
                    pickVisualLauncher.launch(new PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                            .build());
        }
        );

    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityResultLauncher<String[]> launcher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                result.forEach((permission, res) -> {
                    if (permission.equals(Manifest.permission.CAMERA)) {
                        openCamera();
                    }
                });
            });
            launcher.launch(new String[]{Manifest.permission.CAMERA});
        } else {
            bindPreview();
        }
    }
    private void bindPreview() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Настройка превью
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder().build();
                // Выбор задней камеры
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }
    private void registerActivityForPickImage() {
        pickVisualLauncher = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                Log.d("PhotoPicker", "Selected URI: " + uri);
                classifyImage(getBitmapFromUri(uri));
            } else {
                Log.d("PhotoPicker", "No media selected");
            }
        });
    }
    private Bitmap getBitmapFromUri(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            return Bitmap.createScaledBitmap(BitmapFactory.decodeStream(inputStream), size[0], size[1], true);
        } catch (FileNotFoundException e) {
            Log.d("PhotoPicker1", e.toString());
        }
        return null;
    }
    private String classifyImage(Bitmap image) {
        String[] classes = new String[0];
        int maxPos = 0;
        try {
            AnimalsSize150x200 model = AnimalsSize150x200.newInstance(getApplicationContext());

            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 150, 200, 3}, DataType.FLOAT32);
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * size[0] * size[1] * 3);
            byteBuffer.order(ByteOrder.nativeOrder());

            int[] values = new int[size[0] * size[1]];
            image.getPixels(values, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
            int pixel = 0;
            for (int i = 0; i < size[0]; i++){
                for (int j = 0; j < size[1]; j++){
                    int val = values[pixel++];
                    byteBuffer.putFloat(((val >> 16) & 0xFF) * (1.f / 255.f));
                    byteBuffer.putFloat(((val >> 8) & 0xFF) * (1.f / 255.f));
                    byteBuffer.putFloat((val & 0xFF) * (1.f / 255.f));
                }
            }

            inputFeature0.loadBuffer(byteBuffer);

            AnimalsSize150x200.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();

            float[] confidences = outputFeature0.getFloatArray();
            maxPos = 0;
            float maxConfidence = 0;
            for (int i = 0; i < confidences.length; i++) {
                if (confidences[i] > maxConfidence){
                    maxConfidence = confidences[i];
                    maxPos = i;
                }
            }
            classes = new String[] {"Cat", "Chicken", "Dog", "Elephant", "Horse", "Sheep", "Squirrel"};
            binding.resultView.setVisibility(View.VISIBLE);
            binding.resultView.setText(classes[maxPos]);
            model.close();
        } catch (IOException e) {
            Log.d("PhotoPicker1", e.toString());
        }

        return classes[maxPos];
    }
}
