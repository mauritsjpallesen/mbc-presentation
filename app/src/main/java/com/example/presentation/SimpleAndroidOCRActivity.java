package com.example.presentation;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPInputStream;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;

import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.vision.v1.Vision;
import com.google.api.services.vision.v1.model.AnnotateImageRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesResponse;
import com.google.api.services.vision.v1.model.Feature;
import com.google.api.services.vision.v1.model.Image;
import com.googlecode.tesseract.android.TessBaseAPI;

import org.json.JSONException;
import org.json.JSONObject;

public class SimpleAndroidOCRActivity extends Activity {
    public static final String PACKAGE_NAME = "com.datumdroid.android.ocr.simple";

    // You should have the trained data file in assets folder
    // You can get them at:
    // https://github.com/tesseract-ocr/tessdata
    public static final String lang = "eng";

    private static final String TAG = "SimpleAndroidOCR.java";
    static final int REQUEST_IMAGE_CAPTURE = 1;

    private String DATA_PATH;
    private String currentPhotoPath;
    private Uri currentPhotoUri;
    protected Button _button;
    protected ImageView _image;
    protected EditText _field;
    protected String _path;
    protected boolean _taken;

    protected static final String PHOTO_TAKEN = "photo_taken";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen);
        getWindow().getDecorView().setBackgroundColor(Color.WHITE);
        DATA_PATH = getFilesDir().getPath();

        getStorageAccessPermissions();
        ActivityCompat.requestPermissions(SimpleAndroidOCRActivity.this, new String[] {Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);

        String dirPath = DATA_PATH + "/tessdata/";
        File f = new File(dirPath);
        if (f.mkdirs())
            Log.v(TAG, "Directory was made: " + dirPath);
        else
            Log.v(TAG, "ERROR CREATING DIRECTORY: " + dirPath);
        // lang.traineddata file with the app (in assets folder)
        // You can get them at:
        // http://code.google.com/p/tesseract-ocr/downloads/list
        // This area needs work and optimization
        if (!(new File(dirPath + lang + ".traineddata")).exists()) {
            try {

                AssetManager assetManager = getAssets();
                InputStream in = assetManager.open("tessdata/" + lang + ".traineddata");
                //GZIPInputStream gin = new GZIPInputStream(in);
                OutputStream out = new FileOutputStream(DATA_PATH + "/tessdata/" + lang + ".traineddata");

                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                //while ((lenf = gin.read(buff)) > 0) {
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                in.close();
                //gin.close();
                out.close();

                Log.v(TAG, "Copied " + lang + " traineddata");
            } catch (IOException e) {
                Log.e(TAG, "Was unable to copy " + lang + " traineddata " + e.toString());
            }
        }

        _image = (ImageView) findViewById(R.id.image);
        _field = (EditText) findViewById(R.id.field);
        _button = (Button) findViewById(R.id.button);
        _button.setOnClickListener(new ButtonClickHandler());

        _path = DATA_PATH + "/ocr.jpg";
    }

    public class ButtonClickHandler implements View.OnClickListener {
        public void onClick(View view) {
            Log.v(TAG, "Starting Camera app");
            dispatchTakePictureIntent();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        Log.i(TAG, "resultCode: " + resultCode);

        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            Bitmap imageBitmap = null;
            try {
                imageBitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), currentPhotoUri);
            } catch (IOException e) {
                Log.v(TAG, "Error creating bitmap from URI: " + currentPhotoUri + "\n stacktrace: \n" + Arrays.toString(e.getStackTrace()));
            }
            onPhotoTaken(imageBitmap);
        } else {
            Log.v(TAG, "User cancelled");
        }
    }

    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // Ensure that there's a camera activity to handle the intent
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            // Create the File where the photo should go
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                // Error occurred while creating the File
                Log.v(TAG, "Error while creating image file");
            }
            // Continue only if the File was successfully created
            if (photoFile != null) {
                Uri photoURI = FileProvider.getUriForFile(this,
                        "com.example.presentation.fileprovider",
                        photoFile);
                currentPhotoUri = photoURI;
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            }
        }
    }

    private File createImageFile() throws IOException {
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",         /* suffix */
                storageDir      /* directory */
        );

        // Save a file: path for use with ACTION_VIEW intents
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    protected void onPhotoTaken(Bitmap bitmap) {
        _taken = true;
        Log.v(TAG, "photo width: " + bitmap.getWidth() + "\tphoto height: " + bitmap.getHeight());

        if (currentPhotoUri == null) {
            Log.v(TAG, "current photo uri is null");
            return;
        }

        _image.setImageBitmap(bitmap);
        String base64string = bitmapToBase64string(bitmap);
        sendGoogleVisionRequest(base64string);
        Log.v(TAG, "Before baseApi");

        TessBaseAPI baseApi = new TessBaseAPI();
        baseApi.setDebug(true);
        baseApi.init(DATA_PATH, lang);
        baseApi.setImage(bitmap);

        String recognizedText = baseApi.getUTF8Text();

        baseApi.end();

        // You now have the text in recognizedText var, you can do anything with it.
        // We will display a stripped out trimmed alpha-numeric version of it (if lang is eng)
        // so that garbage doesn't make it to the display.

        Log.v(TAG, "OCRED TEXT: " + recognizedText);

        if ( lang.equalsIgnoreCase("eng") ) {
            recognizedText = recognizedText.replaceAll("[^a-zA-Z0-9]+", " ");
        }

        recognizedText = recognizedText.trim();

        if ( recognizedText.length() != 0 ) {
            _field.setText(_field.getText().toString().length() == 0 ? recognizedText : _field.getText() + " " + recognizedText);
            _field.setSelection(_field.getText().toString().length());
        }

        // Cycle done.
    }

    @TargetApi(23)
    private void getStorageAccessPermissions() {
        String[] permissions = new String[] {
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.INTERNET,
                Manifest.permission.GET_ACCOUNTS
        };

        boolean hasAllPermissions = true;

        for (String permission : permissions) {
            int hasPermission = checkSelfPermission(permission);
            if (hasPermission != PackageManager.PERMISSION_GRANTED) {
                hasAllPermissions = false;
                break;
            }
        }

        if (!hasAllPermissions)
            requestPermissions(permissions, 4);
    }

    private void sendGoogleVisionRequest(String base64string) {
        String endPoint = "https://eu-vision.googleapis.com/vy1/images:annotate";
        String apiKey = "AIzaSyC2hoD4qJtMXfgNsFcSLShGw4bK3KGVxiE";
        String url = endPoint + "?key=" + apiKey;
        String jsonBody = "{\"requests\":[{\"features\":[{\"type\":\"DOCUMENT_TEXT_DETECTION\"}],\"imageContext\":{\"languageHints\":[\"da-t-i0-handwrit\"]},\"image\":{\"content\":\"" + base64string + "\"}}]}";

        JSONObject body;
        try {
            body = new JSONObject(jsonBody);
        } catch (JSONException e) {
            Log.v(TAG, "JSON creation failure: " + Arrays.toString(e.getStackTrace()));
            return;
        }

        // Instantiate the RequestQueue.
        RequestQueue queue = Volley.newRequestQueue(this);

        JsonObjectRequest jsonObjectRequest = new JsonObjectRequest
                (Request.Method.POST, url, body, new Response.Listener<JSONObject>() {

                    @Override
                    public void onResponse(JSONObject response) {
                        Log.v(TAG, "GOOGLE API RESPONSE SUCCESS: " + response.toString());
                    }
                }, new Response.ErrorListener() {

                    @Override
                    public void onErrorResponse(VolleyError error) {
                        Log.v(TAG, "GOOGLE API RESPONSE ERROR: " + error.toString());
                    }
                });

        // Add the request to the RequestQueue.
        queue.add(jsonObjectRequest);
    }

    private String bitmapToBase64string(Bitmap bitmap) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream .toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
    }




