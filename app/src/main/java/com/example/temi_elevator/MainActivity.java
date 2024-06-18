package com.example.temi_elevator;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.bumptech.glide.Glide;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.SttLanguage;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.UserInfo;
import com.robotemi.sdk.constants.Page;
import com.robotemi.sdk.constants.Platform;
import com.robotemi.sdk.listeners.OnConversationStatusChangedListener;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.listeners.OnRobotReadyListener;
import com.robotemi.sdk.listeners.OnTelepresenceEventChangedListener;
import com.robotemi.sdk.listeners.OnTelepresenceStatusChangedListener;
import com.robotemi.sdk.model.CallEventModel;
import com.robotemi.sdk.navigation.model.SpeedLevel;
import com.robotemi.sdk.telepresence.CallState;
import com.robotemi.sdk.telepresence.Participant;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements
        OnRobotReadyListener,
        Robot.AsrListener,
        Robot.TtsListener,
        OnConversationStatusChangedListener,
        OnGoToLocationStatusChangedListener,
        OnTelepresenceEventChangedListener {

    // Member variables
    private final String TAG = "MainActivity";
    private UserInfo contact;
    private int destinationFloor = -1; // is used to reset when temi on destination Floor arrives
    private String newDest = ""; // is used to save a destination from MQTT
    @SuppressLint("StaticFieldLeak")
    private static MainActivity instance;
    private final Robot temi = Robot.getInstance();
    private final List<UserInfo> validContacts = new ArrayList<>();
    private boolean waitingForFloor = false;
    private boolean waitingForReset = false;
    private boolean waitingForFinish = false;
    private final Handler waitHandler = new Handler();
    private TextView transcription;
    private ScrollView scrollView;
    private ActivityResultLauncher<Intent> sequenceResultLauncher;
    private ExecutorService myExecutorService;
    private String myAsrResultString = "";
    enum Sequence {GREETING, SEQUENCE_ALEXA, AAL_SEQUENCE, SEQUENCE_BRAIN_GAME, QUESTIONNAIRE, KITCHEN}
    private Sequence currentSequence;
    int currentSequenceStep = 0;
    private boolean flagWaitingForTemiToArrive = false;
    private boolean flagWaitingForTemiToFinishSpeaking = false;
    private boolean flagWaitingForUserResponse = false; // = true when we are expecting an answer from the user
    private boolean flagRepeatSentenceRequest = false; // = true when user wants sentenced repeated (handleSequenceBrainGame)
    boolean conversationMode = false;
    private Reminder.ReminderManager reminderManager;
    private File logFile;

    @Override
    protected void onStart() {
        super.onStart();

        // Add robot event listeners
        temi.addOnRobotReadyListener(this);
        temi.addAsrListener(this);
        temi.addOnConversationStatusChangedListener(this);
        temi.addTtsListener(this);
        temi.addOnGoToLocationStatusChangedListener(this);
        temi.addOnTelepresenceStatusChangedListener(telepresenceStatusChangedListener);
        temi.addOnTelepresenceEventChangedListener(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        temi.requestToBeKioskApp();

        if (!temi.isSelectedKioskApp()){
            temi.setKioskModeOn(true);
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        instance = this;
        setupVideoCall();

        myExecutorService = Executors.newSingleThreadExecutor();

        init(savedInstanceState);

        createLogFile();
    }

    private void init(Bundle savedInstanceState) {
        startTranscription();
        setupThemeButton();
        initCommandsMap();

        scrollView = findViewById(R.id.scrollView);

        findViewById(R.id.isRecordingImg).setVisibility(View.INVISIBLE);

        if (savedInstanceState != null) {
            String transcript = savedInstanceState.getString("transcript");
            transcription.setText(transcript);
        }

        // Initialize the reminder manager
        reminderManager = new Reminder.ReminderManager(this);

        findViewById(R.id.listen).setOnClickListener(view -> {
            temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
            findViewById(R.id.isRecordingImg).setVisibility(View.VISIBLE);
        });

        findViewById(R.id.endTranscription).setOnClickListener(view -> {
            showTranscription("Transkription beended.");
            findViewById(R.id.isRecordingImg).setVisibility(View.INVISIBLE);
        });

        findViewById(R.id.quitButton).setOnClickListener(view -> {
            logToFile(transcription.getText().toString());
            if (temi.isSelectedKioskApp()){
                temi.setKioskModeOn(false);
            }
            temi.setGoToSpeed(SpeedLevel.SLOW);
            enable_menu();
        });



        /* Sequence Window Launcher*/
        findViewById(R.id.sequenceWindowButton).setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, SequenceActivity.class);
            sequenceResultLauncher.launch(intent);
        });

        sequenceResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String sequenceTypeName = result.getData().getStringExtra("SEQUENCE_TYPE");
                        if (sequenceTypeName != null) {
                            currentSequence = Sequence.valueOf(sequenceTypeName);
                            currentSequenceStep = 0;
                            chooseCurrentSequence();
                        }
                    }
                }
        );

        /* Robot Face Display */
        findViewById(R.id.imgOverlayButton).setOnClickListener(view ->  {
            Glide.with(this).asGif().load(R.drawable.smileblink_crop).into((android.widget.ImageView) findViewById(R.id.overlay_image));

            showFace();
        });

        findViewById(R.id.img_close_button).setOnClickListener(view ->  {
            findViewById(R.id.overlay_image).setVisibility(View.GONE);
            findViewById(R.id.img_close_button).setVisibility(View.GONE);
        });

        /* Repeat Button */
        findViewById(R.id.repeatButton).setOnClickListener(view ->  {
            if(currentSequence == null){
                showTranscription("Keine Sequenz ausgewählt");
            }else if(currentSequence == Sequence.SEQUENCE_BRAIN_GAME){
                currentSequenceStep -= 2;
                chooseCurrentSequence();
            }else{
                currentSequenceStep--;
                chooseCurrentSequence();
            }

            //for error avoidance
            if(currentSequenceStep < 0){
                currentSequenceStep = 0;
            }
        });
        /* Call Button */
        findViewById(R.id.confirmCallButton).setOnClickListener(view -> startVideoMeeting(contact));
    }

    private void showFace()
    {
        findViewById(R.id.overlay_image).setVisibility(View.VISIBLE);
        findViewById(R.id.img_close_button).setVisibility(View.VISIBLE);
    }

    private void setupThemeButton() {

        updateThemeButtonText();

        try {
            Button themeButton = findViewById(R.id.themeButton);
            if (themeButton != null) {
                themeButton.setOnClickListener(view -> toggleDarkMode());
            } else {
                Log.e(TAG, "themeButton is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting up theme button: ", e);
        }
    }

    private void startTranscription() {
        transcription = findViewById(R.id.transcription);
        String readyToTranscript = "Bereit... Drücken Sie die Taste 'Hören', um die Transkription zu starten.";
        transcription.append(readyToTranscript + "\n"); //don't scrollToBottom here, results in app crash

        logToFile(readyToTranscript + "\n");
    }

    public void showTranscription(@NotNull String text){
        try{
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            transcription.append(timestamp + ": " + text + "\n");
            scrollToBottom();
        }catch (Exception e){
            Log.e(TAG, "Error in showTranscription: " + e.getMessage());
        }
    }

    /* Voice Commands */
    @Override
    public void onAsrResult(@NotNull String asrResult, @NonNull SttLanguage sttLanguage) {
        try {
            Log.i(TAG, "ASR Result: " + asrResult);
            myAsrResultString = asrResult;
            showTranscription("myAsrResultString: " + myAsrResultString);

            analyzeVoiceCommand();
            flagWaitingForUserResponse = false;
            temi.finishConversation();
        } catch (Exception e) {
            Log.e(TAG, "Error in onAsrResult: " + e.getMessage());
            showTranscription("Ein Fehler ist aufgetreten. Bitte versuchen Sie es erneut.");
        }
    }

    @Override
    public void onConversationStatusChanged(int status, @NotNull String text) {
        myAsrResultString += text;
        switch (status)
        {
            case IDLE:
                Log.i(TAG, "Status: IDLE | Text: " + myAsrResultString);
                showTranscription("Status: IDLE | Text: " + myAsrResultString);
                findViewById(R.id.isRecordingImg).setVisibility(View.INVISIBLE);
                break;
            case LISTENING:
                Log.i(TAG, "Status: LISTENING | Text: " + myAsrResultString);
                showTranscription("Status: LISTENING | Text: " + myAsrResultString);
                findViewById(R.id.isRecordingImg).setVisibility(View.VISIBLE);
                break;
            case THINKING:
                Log.i(TAG, "Status: THINKING | Text: " + myAsrResultString);
                showTranscription("Status: THINKING | Text: " + myAsrResultString);
                break;
            case SPEAKING:
                Log.i(TAG, "Status: SPEAKING | Text: " + myAsrResultString);
                showTranscription("Status: SPEAKING | Text: " + myAsrResultString);
                break;
            default:
                Log.i(TAG, "Status: UNKNOWN | Text: " + myAsrResultString);
                showTranscription("Status: UNKNOWN | Text: " + myAsrResultString);
                findViewById(R.id.isRecordingImg).setVisibility(View.INVISIBLE);
                break;
        }

        if(flagWaitingForUserResponse & status == IDLE)
        {
            flagWaitingForUserResponse = false;

            chooseCurrentSequence();
        }
    }

    @FunctionalInterface
    interface CommandAction {
        void execute(String command);
    }

    private final Map<String[], CommandAction> commandsMap = new HashMap<>();
    private void initCommandsMap() {
        commandsMap.put(new String[]{"gehe", "geh", "fahre", "fahr"}, command -> relocateTemi());
        commandsMap.put(new String[]{"folge mir", "komm mit mir"}, command -> followMe());
        commandsMap.put(new String[]{"transkription starten", "aufnahme beginnen", "aufnahme starten"}, command -> findViewById(R.id.listen).performClick());
        commandsMap.put(new String[]{"transkription beenden", "aufnahme beenden"}, command -> findViewById(R.id.endTranscription).performClick());
        commandsMap.put(new String[]{"applikation beenden", "app beenden"}, command -> findViewById(R.id.quitButton).performClick());
        commandsMap.put(new String[]{"dunkler modus", "heller modus", "keller modus"}, command -> findViewById(R.id.themeButton).performClick());
        commandsMap.put(new String[]{"stopp","stop","abbrechen","abbruch" }, command -> stopCurrentSequence());
        commandsMap.put(new String[]{"gesprächsmodus", "dialogmodus", "gesprächs modus"}, command -> conversationMode = !conversationMode);
        commandsMap.put(new String[]{"erinnere mich", "erinnerung setzen", "setze eine erinnerung"}, command -> setReminder());
        commandsMap.put(new String[]{"wiederholen", "erneut", "wiederhole", "noch mal", "nicht verstanden"}, command -> findViewById(R.id.repeatButton).performClick());
        /* Sequences */
        commandsMap.put(new String[]{"gäste begrüßen", "begrüßung starten", "willkommenssequenz starten", "sequenz 1 starten",
                "sequenz 1 beginnen"}, command -> {
            currentSequence = Sequence.GREETING;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });
        commandsMap.put(new String[]{"sequenz 2 starten", "sequenz 2 beginnen", "alexa sequenz ausführen",
                "alexa sequenz starten", "sag alexa die rolläden zu schließen"}, command -> {
            currentSequence = Sequence.SEQUENCE_ALEXA;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });
        commandsMap.put(new String[]{"aal sequenz", "sequenz 2 starten", "rundgang starten"}, command -> {
            currentSequence = Sequence.AAL_SEQUENCE;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });

        commandsMap.put(new String[]{"gedächtnisspiel", "denksport"}, command -> {
            currentSequence = Sequence.SEQUENCE_BRAIN_GAME;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });
        commandsMap.put(new String[]{"fragebogen starten", "fragebogensequenz starten", "fragebogen beginnen" }, command -> {
            currentSequence = Sequence.QUESTIONNAIRE;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });
        commandsMap.put(new String[]{"führung durch die küche starten" }, command -> {
            currentSequence = Sequence.KITCHEN;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });
    }

    private void handleUndefinedCommand() {
        String undefinedCommandMessage = "Entschuldige, ich habe das nicht verstanden.";
        showTranscription(undefinedCommandMessage);
        temi.speak(TtsRequest.create(undefinedCommandMessage, false));
    }

    public void analyzeVoiceCommand() {
        myAsrResultString = myAsrResultString.toLowerCase();

        boolean commandFound = false;
        for (Map.Entry<String[], CommandAction> entry : commandsMap.entrySet()) {
            for (String command : entry.getKey()) {
                if (myAsrResultString.contains(command)) {
                    entry.getValue().execute(myAsrResultString);
                    commandFound = true;
                    break;
                }
            }
            if (commandFound) break;
        }

        if (!commandFound) {
            handleUndefinedCommand();
        } else {
            chooseCurrentSequence();
        }
    }

    public void stopCurrentSequence()
    {
        temi.stopMovement();
        temi.cancelAllTtsRequests();
        currentSequenceStep = 0;
        currentSequence = null;
    }

    //Handles result from voice commands and SequenceActivity.java click result
    public void chooseCurrentSequence()
    {
        switch(currentSequence)
        {
            case GREETING:
                scrollToBottom();
                showFace();
                handleSequenceGreeting();
                break;
            case SEQUENCE_ALEXA:
                scrollToBottom();
                showFace();
                handleSequenceAlexa();
                break;
            case AAL_SEQUENCE:
                scrollToBottom();
                showFace();
                handleAALSequence();
                break;
            case SEQUENCE_BRAIN_GAME:
                //scrollToBottom();
                showFace();
                handleSequenceBrainGame();
                break;
            case QUESTIONNAIRE:
                scrollToBottom();
                showFace();
                handleSequenceQuestionnaire();
                break;
            case KITCHEN:
                scrollToBottom();
                showFace();
                handleSequenceKitchen();
                break;
            default:
                showTranscription("Error default in switch(currentsequence)");
                break;
        }

    }

    @Override
    public void onTtsStatusChanged(@NonNull TtsRequest ttsRequest) {
        TtsRequest.Status status = ttsRequest.getStatus();
        if (status == TtsRequest.Status.COMPLETED && flagWaitingForTemiToFinishSpeaking) {
            flagWaitingForTemiToFinishSpeaking = false;
            currentSequenceStep += 1;
            chooseCurrentSequence();
        }
    }

    @Override
    public void onGoToLocationStatusChanged(@NonNull String location, @NonNull String status, int descriptionId, @NonNull String description) {
        if (status.equals(COMPLETE) && flagWaitingForTemiToArrive) {
            flagWaitingForTemiToArrive = false;
            currentSequenceStep += 1;
            chooseCurrentSequence();
        }
    }

    public void handleSequenceGreeting() //version from 28.05 saved in the commit and on the desktop txt file
    {
        String nextSentence;
        try {
            switch (currentSequenceStep) {
                case 0:
                    flagWaitingForTemiToArrive = true;      /// SUPER IMPORTANT BEFORE EVERY GO-TO-COMMAND!!!!
                    temi.goTo("tür");               /// so that the next task is started AFTER arriving at destination
                    // step increment done by the onGoToStatusListener()
                    break;
                case 1:
                    nextSentence = getString(R.string.greeting_welcome_door);
                    flagWaitingForTemiToFinishSpeaking = true;  /// SUPER IMPORTANT BEFORE EVERY speaking-COMMAND!!!!
                    temi.speak(TtsRequest.create(nextSentence, false));
                    break;
                case 2:
                    flagWaitingForTemiToArrive = true;
                    temi.goTo("wohnzimmer");
                    break;
                case 3:
                    nextSentence = getString(R.string.greeting_livingroom_have_a_seat);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    break;
                case 4:
                    if(myAsrResultString.contains("stell dich vor") | myAsrResultString.contains("stelle dich vor") | myAsrResultString.contains("beginne die untersuchung") | myAsrResultString.contains("starte die untersuchung"))
                    {
                        flagWaitingForTemiToArrive = true;
                        temi.goTo("wohnzimmersitzgruppe");
                    }
                    break;
                case 5:
                    nextSentence = getString(R.string.greeting_introduction);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    break;
                case 6:
                    currentSequenceStep = 0;
                    currentSequence = Sequence.KITCHEN;
                    break;
                default:
                    currentSequenceStep = 0;
                    showTranscription("Error: Default in Sequenz GREETING!");
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleSequenceGreeting: " + e.getMessage());
            showTranscription("Ein Fehler ist aufgetreten. Bitte versuchen Sie es erneut.");
        }
    }

    public void handleSequenceQuestionnaire() {
        try {
            switch (currentSequenceStep) {
                case 0:
                    String questions_intro = getString(R.string.survey_questions_intro);
                    showTranscription(questions_intro);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(questions_intro, false));
                    break;
                case 1:
                    askSurveyQuestion(R.string.survey_question_1);
                    break;
                case 2: case 4: case 6: case 8: case 10: case 12: case 14:
                case 16: case 18: case 20: case 22: case 24: case 26: case 28:
                    flagWaitingForUserResponse = true;
                    temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
                    findViewById(R.id.isRecordingImg).setVisibility(View.VISIBLE);

                    currentSequenceStep++;
                    break;
                case 3:
                    askSurveyQuestion(R.string.survey_question_3);
                    break;
                case 5:
                    askSurveyQuestion(R.string.survey_question_4);
                    break;
                case 7:
                    askSurveyQuestion(R.string.survey_question_5);
                    break;
                case 11:
                    askSurveyQuestion(R.string.survey_question_6);
                    break;
                case 13:
                    askSurveyQuestion(R.string.survey_question_7);
                    break;
                case 15:
                    askSurveyQuestion(R.string.survey_question_8);
                    break;
                case 17:
                    askSurveyQuestion(R.string.survey_question_9);
                    break;
                case 19:
                    askSurveyQuestion(R.string.survey_question_10);
                    break;
                case 21:
                    askSurveyQuestion(R.string.survey_question_11);
                    break;
                case 23:
                    askSurveyQuestion(R.string.survey_question_12);
                    break;
                case 25:
                    askSurveyQuestion(R.string.survey_question_13);
                    break;
                case 27:
                    String suggestions = getString(R.string.survey_suggestions);
                    showTranscription(suggestions);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(suggestions, false));
                    break;
                case 29:
                    String goodbye_string = getString(R.string.survey_goodbye_string);
                    showTranscription(goodbye_string);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(goodbye_string, false));
                    currentSequenceStep = 0;
                    currentSequence = null;
                    break;
                default:
                    showTranscription("Error: Default in Sequenz GREETING!");
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleSequenceQuestionnaire: " + e.getMessage());
            showTranscription("Ein Fehler ist aufgetreten. Bitte versuchen Sie es erneut.");
        }
    }

    private void askSurveyQuestion(int questionResId) {
        String question = getString(questionResId);
        showTranscription(question);
        flagWaitingForTemiToFinishSpeaking = true;
        temi.speak(TtsRequest.create(question, false));
    }

    private void handleSequenceAlexa() {
        try {
            switch (currentSequenceStep) {
                case 0:
                    String alexa_command_init = "Okay. Ich gehe zu Alexa um ihr zu sagen, dass sie den Rolläden schließen soll.";
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(alexa_command_init, false));
                    break;
                case 1:
                    flagWaitingForTemiToArrive = true;
                    temi.goTo("alexa");
                    break;
                case 2:
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create("Alexa", false));
                    break;
                case 3:
                    waitHandler.postDelayed(() -> {
                        flagWaitingForTemiToFinishSpeaking = true;
                        temi.speak(TtsRequest.create("Bitte schließe die Rolläden", false));
                    }, 1000);
                    currentSequenceStep = 0;
                    currentSequence = null;
                    break;
                default:
                    showTranscription("Error: Default in Sequenz Alexa!");
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleSequenceAlexa: " + e.getMessage());
            showTranscription("Ein Fehler ist aufgetreten. Bitte versuchen Sie es erneut.");
        }
    }

    private void handleAALSequence() {
        try {
            switch (currentSequenceStep) {
                case 0:
                    flagWaitingForTemiToArrive = true;
                    temi.goTo("tür");
                    break;
                case 1:
                    String entranceWelcome = getString(R.string.aal_welcome);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(entranceWelcome, false));
                    break;
                case 2:
                    String introduction = getString(R.string.aal_intro);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(introduction, false));
                    break;
                case 3:
                    flagWaitingForTemiToArrive = true;
                    temi.goTo("küche");
                    break;
                case 4:
                    String kitchenIntro = getString(R.string.aal_kitchen_intro);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(kitchenIntro, false));
                    break;
                case 5:
                    String kitchenDetails1 = getString(R.string.aal_kitchen_details1);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(kitchenDetails1, false));
                    break;
                case 6:
                    String kitchenDetails2 = getString(R.string.aal_kitchen_details2);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(kitchenDetails2, false));
                    break;
                case 7:
                    flagWaitingForTemiToArrive = true;
                    temi.goTo("waschbecken");
                    break;
                case 8:
                    String sinkDetails = getString(R.string.aal_sink_details);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(sinkDetails, false));
                    break;
                case 9:
                    String worktopDetails = getString(R.string.aal_worktop_details);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(worktopDetails, false));
                    break;
                case 10:
                    String kitchenSummary = getString(R.string.aal_kitchen_summary);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(kitchenSummary, false));
                    break;
                case 11:
                    flagWaitingForTemiToArrive = true;
                    temi.goTo("wohnzimmer");
                    break;
                case 12:
                    String livingRoomIntro = getString(R.string.aal_livingroom_intro);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(livingRoomIntro, false));
                    break;
                case 13:
                    String livingRoomDetails1 = getString(R.string.aal_livingroom_details1);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(livingRoomDetails1, false));
                    break;
                case 14:
                    String livingRoomDetails2 = getString(R.string.aal_livingroom_details2);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(livingRoomDetails2, false));
                    break;
                case 15:
                    String farewell = getString(R.string.aal_farewell);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(farewell, false));
                    currentSequenceStep = 0;
                    currentSequence = null;
                    break;
                default:
                    showTranscription("Error: Default in Sequenz AAL!");
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleAALSequence: " + e.getMessage());
            showTranscription("Ein Fehler ist aufgetreten. Bitte versuchen Sie es erneut.");
        }
    }

    private void handleSequenceBrainGame() {
        try {
            showTranscription("DEGBUG: in handleBrainGame: Schritt = " + currentSequenceStep);

            String nextSentence = "";

            switch (currentSequenceStep) {
                case 0:
                    showTranscription("DEGBUG: Start Sequence: handleSequenceBrainGame");
                    flagRepeatSentenceRequest = false;
                    String introducingBrainGame = getString(R.string.bg_introduction_short);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(introducingBrainGame, false));
                    break;
                case 1: case 3: case 5: case 7:
                case 9: case 11: case 14:
                    temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
                    findViewById(R.id.isRecordingImg).setVisibility(View.VISIBLE);
                    flagWaitingForUserResponse = true;
                    currentSequenceStep++;
                    break;
                case 2:
                    if(((myAsrResultString.contains("ja") | myAsrResultString.contains("bereit")) & !myAsrResultString.contains("nicht")) | flagRepeatSentenceRequest) {
                        flagRepeatSentenceRequest = false;
                        String sentence1 = getString(R.string.bg_letsgo) + getString(R.string.bg_sentence1);
                        flagWaitingForTemiToFinishSpeaking = true;
                        temi.speak(TtsRequest.create(sentence1, false));
                    } else {
                        String repeatIntdroduction = getString(R.string.bg_iRepeat) + getString(R.string.bg_introduction);
                        currentSequenceStep = 0;
                        flagWaitingForTemiToFinishSpeaking = true;
                        temi.speak(TtsRequest.create(repeatIntdroduction, false));
                    }
                    flagRepeatSentenceRequest = false;
                    break;
                case 4:
                    showTranscription("DEGBUG: myAsrResultString = " + myAsrResultString);
                    if(checkRepeatRequest()) {
                        currentSequenceStep -= 2;
                        flagRepeatSentenceRequest = true;
                        myAsrResultString = "";
                        chooseCurrentSequence();
                        break;
                    }
                    if(((myAsrResultString.contains("taube") || myAsrResultString.contains("spatz")) && myAsrResultString.contains("dach")) | flagRepeatSentenceRequest) {
                        if(!flagRepeatSentenceRequest) {
                            nextSentence += getString(R.string.bg_answer_correct);
                        }
                        nextSentence += getString(R.string.bg_sentence2);
                    } else {
                        if(checkForDontKnowAnswer()) {
                            nextSentence = getString(R.string.bg_dont_know_answer);
                        } else {
                            nextSentence = getString(R.string.bg_sorry_wrong);
                        }
                        nextSentence += getString(R.string.bg_sentence1_correct) + getString(R.string.bg_sentence2);
                    }
                    flagRepeatSentenceRequest = false;
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    break;
                case 6:
                    showTranscription("DEGBUG: InHandle myAsrResultString = " + myAsrResultString);
                    if(checkRepeatRequest()) {
                        currentSequenceStep -= 2;
                        flagRepeatSentenceRequest = true;
                        myAsrResultString = "";
                        chooseCurrentSequence();
                        break;
                    }

                    if((myAsrResultString.contains("macht") && myAsrResultString.contains("sommer")) | flagRepeatSentenceRequest) {
                        if(!flagRepeatSentenceRequest) {
                            nextSentence += getString(R.string.bg_answer_correct);
                        }
                        nextSentence += getString(R.string.bg_sentence3);
                    } else {
                        if(checkForDontKnowAnswer()) {
                            nextSentence = getString(R.string.bg_dont_know_answer);
                        } else {
                            nextSentence = getString(R.string.bg_sorry_wrong);
                        }
                        nextSentence += getString(R.string.bg_sentence2_correct) + getString(R.string.bg_sentence3);
                    }
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    flagRepeatSentenceRequest = false;
                    break;
                case 8:
                    showTranscription("DEGBUG: myAsrResultString = " + myAsrResultString);
                    if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                        currentSequenceStep -= 2;
                        flagRepeatSentenceRequest = true;
                        myAsrResultString = "";
                        chooseCurrentSequence();
                        break;
                    }

                    if((myAsrResultString.contains("torheit") && myAsrResultString.contains("nicht")) | flagRepeatSentenceRequest) {
                        if(!flagRepeatSentenceRequest) {
                            nextSentence += getString(R.string.bg_answer_correct);
                        }
                        nextSentence += getString(R.string.bg_sentence4);
                    } else {
                        if(checkForDontKnowAnswer()) {
                            nextSentence = getString(R.string.bg_dont_know_answer);
                        } else {
                            nextSentence = getString(R.string.bg_sorry_wrong);
                        }
                        nextSentence += getString(R.string.bg_sentence3_correct) + getString(R.string.bg_sentence4);
                    }
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    flagRepeatSentenceRequest = false;
                    break;
                case 10:
                    showTranscription("DEGBUG: myAsrResultString = " + myAsrResultString);
                    if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                        currentSequenceStep -= 2;
                        flagRepeatSentenceRequest = true;
                        myAsrResultString = "";
                        chooseCurrentSequence();
                        break;
                    }

                    if((myAsrResultString.contains("wird") && myAsrResultString.contains("kalt")) | flagRepeatSentenceRequest) {
                        if(!flagRepeatSentenceRequest) {
                            nextSentence += getString(R.string.bg_answer_correct);
                        }
                        nextSentence += getString(R.string.bg_sentence5);
                    } else {
                        if(checkForDontKnowAnswer()) {
                            nextSentence = getString(R.string.bg_dont_know_answer);
                        } else {
                            nextSentence = getString(R.string.bg_sorry_wrong);
                        }
                        nextSentence += getString(R.string.bg_sentence4_correct) + getString(R.string.bg_sentence5);
                    }
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    flagRepeatSentenceRequest = false;
                    break;
                case 12:
                    showTranscription("DEGBUG: myAsrResultString = " + myAsrResultString);
                    if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                        currentSequenceStep -= 2;
                        flagRepeatSentenceRequest = true;
                        myAsrResultString = "";
                        chooseCurrentSequence();
                        break;
                    }

                    if((myAsrResultString.contains("schnaps") | flagRepeatSentenceRequest)) {
                        if(!flagRepeatSentenceRequest) {
                            nextSentence += getString(R.string.bg_answer_correct);
                        }
                        nextSentence += getString(R.string.bg_answer_correct);
                    } else {
                        if(checkForDontKnowAnswer()) {
                            nextSentence = getString(R.string.bg_dont_know_answer);
                        } else {
                            nextSentence = getString(R.string.bg_sorry_wrong);
                        }
                        nextSentence += getString(R.string.bg_sentence5_correct);
                    }
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    flagRepeatSentenceRequest = false;
                    break;
                case 13:
                    String finish = getString(R.string.bg_finish_question);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(finish, false));
                    break;
                case 15:
                    showTranscription("DEGBUG: myAsrResultString = " + myAsrResultString);
                    if(myAsrResultString.contains("ja")) {
                        nextSentence = getString(R.string.bg_finish_answer_positive);
                    } else {
                        nextSentence = getString(R.string.bg_finish_answer_negative);
                    }

                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    break;
                case 16:
                    flagRepeatSentenceRequest = false;
                    currentSequenceStep = 0;
                default:
                    showTranscription("DEGBUG: default in handleSequenceBrainGame !");
                    flagRepeatSentenceRequest = false;
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleSequenceBrainGame: " + e.getMessage());
            showTranscription("Ein Fehler ist aufgetreten. Bitte versuchen Sie es erneut.");
        }
    }

    private boolean checkRepeatRequest() {
        return myAsrResultString.contains("wiederhole") | myAsrResultString.contains("noch mal") | myAsrResultString.contains("nicht verstanden");
    }
    private boolean checkForDontKnowAnswer() {
        return myAsrResultString.contains("weiß ich nicht") | myAsrResultString.contains("kenne ich nicht") | myAsrResultString.contains("unbekannt") | myAsrResultString.contains("keine ahnung");
    }

    private void handleSequenceKitchen() {
        try {
            String nextSentence = "";
            switch (currentSequenceStep) {
                case 0:
                    if (checkForContinueNextSequence() | myAsrResultString.contains("führung durch die küche starten")) {
                        nextSentence = getString(R.string.kt_letsgo);
                        flagWaitingForTemiToFinishSpeaking = true;
                    } else {
                        nextSentence = getString(R.string.insufficant_answer);
                    }
                    temi.speak(TtsRequest.create(nextSentence, false));
                    break;
                case 1:
                    flagWaitingForTemiToArrive = true;
                    temi.goTo("küche");
                    break;
                case 2:
                    nextSentence = getString(R.string.kt_takeDrink);
                    temi.speak(TtsRequest.create(nextSentence, false));
                    currentSequenceStep++;
                    break;
                case 3:
                    if (myAsrResultString.contains("was kannst du mir zur küche sagen")
                            | (myAsrResultString.contains("zeige") & myAsrResultString.contains("küche"))) {
                        nextSentence = getString(R.string.aal_kitchen_intro)
                                + getString(R.string.aal_kitchen_summary);
                    }

                    nextSentence += getString(R.string.kt_tour_finished);

                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    break;
                case 4:
                    currentSequenceStep = 0;
                    currentSequence = Sequence.SEQUENCE_ALEXA;
                    break;
                default:
                    showTranscription("DEGBUG: default in handleSequenceKitchen !");
                    currentSequenceStep = 0;
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleSequenceKitchen: " + e.getMessage());
            showTranscription("Ein Fehler ist aufgetreten. Bitte versuchen Sie es erneut.");
        }
    }

    private boolean checkForContinueNextSequence() {
        return myAsrResultString.contains("lass uns weitermachen")
                | myAsrResultString.contains("ich bin bereit")
                | myAsrResultString.contains("es kann weitergehen")
                | myAsrResultString.contains("weiter")
                | myAsrResultString.contains("was jetzt");
    }

    public void relocateTemi() {
        try {
            if (myAsrResultString.contains("tür")) {
                temi.goTo("tür");
            } else if (myAsrResultString.contains("basisstation")) {
                temi.goTo("home base");
            } else if (myAsrResultString.contains("wohnzimmer")) {
                temi.goTo("wohnzimmer");
            } else if (myAsrResultString.contains("küche")) {
                temi.goTo("küche");
            } else if (myAsrResultString.contains("büro")) {
                temi.goTo("büro");
            } else if (myAsrResultString.contains("esszimmer")) {
                temi.goTo("esszimmer");
            } else if (myAsrResultString.contains("waschbecken")) {
                temi.goTo("waschbecken");
            } else if (myAsrResultString.contains("alexa")) {
                temi.goTo("alexa");
            } else {
                String unknownPlace = "Entschuldige diesen Ort kenne ich leider nicht.";
                showTranscription(unknownPlace);
                temi.speak(TtsRequest.create(unknownPlace, false));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in relocateTemi: " + e.getMessage());
            showTranscription("Ein Fehler ist aufgetreten. Bitte versuchen Sie es erneut.");
        }
    }

    /* Reminder */
    public void setReminder() {
        long timeInMillis = System.currentTimeMillis() + 15000; // Set reminder after 15 seconds for demonstration
        Reminder reminder = new Reminder(myAsrResultString, timeInMillis);
        reminderManager.setReminder(reminder);

        String reminderString = "Erinnerung: " + myAsrResultString;
        showTranscription(reminderString);
        temi.speak(TtsRequest.create(reminderString, false));
    }

    /* Text Field to TXT File*/
    private void createLogFile() {
        String timestamp = new SimpleDateFormat("yyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String filename = "log_" + timestamp + ".txt";

        try {
            logFile = new File(getFilesDir(), filename); // Use internal storage
            if (logFile.createNewFile()) {
                Toast.makeText(getApplicationContext(), "Log file created: " + logFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getApplicationContext(), "Log file already exists", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(getApplicationContext(), "Error creating log file", Toast.LENGTH_SHORT).show();
        }
    }

    public void logToFile(String logMessage) {
        if (logFile != null) {
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.append(logMessage).append("\n");
            } catch (IOException e) {
                Toast.makeText(getApplicationContext(), "Error logging message", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(getApplicationContext(), "Log file is not created", Toast.LENGTH_SHORT).show();
        }
    }

    /* Videocall start */

    private void setupVideoCall() {
        Log.i(TAG, "Entered transportMode");
        setContentView(R.layout.activity_main);

        temi.addOnRobotReadyListener(isReady -> {
            if (isReady) {
                initializeTemi();
            } else {
                new Handler().postDelayed(this::retryInitialization, 1000);
            }
        });

        Button yes_button = findViewById(R.id.confirmArrivedButton);
        yes_button.setOnClickListener(v -> arrived());

        Spinner spinner = findViewById(R.id.dropdownMenu);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Object item = parent.getItemAtPosition(position);
                showTranscription("Loaded item object: " + item.toString());

                if (item instanceof UserInfo) {
                    contact = (UserInfo) item;
                    showTranscription("Contact = " + contact.getName());
                } else {
                    showTranscription("Selected item is not an instance of UserInfo");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                Log.e(TAG, "Nothing is selected");
            }
        });
    }
    private void retryInitialization() {
        Log.i(TAG, "Retrying initialization...");
        temi.addOnRobotReadyListener(isReady -> {
            if (isReady) {
                initializeTemi();
            } else {
                new Handler().postDelayed(this::retryInitialization, 1000);
            }
        });
    }

    private void initializeTemi() {
        refreshTemiUi();
        updateValidContacts();
        fillDropdownMenu();
        temi.setHardButtonsDisabled(true);
        temi.setGoToSpeed(SpeedLevel.SLOW);
    }

    private void updateValidContacts() {
        List<UserInfo> allContacts = temi.getAllContact();
        validContacts.clear();
        validContacts.addAll(allContacts);
    }

    private void fillDropdownMenu() {
        List<UserInfo> contactList = new ArrayList<>(temi.getAllContact());
        contactList.addAll(validContacts);

        Set<UserInfo> set = new HashSet<>(contactList);
        contactList = new ArrayList<>(set);

        List<String> contactNames = new ArrayList<>();
        for (UserInfo userInfo : contactList) {
            contactNames.add(userInfo.getName());
            showTranscription("Contact added: " + userInfo.getName());
        }

        String[] contacts = contactNames.toArray(new String[0]);
        Spinner dropdownMenu = findViewById(R.id.dropdownMenu);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.dropdown_menu_text_view, contacts);
        adapter.setDropDownViewResource(R.layout.dropdown_menu_pick_text_view);
        dropdownMenu.setAdapter(adapter);

        dropdownMenu.setTag(contactList);
    }

    private final OnTelepresenceStatusChangedListener telepresenceStatusChangedListener = new OnTelepresenceStatusChangedListener("") {
        @Override
        public void onTelepresenceStatusChanged(CallState callState) {
            Log.i("Telepresence", "CallState " + callState + ", " + callState.getLowLightMode());
        }
    };

    @Override
    public void onTelepresenceEventChanged(@NotNull CallEventModel callEventModel) {
        Log.i("Telepresence", "Call Event: " + callEventModel);
    }

    private void startVideoMeeting(UserInfo target) {
        if (target == null) {
            showTranscription("No contact chosen.");
            return;
        }

        List<Participant> participants = Arrays.asList(
                new Participant(target.getUserId(), Platform.MOBILE),
                new Participant(target.getUserId(), Platform.TEMI_CENTER)
        );

        showTranscription(participants.toString());

        String resp = temi.startMeeting(participants, true, false);
        showTranscription("startMeeting result :" + resp);
    }

    private void followMe() {
        Log.i(TAG, "Follow the user");
        temi.beWithMe();
    }

    @Override
    public void onRobotReady(boolean b) {
        if (temi.isReady()) {
            Log.i(TAG, "Robot is ready");
            temi.hideTopBar();
        }
    }

    /* Dark Mode */
    private void toggleDarkMode() {
        Log.i(TAG, "Toggled Dark Mode");

        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
        recreate();
    }
    private void updateThemeButtonText() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        Button theme_button = findViewById(R.id.themeButton);
        if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
            theme_button.setText(R.string.dark_theme);
        } else {
            theme_button.setText(R.string.light_theme);
        }
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void refreshTemiUi() {
        try {
            ActivityInfo activityInfo = getPackageManager()
                    .getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
            Robot.getInstance().onStart(activityInfo);
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing temi UI: ", e);
        }
    }

    @SuppressLint("SetTextI18n")
    public void receivedFloor(int number) {
        runOnUiThread(() -> {
            TextView textView = findViewById(R.id.elevatorTextView);
            textView.setText("Der Raum befindet sich auf dem Stockwerk: " + number);
            this.waitingForFloor = false;
            this.waitingForReset = true;

            waitHandler.postDelayed(() -> {
                if (this.waitingForFinish) {
                    Log.w(TAG, "no button was pressed");
                    reset();
                }
            }, 60000);
        });
    }

    public void arrived() {
        if (!this.waitingForFinish) {
            temi.goTo(newDest);
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

    public static MainActivity getInstance() {
        return instance;
    }

    public Robot getTemi() {
        return temi;
    }

    public int getMyfloorNumber() {
        return 0;
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
            Spinner spinner = findViewById(R.id.dropdownMenu);
            TextView textView = findViewById(R.id.topTextView);
            findViewById(R.id.isRecordingImg).setVisibility(View.INVISIBLE);
            elevatorTextView.setVisibility(View.INVISIBLE);
            confirmTextView.setVisibility(View.INVISIBLE);
            confirmYesButton.setVisibility(View.INVISIBLE);
            confirmElevatorButton.setVisibility(View.INVISIBLE);
            spinner.setVisibility(View.VISIBLE);
            textView.setVisibility(View.VISIBLE);
        });
    }

    public void setDestinationFloor(int destinationFloor) {
        this.destinationFloor = destinationFloor;
    }
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("transcript", transcription.getText().toString());
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        String transcript = savedInstanceState.getString("transcript");
        transcription.setText(transcript);
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

    @Override
    protected void onStop() {
        super.onStop();

        temi.removeOnRobotReadyListener(this);
        temi.removeAsrListener(this);
        temi.removeOnConversationStatusChangedListener(this);
        temi.removeOnTelepresenceStatusChangedListener(telepresenceStatusChangedListener);
        temi.removeOnTelepresenceEventChangedListener(this);
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
