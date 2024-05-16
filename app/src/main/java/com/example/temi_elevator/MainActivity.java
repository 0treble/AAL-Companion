package com.example.temi_elevator;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import com.robotemi.sdk.*;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.constants.Page;
import com.robotemi.sdk.navigation.model.SpeedLevel;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.Collections;

public class MainActivity extends AppCompatActivity /*implements Robot.AsrListener*/{

    // Member variables
    private final String TAG = "MainActivity";
    private String destination = "";
    private int destinationFloor = -1; // is used to reset when temi on destination Floor arrives
    private String newDest = ""; // is used to save a destination from MQTT
    private static MainActivity instance;
    private final Robot temi = Robot.getInstance();
    private List<String> otherLocations = new ArrayList<>();
    private int myfloorNumber = 0;
    private boolean waitingForFloor = false;
    private boolean waitingForReset = false;
    private boolean waitingForFinish = false;
    private final Handler waitHandler = new Handler();
    private static String[] REQUIRED_PERMISSIONS = new String[]{android.Manifest.permission.RECORD_AUDIO};
    private TextView transcription;
    private ExecutorService myExecutorService;
    private SpeechRecognizer speechRecognizer;

    private enum TtsFollow {
        GERMAN(" bitte folgen Sie mir zu Raum: ", 11),
        ENGLISH(" please follow me to room: ", 1);

        private final String language;
        private final int number;

        TtsFollow(String language, int number) {
            this.language = language;
            this.number = number;
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
        setContentView(R.layout.activity_main);

        instance = this;
        transportMode();

        myExecutorService = Executors.newSingleThreadExecutor();

        init();
    }

    private void init() {
        transcriptMode();
        setupThemeButton();
    }

    private void setupThemeButton() {
        try {
            Button themeButton = findViewById(R.id.themeButton);
            if (themeButton != null) {
                themeButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        toggleDarkMode();
                    }
                });
            } else {
                Log.e(TAG, "themeButton is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up theme button: ", e);
        }
    }

    private void startRecording(Intent speechRecognizerIntent) {
        try {
            speechRecognizer.startListening(speechRecognizerIntent);
            transcription.setText("");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRecording() {
        speechRecognizer.stopListening();
        speechRecognizer.cancel();
    }

        /*
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
    }*/

    private void transcriptMode() {
        transcription = findViewById(R.id.transcription);
        transcription.setText("Ready... Press the Listen Button to start the transcription");

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        final Intent speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 100000);

        findViewById(R.id.listen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //transcription.setText("Ready... Temi should listen now");
                temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
                startRecording(speechRecognizerIntent);
            }
        });

        findViewById(R.id.endTranscription).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                transcription.setText("Transcription ended.");
                temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
                stopRecording();
            }
        });

        findViewById(R.id.MenuButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                enable_menu();
            }
        });

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle bundle) {
                transcription.setText("");
            }

            @Override
            public void onBeginningOfSpeech() {
                //state.setText("Listening...");
            }

            @Override
            public void onRmsChanged(float v) {}

            @Override
            public void onBufferReceived(byte[] bytes) {}

            @Override
            public void onEndOfSpeech() {
                //state.setText("Ready...");
            }

            @Override
            public void onError(int i) {}

            @Override
            public void onResults(Bundle results) {
                if (results != null) {
                    List<String> data = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (data != null && !data.isEmpty()) {
                        String result = data.get(0); // Get the first recognized result
                        result = result.trim();
                        result = result.substring(0, 1).toUpperCase() + result.substring(1);
                        if (!result.isEmpty()) {
                            transcription.append(result);
                            transcription.append("\n");
                        }
                    }
                }
            }

            @Override
            public void onPartialResults(Bundle bundle) {
                if (bundle != null) {
                    List<String> data = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (data != null && !data.isEmpty()) {
                        String result = data.get(0); // Get the first recognized result
                        result = result.trim();
                        result = result.substring(0, 1).toUpperCase() + result.substring(1);
                        if (!result.isEmpty()) {
                            transcription.append(result);
                            transcription.append("\n");
                        }
                    }
                }
            }

            @Override
            public void onEvent(int i, Bundle bundle) {}
        });
    }

    private void toggleDarkMode() {
        Log.i(TAG, "Toggled Dark Mode");

        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
            // Night mode is not active, activate it
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            // Night mode is active, deactivate it
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }

        recreate(); // Recreate activity to apply theme change
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
            }

        });

        // Add click listener for confirm button
        Button confirm_button = findViewById(R.id.confirmLocationButton);
        confirm_button.setOnClickListener(v -> confirm());

        // Add click listener for arrived button
        Button yes_button = findViewById(R.id.confirmArrivedButton);
        yes_button.setOnClickListener(v -> arrived());

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

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter a Number");
        final EditText input = new EditText(this);
        builder.setView(input);
        builder.setPositiveButton("Go to Home", (dialog, which) -> {
            String numberString = input.getText().toString();
            try {
                int number = Integer.parseInt(numberString);
                if (true) {
                    temi.startPage(Page.HOME);
                } else {
                    Log.i(TAG, "Wrong password entered");
                }
            } catch (NumberFormatException e) {
                Log.i(TAG, "not a valid number");
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        //builder.show();


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

    // is used to bring the guest to his destination when he's coming from a different floor
    public void setNewDest(String x) {
        newDest = x;
    }

    // tells the initial temi to reset and resets itself when the user arrives at the destination
    public void arrived() {
        if (!this.waitingForFinish) {
            temi.goTo(newDest);
            String msg = "{\"status\":\"arrived\",\"floor\":\"" + this.myfloorNumber + "\"}";
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
            Button confirm_button = findViewById(R.id.confirmLocationButton);
            Spinner spinner = findViewById(R.id.dropdownMenu);
            TextView textView = findViewById(R.id.topTextView);

            elevatorTextView.setVisibility(View.INVISIBLE);
            confirmTextView.setVisibility(View.INVISIBLE);
            confirmYesButton.setVisibility(View.INVISIBLE);
            confirmElevatorButton.setVisibility(View.INVISIBLE);
            confirm_button.setVisibility(View.VISIBLE);
            spinner.setVisibility(View.VISIBLE);
            textView.setVisibility(View.VISIBLE);
        });
    }

    public void setDestinationFloor(int destinationFloor) {
        this.destinationFloor = destinationFloor;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (myExecutorService != null) {
            myExecutorService.shutdown();
        } else {
            Log.w(TAG, "Attempted to shut down a null ExecutorService.");
        }
    }
}