//    protected BatchAnnotateImagesResponse doInBackground(Object... params) {
//        try {
//            GoogleCredential credential = new GoogleCredential().setAccessToken(accessToken);
//            HttpTransport httpTransport = AndroidHttp.newCompatibleTransport();
//            JsonFactory jsonFactory = GsonFactory.getDefaultInstance();
//
//            Vision.Builder builder = new Vision.Builder
//                    (httpTransport, jsonFactory, credential);
//            Vision vision = builder.build();
//
//            List<Feature> featureList = new ArrayList<>();
//            Feature labelDetection = new Feature();
//            labelDetection.setType("LABEL_DETECTION");
//            labelDetection.setMaxResults(10);
//            featureList.add(labelDetection);
//            Feature textDetection = new Feature();
//            textDetection.setType("TEXT_DETECTION");
//            textDetection.setMaxResults(10);
//            featureList.add(textDetection);
//
//            List<AnnotateImageRequest> imageList = new ArrayList<>();
//            AnnotateImageRequest annotateImageRequest = new AnnotateImageRequest();
//            Image base64EncodedImage = getBase64EncodedJpeg(bitmap);
//            annotateImageRequest.setImage(base64EncodedImage);
//            annotateImageRequest.setFeatures(featureList);
//            imageList.add(annotateImageRequest);
//
//            BatchAnnotateImagesRequest batchAnnotateImagesRequest =
//            new BatchAnnotateImagesRequest();
//            batchAnnotateImagesRequest.setRequests(imageList);
//
//            Vision.Images.Annotate annotateRequest =
//            vision.images().annotate(batchAnnotateImagesRequest);
//            annotateRequest.setDisableGZipContent(true);
//            Log.d(TAG, "Sending request to Google Cloud");
//
//            BatchAnnotateImagesResponse response = annotateRequest.execute();
//            return response;
//
//        } catch (GoogleJsonResponseException e) {
//            Log.e(TAG, "Request error: " + e.getContent());
//        } catch (IOException e) {
//            Log.d(TAG, "Request error: " + e.getMessage());
//        }
//        return null;
//    }
    // www.Gaut.am was here
    // Thanks for reading!
}
