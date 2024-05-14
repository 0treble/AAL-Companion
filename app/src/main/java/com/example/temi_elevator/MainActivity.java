package com.example.temi_elevator;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.os.Bundle;
import android.util.Size;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.temi_elevator.databinding.CameraMainBinding;
import com.example.temi_elevator.mqtt.MQTT;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.robotemi.sdk.*;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.constants.Page;
import com.robotemi.sdk.navigation.model.SpeedLevel;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import java.util.Collections;


public class MainActivity extends AppCompatActivity implements Robot.AsrListener{

    // Member variables
    private final String TAG = "MainActivity";
    private String destination = "";
    private int destinationFloor = -1; // is used to reset when temi on destination Floor arrives
    private String newDest = ""; // is used to save a destination from MQTT
    private static MainActivity instance;
    private final Robot temi = Robot.getInstance();
    private List<String> otherLocations = new ArrayList<>();
    private final MQTT myMQTT = MQTT.getInstance(this);
    private int myfloorNumber = 0;
    private boolean waitingForFloor = false;
    private boolean waitingForReset = false;
    private boolean waitingForFinish = false;
    private final Handler waitHandler = new Handler();
    private CameraMainBinding viewBinding;
    private ExecutorService cameraExecutor;
    ImageAnalysis imageAnalysis;
    private static String[] REQUIRED_PERMISSIONS = new String[]{android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO};
    private boolean isCheckingForQrCode = false;
    private String displayName = "";
    private String ttsWelcome = TtsWelcome.getLanguageByNumber(1); // 1 = GERMAN as default language
    private String ttsFollow = TtsFollow.getLanguageByNumber(1);
    private int ttsLanguage = 0;
    private TextView transcription;
    //test

    private enum TtsWelcome {
        // using languages already implemented in TemiSDK
        GERMAN("Willkommen ", 11),
        ENGLISH("Welcome ", 1),
        FRENCH("Bienvenue ", 12),
        SPANISH("Bienvenido ", 21),
        ITALIAN("Benvenuto ", 19),
        PORTUGUESE("Bem-vindo ", 14),
        RUSSIAN("Добро пожаловать ", 18),
        CHINESE("欢迎 ", 2),
        JAPANESE("ようこそ ", 8),
        THAI("ยินดีต้อนรับ ", 5),
        HEBREW("ברוך הבא ", 6),
        KOREAN("환영합니다 ", 7),
        INDONESIAN("Selamat Datang ", 10),
        ESTONIAN("Tere tulemast ", 24),
        CATALAN("Benvingut ", 22),
        HINDI("स्वागत है ", 23);

        private final String language;
        private final int number;

        TtsWelcome(String language, int number) {
            this.language = language;
            this.number = number;
        }

        public static String getLanguageByNumber(int number) {
            for (TtsWelcome ttsWelcome : values()) {
                if (ttsWelcome.number == number) {
                    return ttsWelcome.language;
                }
            }
            return null; // Return null if the number doesn't match any language
        }
    }

    private enum TtsFollow {
        GERMAN(" bitte folgen Sie mir zu Raum: ", 11),
        ENGLISH(" please follow me to room: ", 1),
        FRENCH(" veuillez me suivre jusqu'à la salle : ", 12),
        SPANISH(" por favor sígame a la sala: ", 21),
        ITALIAN(" per favore seguimi in sala: ", 19),
        PORTUGUESE(" por favor, siga-me até a sala: ", 14),
        RUSSIAN(" пожалуйста, следуйте за мной в комнату: ", 18),
        CHINESE(" 请跟我去房间：", 2),
        JAPANESE(" 部屋までついてきてください：", 8),
        THAI(" โปรดตามฉันไปยังห้อง: ", 5),
        HEBREW(" בבקשה עקוב אחרי בי לחדר: ", 6),
        KOREAN(" 부디 나를 따라와 방으로 가십시오: ", 7),
        INDONESIAN(" silakan ikuti saya ke ruangan: ", 10),
        ESTONIAN(" palun järgige mind tuppa: ", 24),
        CATALAN(" si us plau segueix-me a la sala: ", 22),
        HINDI(" कृपया मुझे कक्षा में अनुसरण करें: ", 23);

