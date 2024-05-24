package com.example.temi_elevator;

import android.Manifest;
import android.annotation.SuppressLint;
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
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.navigation.model.SpeedLevel;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import android.text.method.ScrollingMovementMethod;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;

import com.robotemi.sdk.listeners.OnConversationStatusChangedListener;
import com.robotemi.sdk.listeners.OnRobotReadyListener;

import org.jetbrains.annotations.NotNull;

public class MainActivity extends AppCompatActivity implements
        OnRobotReadyListener,
        Robot.AsrListener,
        Robot.TtsListener,
        OnConversationStatusChangedListener,
        OnGoToLocationStatusChangedListener {

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
    private String myAsrResultString = "";
    enum sequence {GREETING,SMALL_TALK};
    sequence currentSequence = sequence.GREETING;
    int currentSequenceStep = 1;
    boolean flagWaitingForTemiToArrive = false;
    boolean flagWaitingForTemiToFinishSpeaking = false;
    boolean conversationMode = false;



    @Override
    protected void onStart() {
        super.onStart();

        // Add robot event listeners
        temi.addOnRobotReadyListener(this);
        temi.addAsrListener(this);
        temi.addOnConversationStatusChangedListener(this);

        temi.addOnGoToLocationStatusChangedListener(this);
    }
    @Override
    protected void onStop() {
        super.onStop();

        // Remove robot event listeners
        temi.removeOnRobotReadyListener(this);
        temi.removeAsrListener(this);
        temi.removeOnConversationStatusChangedListener(this);
    }
    @Override
    public void onAsrResult(@NotNull String asrResult, @NonNull SttLanguage sttLanguage) {

        Log.i(TAG, "ASR Result: " + asrResult);
        myAsrResultString = asrResult;
        transcription.append("myAsrResultString: " + myAsrResultString + "\n");


        temi.speak(TtsRequest.create(myAsrResultString, false));

        analyzeVoiceCommand();

        if(!conversationMode){
            temi.finishConversation(); // stop ASR listener
        }
    }

    /* Voice Command Recognition */
    @FunctionalInterface
    interface CommandAction {
        void execute(String command);
    }

    private final Map<String[], CommandAction> commandsMap = new HashMap<>();
    private void initCommandsMap() {
        commandsMap.put(new String[]{"gehe", "geh", "fahre", "fahr"}, command -> relocateTemi());
        commandsMap.put(new String[]{"sequenz starten", "sequenz beginnen"}, command -> findViewById(R.id.sequence1Button).performClick());
        commandsMap.put(new String[]{"transkription starten", "aufnahme beginnen", "aufnahme starten"}, command -> findViewById(R.id.listen).performClick());
        commandsMap.put(new String[]{"transkription beenden", "aufnahme beenden"}, command -> findViewById(R.id.endTranscription).performClick());
        commandsMap.put(new String[]{"applikation beenden", "app beenden"}, command -> findViewById(R.id.quitButton).performClick());
        commandsMap.put(new String[]{"dunkler modus"}, command -> findViewById(R.id.themeButton).performClick());
        commandsMap.put(new String[]{"gesprächsmodus", "dialogmodus", "gesprächs modus"}, command -> conversationMode = !conversationMode);
    }

    public void analyzeVoiceCommand() {

        myAsrResultString = myAsrResultString.toLowerCase();

        for (Map.Entry<String[], CommandAction> entry : commandsMap.entrySet()) {
            for (String command : entry.getKey()) {
                if (myAsrResultString.contains(command)) {
                    entry.getValue().execute(myAsrResultString);
                    return;
                }
            }
        }

        /*myAsrResultString = myAsrResultString.toLowerCase();

        if(     myAsrResultString.contains("gehe")
                ||    myAsrResultString.contains("geh")
                ||    myAsrResultString.contains("fahre")
                ||    myAsrResultString.contains("fahr"))
        {
            relocateTemi();
        }else if((myAsrResultString.contains("sequenz starten")
                || myAsrResultString.contains("sequenz beginnen"))){
            findViewById(R.id.sequence1Button).performClick();

        }else if((myAsrResultString.contains("transkription starten")
                || myAsrResultString.contains("aufnahme beginnen"))){
            findViewById(R.id.listen).performClick();

        }else if((myAsrResultString.contains("transkription beenden")
                || myAsrResultString.contains("aufnahme beenden"))){
            findViewById(R.id.endTranscription).performClick();

        }else if((myAsrResultString.contains("applikation beenden")
                || myAsrResultString.contains("app beenden"))){
            findViewById(R.id.quitButton).performClick();

        }else if(myAsrResultString.contains("dunkler modus")){
            findViewById(R.id.themeButton).performClick();

        }else if(myAsrResultString.contains("gesprächmodus")){
            conversationMode = !conversationMode;
        }else {
            chooseCurrentSequence();
        }*/

    }

    public void relocateTemi()
    {
        if(myAsrResultString.contains("tür"))
        {
            destination = "tür";
            confirm();
        }
        else if(myAsrResultString.contains("basisstation"))
        {
            destination = "home base";
            confirm();
        }
        else if(myAsrResultString.contains("wohnzimmer"))
        {
            destination = "wohnzimmer";
            confirm();
        }
        else if(myAsrResultString.contains("küche"))
        {
            destination = "küche";
            confirm();
        }
        else
        {   // default
            temi.speak(TtsRequest.create("Entschuldige diesen Ort kenne ich leider nicht.", false));
        }
    }

    public void chooseCurrentSequence()
    {
        switch(currentSequence)
        {
            case GREETING:
                handleSequenceGreeting();
                break;
            case SMALL_TALK:

                break;
            default:
                transcription.append("Error default in switch(currentseqwuence)");
                break;
        }
    }

    public void handleSequenceGreeting()
    {
        switch (currentSequenceStep)
        {
            case 1:
                flagWaitingForTemiToArrive = true;      /// SUPER IMPORTANT BEFORE EVERY GO-TO-COMMAND!!!!
                temi.goTo("tür");               /// so that the next task is started AFTER arriving at destination
                // step increment done by the onGoToStatusListener()
                break;
            case 2:
                String greeting_string = getString(R.string.greeting_string);
                transcription.setText(greeting_string);
                flagWaitingForTemiToFinishSpeaking = true;  /// SUPER IMPORTANT BEFORE EVERY speaking-COMMAND!!!!
                                                            /// so that the next task is started AFTER arriving at destination
                temi.speak(TtsRequest.create(greeting_string, false));
                // step increment done by the onTtsStatusChanged() when temi finished speaking previous string
                break;
            case 3:
                waitHandler.postDelayed(() -> {
                }, 3000);
                flagWaitingForTemiToArrive = true;
                temi.goTo("wohnzimmer");
                break;
            case 4:

                String livingroom_string = getString(R.string.livingroom_string);
                transcription.setText(livingroom_string);
                waitHandler.postDelayed(() -> {
                }, 5000);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(livingroom_string, false));
                // step increment done by the onTtsStatusChanged() when temi finished speaking previous string
                break;
            case 5:
                waitHandler.postDelayed(() -> {
                }, 3000);
                String introduction_string = getString(R.string.introduction_string);
                transcription.setText(introduction_string);
                temi.speak(TtsRequest.create(introduction_string, false));
                waitHandler.postDelayed(() -> {
                }, 3000);
                // step increment done by the onTtsStatusChanged() when temi finished speaking previous string
                break;
            case 6: // End of sequence GREETING
                currentSequenceStep = 0;
                break;
            default:
                transcription.append("Error: Default in Sequenz GREETING!");
                break;
        }
    }

    @Override
    public void onGoToLocationStatusChanged(@NonNull String location, @NonNull String status, int descriptionId, @NonNull String description) {
        if(status.equals(COMPLETE) && flagWaitingForTemiToArrive)
        {
            flagWaitingForTemiToArrive = false;
            currentSequenceStep += 1;
            chooseCurrentSequence();
        }
        else if(flagWaitingForTemiToArrive)
        {
            //flagTemiOnTheMove = true;
        }
    }

    @Override
    public void onConversationStatusChanged(int status, @NotNull String text) {
        myAsrResultString += text;
        switch (status) {
            case IDLE:
                Log.i(TAG, "Status: IDLE | Text: " + myAsrResultString);
                transcription.append("Status: IDLE | Text: " + myAsrResultString + "\n");
                findViewById(R.id.isRecordingBar).setVisibility(View.INVISIBLE);
                break;
            case LISTENING:
                Log.i(TAG, "Status: LISTENING | Text: " + myAsrResultString);
                transcription.append("Status: LISTENING | Text: " + myAsrResultString + "\n");
                findViewById(R.id.isRecordingBar).setVisibility(View.VISIBLE);
                break;
            case THINKING:
                Log.i(TAG, "Status: THINKING | Text: " + myAsrResultString);
                transcription.append("Status: THINKING | Text: " + myAsrResultString + "\n");
                break;
            case SPEAKING:
                Log.i(TAG, "Status: SPEAKING | Text: " + myAsrResultString);
                transcription.append("Status: SPEAKING | Text: " + myAsrResultString + "\n");
                break;
            default:
                Log.i(TAG, "Status: UNKNOWN | Text: " + myAsrResultString);
                transcription.append("Status: UNKNOWN | Text: " + myAsrResultString + "\n");
                findViewById(R.id.isRecordingBar).setVisibility(View.INVISIBLE);
                break;
        }
    }

    @Override
    public void onRobotReady(boolean b) {
        if (temi.isReady()) {                                     // true instead of 'isReady'????
            Log.i(TAG, "Robot is ready");
            temi.hideTopBar(); // hide temi's top action bar when skill is active
        }
    }

    @Override
    public void onTtsStatusChanged(@NonNull TtsRequest ttsRequest) {

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
        temi.setKioskModeOn(true);

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
        initCommandsMap();

        findViewById(R.id.isRecordingBar).setVisibility(View.INVISIBLE);

        findViewById(R.id.quitButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                temi.setKioskModeOn(false);
                temi.setInteractionState(true);
                enable_menu();

            }
        });

        findViewById(R.id.listen).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
                findViewById(R.id.isRecordingBar).setVisibility(View.VISIBLE);
            }
        });

        findViewById(R.id.endTranscription).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                transcription.append("Transcription ended." + "\n");
                temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
                findViewById(R.id.isRecordingBar).setVisibility(View.INVISIBLE);
            }
        });

        findViewById(R.id.quitButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                temi.setKioskModeOn(false);
                enable_menu();
            }
        });

        findViewById(R.id.sequence1Button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                handleSequenceGreeting();
            }
        });

        temi.addTtsListener(new Robot.TtsListener() {
            @Override
            public void onTtsStatusChanged(@NotNull TtsRequest ttsRequest) {
                if(flagWaitingForTemiToFinishSpeaking && ttsRequest.getStatus() == TtsRequest.Status.COMPLETED)
                {
                    flagWaitingForTemiToFinishSpeaking = false;
                    currentSequenceStep += 1;
                    chooseCurrentSequence();
                }
            }
        });
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


    private void transcriptMode() {
        transcription = findViewById(R.id.transcription);
        transcription.setMovementMethod(new ScrollingMovementMethod());
        transcription.append("Ready... Press the Listen Button to start the transcription" + "\n");

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

        Button menu_button = findViewById(R.id.quitButton);
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
            try {
                temi.startPage(Page.HOME);
            } catch (NumberFormatException e) {
                Log.i(TAG, "not a valid number");
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
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
        }catch (Exception e) {
            Log.e(TAG, "Error refreshing temi UI: ", e);
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
            //String msg = "{\"status\":\"arrived\",\"floor\":\"" + this.myfloorNumber + "\"}";
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
            findViewById(R.id.isRecordingBar).setVisibility(View.INVISIBLE);

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

        instance = null;

        if (myExecutorService != null) {
            myExecutorService.shutdown();
        } else {
            Log.w(TAG, "Attempted to shut down a null ExecutorService.");
        }
    }
}