        private final String language;
        private final int number;

        TtsFollow(String language, int number) {
            this.language = language;
            this.number = number;
        }

        public static String getLanguageByNumber(int number) {
            for (TtsFollow ttsFollow : values()) {
                if (ttsFollow.number == number) {
                    return ttsFollow.language;
                }
            }
            return null; // Return null if the number doesn't match any language
        }
    }

    static {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            REQUIRED_PERMISSIONS = Arrays.copyOf(REQUIRED_PERMISSIONS, REQUIRED_PERMISSIONS.length + 1);
            REQUIRED_PERMISSIONS[REQUIRED_PERMISSIONS.length - 1] = Manifest.permission.WRITE_EXTERNAL_STORAGE;
        }
    }

    /**************************************************************************************
     * ************************************************************************************
     * ************************************************************************************
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        instance = this;
        myMQTT.connect();
        transportMode();
        transcriptMode();
        setContentView(R.layout.activity_main);
        transcription = findViewById(R.id.transcription);

        findViewById(R.id.listen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                transcription.setText("Ready... Temi should listen now");
                temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
            }
        });
    }
    private String myAsrResultString = "nixxx";     // here we want the transcribed string to be in
    @Override
    public void onAsrResult(String asrResult, SttLanguage sttLanguage) {
        myAsrResultString = asrResult;
        transcription.setText(myAsrResultString);
    }

    private void transcriptMode() {
        Log.i(TAG, "Entered transcriptMode");
        setContentView(R.layout.activity_main);

        transcription = findViewById(R.id.transcription);

        Button listen = findViewById(R.id.confirmLocationButton);
        listen.setOnClickListener(v -> temiWakeUp());
    }

    private void toggleDarkMode() {
        Log.i(TAG, "Toggled Dark Mode");

        Button themeButton = findViewById(R.id.themeButton);
        themeButton.setOnClickListener(v -> enable_menu());

        int currentNightMode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
            // Night mode is not active, activate it
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            // Night mode is active, deactivate it
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
        //recreate(); // Recreate activity to apply theme change
    }

    private void transportMode() {
        Log.i(TAG, "Entered transportMode");
        setContentView(R.layout.activity_main);

        // Add listener to check when the robot is ready
        temi.addOnRobotReadyListener(isReady -> {
            if (isReady) {
                refreshTemiUi();
                fillDropdownMenu();
                temi.setHardButtonsDisabled(true);
                temi.setGoToSpeed(SpeedLevel.SLOW);
                temi.hideTopBar();
            }
        });

        // Add click listener for confirm button
        Button confirm_button = findViewById(R.id.confirmLocationButton);
        confirm_button.setOnClickListener(v -> confirm());

        // Add click listener for arrived button
        Button yes_button = findViewById(R.id.confirmArrivedButton);
        yes_button.setOnClickListener(v -> arrived());

        Button qr_code_button = findViewById(R.id.QRCodeButton);
        qr_code_button.setOnClickListener(v -> QRCodeMode());

        Button menu_button = findViewById(R.id.MenuButton);
        menu_button.setOnClickListener(v -> enable_menu());

        // Add listener for dropdown menu
        Spinner spinner = findViewById(R.id.dropdownMenu);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                destination = parent.getItemAtPosition(position).toString();
                Log.i(TAG, "Destination = " + destination);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                Log.e(TAG, "Nothing is selected");
            }
        });

    }

    private void enable_menu() {
        temi.startPage(Page.HOME);
        /*
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter a Number");
        final EditText input = new EditText(this);
        builder.setView(input);
        builder.setPositiveButton("Go to Home", (dialog, which) -> {
            String numberString = input.getText().toString();
            try {
                int number = Integer.parseInt(numberString);
                if (number == 4115) {
                    temi.startPage(Page.HOME);
                } else {
                    Log.i(TAG, "Wrong password entered");
                }
            } catch (NumberFormatException e) {
                Log.i(TAG, "not a valid number");
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
        */

    }

    private void QRCodeMode() {
        Log.i(TAG, "Entered QRCodeMode");
        this.isCheckingForQrCode = true;
        viewBinding = CameraMainBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }

        viewBinding.imageCaptureButton.setOnClickListener(v -> transportMode());

        cameraExecutor = Executors.newSingleThreadExecutor();

    }

    private void fillDropdownMenu() {
        List<String> locationList = temi.getLocations();
        locationList.addAll(otherLocations);

        // Remove duplicates
        Set<String> set = new HashSet<>(locationList);
        locationList = new ArrayList<>(set);

        String floorx = "";

        // Find the current floor and remove it from the list
        for (String element : locationList) {
            if (element.startsWith("floor")) {
                this.myfloorNumber = Integer.parseInt(element.substring(5));
                floorx = element;
            }
        }
        // removing the floorNumber indicator
        locationList.remove(floorx);

        Log.i(TAG, "Temi befindet sich auf floor: " + this.myfloorNumber);

        // Set adapter for dropdown menu
        String[] locations = locationList.toArray(new String[0]);
        Spinner dropdownMenu = findViewById(R.id.dropdownMenu);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.dropdown_menu_text_view, locations);
        adapter.setDropDownViewResource(R.layout.dropdown_menu_pick_text_view);
        dropdownMenu.setAdapter(adapter);
    }

    private void refreshTemiUi() {
        try {
            ActivityInfo activityInfo = getPackageManager()
                    .getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
            Robot.getInstance().onStart(activityInfo);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    public void hideModeElements() {
        runOnUiThread(() -> {
            Button qr_code_button = findViewById(R.id.QRCodeButton);
            Button confirm_button = findViewById(R.id.confirmLocationButton);
            Spinner spinner = findViewById(R.id.dropdownMenu);
            TextView confirmtextView = findViewById(R.id.topTextView);

            confirm_button.setVisibility(View.INVISIBLE);
            spinner.setVisibility(View.INVISIBLE);
            confirmtextView.setVisibility(View.INVISIBLE);
            qr_code_button.setVisibility(View.INVISIBLE);
        });
    }

    @SuppressLint("SetTextI18n")
    public void receivedFloor(int number) {
        runOnUiThread(() -> {
            TextView textView = findViewById(R.id.elevatorTextView);
            textView.setText("Der Raum befindet sich auf dem Stockwerk: " + number);
            this.waitingForFloor = false;
            this.waitingForReset = true;

            // If nothing happens after 1 min go home anyways
            waitHandler.postDelayed(() -> {
                if (this.waitingForFinish) {
                    Log.w(TAG, "no button was pressed");
                    reset();
                }
            }, 60000);
        });
    }

    // confirming the destination choice
    @SuppressLint("SetTextI18n")
    private void confirm() {
        hideModeElements();
        List<String> locationList = temi.getLocations();
        if (locationList.contains(destination)) {
            temi.goTo(destination);
            this.showArrivedMessage();

            waitHandler.postDelayed(() -> {
                if (this.waitingForFinish) {
                    reset();
                }
            }, 60000);

            this.waitingForFinish = true;
        } else {
            temi.goTo("aufzug");
            myMQTT.publish("{\"destination\":\"" + destination + "\"}", MQTT.PUBLISH_TOPIC.TOPIC_TRANSPORT.getTopic());

            this.waitingForFloor = true;
            TextView textView = findViewById(R.id.elevatorTextView);
            textView.setVisibility(View.VISIBLE);
            textView.setText("Warten auf Stockwerk...");

            // If no floor is received reset after 60 seconds
            waitHandler.postDelayed(() -> {
                if (this.waitingForFloor) {
                    Log.w(TAG, "No Floor was received");
                    reset();
                }
            }, 60000);
        }
    }

    // received the floor number to display on the message
    @SuppressLint("SetTextI18n")
    private void temiWakeUp() {
        transcription.setText("Ready... Temi should listen now");
        temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
    }

    // is used to bring the guest to his destination when he's coming from a different floor
    public void setNewDest(String x) {
        newDest = x;
    }

    // tells the initial temi to reset and resets itself when the user arrives at the destination
    public void arrived() {
        if (!this.waitingForFinish) {
            temi.goTo(newDest);
            String msg = "{\"status\":\"arrived\",\"floor\":\"" + this.myfloorNumber + "\"}";
            myMQTT.publish(msg, MQTT.PUBLISH_TOPIC.TOPIC_ARRIVED.getTopic());
            this.waitingForFinish = true;

            this.showArrivedMessage();

            waitHandler.postDelayed(() -> {
                if (this.waitingForFinish) {
                    Log.w(TAG, "The button was never pressed");
                    reset();
                }
            }, 180000);
        } else {
            this.reset();
        }
    }

    public void showArrivedMessage() {
        runOnUiThread(() -> {
            TextView confirmTextView = findViewById(R.id.confirmTextView);
            Button confirmYesButton = findViewById(R.id.confirmArrivedButton);

            confirmTextView.setVisibility(View.VISIBLE);
            String arrivedMSG = "Sind sie am richtigen Raum angekommen?";
            confirmTextView.setText(arrivedMSG);
            confirmYesButton.setVisibility(View.VISIBLE);
        });
    }

    // makes the confirm message and confirm button visible
    @SuppressLint("SetTextI18n")
    public void showContent(String room) {
        waitingForReset = true;
        runOnUiThread(() -> {
            TextView confirmTextView = findViewById(R.id.confirmTextView);
            Button confirmYesButton = findViewById(R.id.confirmArrivedButton);

            confirmTextView.setVisibility(View.VISIBLE);
            confirmTextView.setText("Bitte bestätigen, wenn Sie zu " + room + " wollen");
            confirmYesButton.setVisibility(View.VISIBLE);
        });
    }

    public void addLocations(String[] locations) {
        otherLocations.addAll(Arrays.asList(locations));

        // Remove duplicates
        Set<String> set = new HashSet<>(otherLocations);
        otherLocations = new ArrayList<>(set);

        String floorx = "";

        for (String element : otherLocations) {
            if (element.startsWith("floor")) {
                floorx = element;
            }
        }

        otherLocations.remove(floorx);

        runOnUiThread(this::fillDropdownMenu);
    }

    public static MainActivity getInstance() {
        return instance;
    }

    public Robot getTemi() {
        return temi;
    }

    public int getMyfloorNumber() {
        return this.myfloorNumber;
    }

    public int getDestinationFloor() {
        return this.destinationFloor;
    }

    public boolean isWaitingForFloor() {
        return waitingForFloor;
    }

    public boolean isWaitingForReset() {
        return waitingForReset;
    }

    public void reset() {
        temi.goTo("home base");
        this.destinationFloor = -1;
        this.waitingForFloor = false;
        this.waitingForFinish = false;
        this.waitingForReset = false;
        this.newDest = "";
        runOnUiThread(() -> {
            TextView confirmTextView = findViewById(R.id.confirmTextView);
            TextView elevatorTextView = findViewById(R.id.elevatorTextView);
            Button confirmYesButton = findViewById(R.id.confirmArrivedButton);
            Button confirmElevatorButton = findViewById(R.id.confirmFinishButton);
            Button qrCodeButton = findViewById(R.id.QRCodeButton);
            Button confirm_button = findViewById(R.id.confirmLocationButton);
            Spinner spinner = findViewById(R.id.dropdownMenu);
            TextView textView = findViewById(R.id.topTextView);

            elevatorTextView.setVisibility(View.INVISIBLE);
            confirmTextView.setVisibility(View.INVISIBLE);
            confirmYesButton.setVisibility(View.INVISIBLE);
            confirmElevatorButton.setVisibility(View.INVISIBLE);
            qrCodeButton.setVisibility(View.VISIBLE);
            confirm_button.setVisibility(View.VISIBLE);
            spinner.setVisibility(View.VISIBLE);
            textView.setVisibility(View.VISIBLE);
        });
    }

    public void setDestinationFloor(int destinationFloor) {
        this.destinationFloor = destinationFloor;
    }

    private final ActivityResultLauncher<String[]> activityResultLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
        // Handle Permission granted/rejected
        boolean permissionGranted = true;
        for (String permission : REQUIRED_PERMISSIONS) {
            if (permissions.containsKey(permission) && Boolean.FALSE.equals(permissions.get(permission))) {
                permissionGranted = false;
                break;
            }
        }
        if (!permissionGranted) {
            Toast.makeText(getApplicationContext(), "Permission request denied", Toast.LENGTH_SHORT).show();
        } else {
            startCamera();
        }
    });

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void hasQRCodeFromPreview(ImageProxy imageProxy) {
        // Convert ImageProxy to byte array
        Image image = imageProxy.getImage();
        assert image != null;
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] imageData = new byte[buffer.remaining()];
        buffer.get(imageData);

        // Check if QR code is present
        if (checkForQRCode(imageData)) {
            Log.d(TAG, "Destination from Qr Code:" + destination);
            this.isCheckingForQrCode = false;
            runOnUiThread(this::display_name);
        }

        // Close the imageProxy
        imageProxy.close();
    }

    public boolean checkForQRCode(byte[] imageData) {
        int width = 640; // only hardcoded because of Log spam by some Library
        int height = 480;
        int[] pixels = new int[width * height];

        // Convert the byte image data to int[]
        for (int i = 0; i < imageData.length; i++) {
            int value = imageData[i] & 0xFF; // Convert byte value to unsigned int value
            pixels[i] = 0xFF000000 | (value << 16) | (value << 8) | value; // Convert grayscale value to ARGB int value
        }

        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);


        // Create a BinaryBitmap from the RGBLuminanceSource
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

        // Create a QRCodeReader to decode the QR code
        Reader reader = new QRCodeReader();

        try {
            if (this.isCheckingForQrCode) {
                // Try to decode the QR code
                Result result = reader.decode(bitmap);
                JSONObject msg = new JSONObject(result.getText());
                Log.i(TAG, "Detected QR Code: " + msg);
                destination = msg.getString("destination");
                displayName = msg.getString("name");
                ttsWelcome = TtsWelcome.getLanguageByNumber(msg.getInt("language"));
                Log.i(TAG, "Language set to: " + ttsWelcome);
                ttsFollow = TtsFollow.getLanguageByNumber(msg.getInt("language"));
                ttsLanguage = msg.getInt("language");

                // If a QR code is found, return true
                return true;
            } else {
                return false;
            }

        } catch (NotFoundException | ChecksumException | FormatException e) {
            // QR code not found or decoding error occurred
            return false;
        } catch (JSONException e) {
            Log.e(TAG, "QR Code is not valid!");
            return false;
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                // Used to bind the lifecycle of cameras to the lifecycle owner
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Preview
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewBinding.viewFinder.getSurfaceProvider());


                // Select back camera as a default
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                imageAnalysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(new Size(640, 480)).build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::hasQRCodeFromPreview);

                try {
                    // Unbind use cases before rebinding
                    cameraProvider.unbindAll();

                    // Bind use cases to camera
                    cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                } catch (Exception exc) {
                    Log.e(TAG, "Use case binding failed", exc);
                }
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void requestPermissions() {
        String[] permissionsToRequest = getPermissionsToRequest();
        if (permissionsToRequest.length > 0) {
            activityResultLauncher.launch(permissionsToRequest);
        }
    }

    private String[] getPermissionsToRequest() {
        List<String> permissions = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(permission);
            }
        }
        return permissions.toArray(new String[0]);
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    private void display_name() {
        String msg = ttsWelcome + displayName + ttsFollow + destination;
        temi.speak(TtsRequest.create(msg, true, getLanguageFromValue(ttsLanguage)));
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        tts_ready();
    }

    private void tts_ready() {
        transportMode();
        confirm();
    }

    public TtsRequest.Language getLanguageFromValue(int value) {
        for (TtsRequest.Language language : TtsRequest.Language.values()) {
            if (language.getValue() == value) {
                return language;
            }
        }
        // Return a default language or handle the case when no matching language is found
        return TtsRequest.Language.SYSTEM; // You can change this to another default if needed
    }
}