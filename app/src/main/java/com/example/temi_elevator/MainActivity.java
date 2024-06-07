package com.example.temi_elevator;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
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
import android.widget.ScrollView;

import android.widget.Toast;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.robotemi.sdk.*;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.constants.Page;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.navigation.model.SpeedLevel;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

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
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Date;

import com.robotemi.sdk.listeners.OnConversationStatusChangedListener;
import com.robotemi.sdk.listeners.OnRobotReadyListener;
import com.robotemi.sdk.TtsRequest;
import com.bumptech.glide.Glide;

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
    private List<String> validLocations = new ArrayList<>();
    private int myfloorNumber = 0;
    private boolean waitingForFloor = false;
    private boolean waitingForReset = false;
    private boolean waitingForFinish = false;
    private final Handler waitHandler = new Handler();
    private TextView transcription;
    private ScrollView scrollView;
    private ActivityResultLauncher<Intent> sequenceResultLauncher;
    private ExecutorService myExecutorService;
    private String myAsrResultString = "";
    enum Sequence {GREETING, SEQUENCE_ALEXA, AAL_SEQUENCE, SEQUENCE_BRAIN_GAME, QUESTIONNAIRE}
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
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        temi.setKioskModeOn(true);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        instance = this;
        transportMode();

        myExecutorService = Executors.newSingleThreadExecutor();

        init(savedInstanceState);

        createLogFile();
    }

    private void init(Bundle savedInstanceState) {
        transcriptMode();
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
            temi.setKioskModeOn(false);
            temi.setHardButtonsDisabled(false);
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

            findViewById(R.id.overlay_image).setVisibility(View.VISIBLE);
            findViewById(R.id.img_close_button).setVisibility(View.VISIBLE);
        });

        findViewById(R.id.img_close_button).setOnClickListener(view ->  {
            findViewById(R.id.overlay_image).setVisibility(View.GONE);
            findViewById(R.id.img_close_button).setVisibility(View.GONE);
        });

        /* Repeat Button */
        findViewById(R.id.repeatSequenceStep).setOnClickListener(view ->  {
            if(currentSequence == null){
                showTranscription("Keine Sequenz ausgewählt");
            }else if(currentSequence == Sequence.SEQUENCE_BRAIN_GAME){
                currentSequenceStep -= 2;
            }else{
                currentSequenceStep--;
            }

            //for error avoidance
            if(currentSequenceStep < 0){
                currentSequenceStep = 0;
            }

            chooseCurrentSequence();
        });
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

    private void transcriptMode() {
        transcription = findViewById(R.id.transcription);
        String readyToTranscript = "Bereit... Drücken Sie die Taste 'Hören', um die Transkription zu starten.";
        transcription.append(readyToTranscript + "\n"); //don't scrollToBottom here, results in app crash

        transcription.append("Kiosk Mode is on: " + temi.isKioskModeOn()); //check if kiosk mode is on

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
        Log.i(TAG, "ASR Result: " + asrResult);
        myAsrResultString = asrResult;
        showTranscription("myAsrResultString: " + myAsrResultString);

        analyzeVoiceCommand();
        flagWaitingForUserResponse = false; // relevant for the BrainGame when user doesnt say anything
        temi.finishConversation(); // stop ASR listener
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
        commandsMap.put(new String[]{"wiederholen", "erneut", "wiederhole", "noch mal", "nicht verstanden"}, command -> findViewById(R.id.repeatSequenceStep).performClick());
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

        chooseCurrentSequence();
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
                handleSequenceGreeting();
                break;
            case SEQUENCE_ALEXA:
                scrollToBottom();
                handleSequenceAlexa();
                break;
            case AAL_SEQUENCE:
                scrollToBottom();
                handleAALSequence();
                break;
            case SEQUENCE_BRAIN_GAME:
                //scrollToBottom();
                handleSequenceBrainGame();
                break;
            case QUESTIONNAIRE:
                scrollToBottom();
                handleSequenceQuestionnaire();
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
        switch (currentSequenceStep)
        {
            case 0:
                flagWaitingForTemiToArrive = true;      /// SUPER IMPORTANT BEFORE EVERY GO-TO-COMMAND!!!!
                temi.goTo("tür");               /// so that the next task is started AFTER arriving at destination
                // step increment done by the onGoToStatusListener()
                break;
            case 1:
                waitHandler.postDelayed(() -> {
                    temi.tiltAngle(20);
                }, 5000);
                String greeting_string = getString(R.string.greeting_string);
                //showTranscription(greeting_string);
                flagWaitingForTemiToFinishSpeaking = true;  /// SUPER IMPORTANT BEFORE EVERY speaking-COMMAND!!!!
                /// so that the next task is started AFTER arriving at destination
                temi.speak(TtsRequest.create(greeting_string, false));
                // step increment done by the onTtsStatusChanged() when temi finished speaking previous string
                break;
            case 2:
                waitHandler.postDelayed(() -> {
                }, 3000);
                flagWaitingForTemiToArrive = true;
                temi.goTo("wohnzimmer");
                break;
            case 3:

                String livingroom_string = getString(R.string.livingroom_string);
                //showTranscription(livingroom_string);
                waitHandler.postDelayed(() -> {
                }, 5000);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(livingroom_string, false));
                // step increment done by the onTtsStatusChanged() when temi finished speaking previous string
                break;
            case 4:
                waitHandler.postDelayed(() -> {
                }, 3000);
                String introduction_string = getString(R.string.introduction_string);
                //showTranscription(introduction_string);
                temi.speak(TtsRequest.create(introduction_string, false));
                waitHandler.postDelayed(() -> {
                }, 3000);
                // step increment done by the onTtsStatusChanged() when temi finished speaking previous string
                currentSequenceStep = 0;
                currentSequence = null;
                break;
            default:
                showTranscription("Error: Default in Sequenz GREETING!");
                break;
        }
    }

    public void handleSequenceQuestionnaire()
    {
        switch (currentSequenceStep)
        {
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
            case 29: // End of sequence GREETING
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
    }

    private void askSurveyQuestion(int questionResId) {
        String question = getString(questionResId);
        showTranscription(question);
        flagWaitingForTemiToFinishSpeaking = true;
        temi.speak(TtsRequest.create(question, false));
    }

    private void handleSequenceAlexa() {
        switch (currentSequenceStep) {
            case 0:
                // Initial command
                String alexa_command_init = "Okay. Ich gehe zu Alexa um ihr zu sagen, dass sie den Rolläden schließen soll.";
                //showTranscription(alexa_command_init);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(alexa_command_init, false));
                break;
            case 1:
                // Go to Alexa location
                flagWaitingForTemiToArrive = true;
                temi.goTo("alexa");
                break;
            case 2:
                // Speak "Alexa"
                //showTranscription("Alexa");
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create("Alexa", false));
                break;
            case 3:
                // Final command after a delay
                waitHandler.postDelayed(() -> {
                    //showTranscription("Bitte schließe die Rolläden");
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create("Bitte schließe die Rolläden", false));
                    // oder temi.speak(TtsRequest.create("Wohnzimmer an", false));
                }, 1000);
                currentSequenceStep = 0;
                currentSequence = null;
                break;
            default:
                showTranscription("Error: Default in Sequenz Alexa!");
                break;
        }
    }

    private void handleAALSequence() {
        switch (currentSequenceStep) {
            case 0:
                flagWaitingForTemiToArrive = true;
                temi.goTo("tür");
                break;
            case 1:
                // At the entrance
                String entranceWelcome = getString(R.string.aal_welcome);
                //showTranscription(entranceWelcome);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(entranceWelcome, false));
                break;
            case 2:
                String introduction = getString(R.string.aal_intro);
                //showTranscription(introduction);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(introduction, false));
                break;
            case 3:
                // Move to the kitchen
                flagWaitingForTemiToArrive = true;
                temi.goTo("küche");
                break;
            case 4:
                // In the kitchen
                String kitchenIntro = getString(R.string.aal_kitchen_intro);
                //showTranscription(kitchenIntro);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(kitchenIntro, false));
                break;
            case 5:
                String kitchenDetails1 = getString(R.string.aal_kitchen_details1);
                //showTranscription(kitchenDetails1);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(kitchenDetails1, false));
                break;
            case 6:
                String kitchenDetails2 = getString(R.string.aal_kitchen_details2);
                //showTranscription(kitchenDetails2);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(kitchenDetails2, false));
                break;
            case 7:
                // Move to the sink
                flagWaitingForTemiToArrive = true;
                temi.goTo("waschbecken");
                break;
            case 8:
                String sinkDetails = getString(R.string.aal_sink_details);
                //showTranscription(sinkDetails);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(sinkDetails, false));
                break;
            case 9:
                String worktopDetails = getString(R.string.aal_worktop_details);
                //showTranscription(worktopDetails);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(worktopDetails, false));
                break;
            case 10:
                String kitchenSummary = getString(R.string.aal_kitchen_summary);
                //showTranscription(kitchenSummary);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(kitchenSummary, false));
                break;
            case 11:
                // Move to the living room
                flagWaitingForTemiToArrive = true;
                temi.goTo("wohnzimmer");
                break;
            case 12:
                // In the living room
                String livingRoomIntro = getString(R.string.aal_livingroom_intro);
                //showTranscription(livingRoomIntro);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(livingRoomIntro, false));
                break;
            case 13:
                String livingRoomDetails1 = getString(R.string.aal_livingroom_details1);
                //showTranscription(livingRoomDetails1);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(livingRoomDetails1, false));
                break;
            case 14:
                String livingRoomDetails2 = getString(R.string.aal_livingroom_details2);
                //showTranscription(livingRoomDetails2);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(livingRoomDetails2, false));
                break;
            case 15:
                String farewell = getString(R.string.aal_farewell);
                //showTranscription(farewell);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(farewell, false));
                // Sequence completed
                currentSequenceStep = 0;
                currentSequence = null;
                break;
            default:
                showTranscription("Error: Default in Sequenz AAL!");
                break;
        }
    }

    private void handleSequenceBrainGame() {
        showTranscription("DEGBUG: in handleBrainGame: Schritt = " + currentSequenceStep);

        switch (currentSequenceStep) {
            case 0:
                showTranscription("DEGBUG: Start Sequence: handleSequenceBrainGame");
                flagRepeatSentenceRequest = false;
                String introducingBrainGame = getString(R.string.bg_introduction_short);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(introducingBrainGame, false));
                // step/case incremeted by the statusChange of tts
                break;

            case 1: case 3: case 5: case 7:
            case 9: case 11: case 14:

                flagWaitingForUserResponse = true;
                temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
                findViewById(R.id.isRecordingImg).setVisibility(View.VISIBLE);

                // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 2

                currentSequenceStep++;
                break;

            case 2:

                if(((myAsrResultString.contains("ja") | myAsrResultString.contains("bereit")) & !myAsrResultString.contains("nicht")) | flagRepeatSentenceRequest)
                {
                    flagRepeatSentenceRequest = false;
                    String sentence1 = getString(R.string.bg_letsgo) + getString(R.string.bg_sentence1);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(sentence1, false));
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    String repeatIntdroduction = getString(R.string.bg_iRepeat) + getString(R.string.bg_introduction);
                    currentSequenceStep = 0;
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(repeatIntdroduction, false));
                    // step/case incremeted by the statusChange of tts > therefore continues at case 1
                }
                flagRepeatSentenceRequest = false;  // just to be save
                break;

            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 4:
                showTranscription("DEGBUG: myAsrResultString = " + myAsrResultString);
                if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                    currentSequenceStep -= 2;
                    flagRepeatSentenceRequest = true;
                    chooseCurrentSequence();
                    break;
                }
                if(((myAsrResultString.contains("taube") || myAsrResultString.contains("spatz")) && myAsrResultString.contains("dach")) | flagRepeatSentenceRequest)
                {
                    String nextSentence = getString(R.string.bg_answer_correct) + getString(R.string.bg_sentence2);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    String nextSentence = "";
                    if(checkForDontKnowAnswer())
                    {
                        nextSentence = getString(R.string.bg_dont_know_answer);
                    }
                    else
                    {
                        nextSentence = getString(R.string.bg_sorry_wrong);
                    }
                    nextSentence += getString(R.string.bg_sentence1_correct) + getString(R.string.bg_sentence2);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                flagRepeatSentenceRequest = false;
                break;
            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 6:
                showTranscription("DEGBUG: InHandle myAsrResultString = " + myAsrResultString);
                if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                    currentSequenceStep -= 2;
                    flagRepeatSentenceRequest = true;
                    chooseCurrentSequence();
                    break;
                }

                if((myAsrResultString.contains("macht") && myAsrResultString.contains("sommer")) | flagRepeatSentenceRequest)
                {
                    String nextSentence = getString(R.string.bg_answer_correct) + getString(R.string.bg_sentence3);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    String nextSentence = "";
                    if(checkForDontKnowAnswer())
                    {
                        nextSentence = getString(R.string.bg_dont_know_answer);
                    }
                    else
                    {
                        nextSentence = getString(R.string.bg_sorry_wrong);
                    }
                    nextSentence += "Test " + getString(R.string.bg_sentence2_correct) + getString(R.string.bg_sentence3);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                flagRepeatSentenceRequest = false;
                break;
            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 8:
                showTranscription("DEGBUG: myAsrResultString = " + myAsrResultString);
                if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                    currentSequenceStep -= 2;
                    flagRepeatSentenceRequest = true;
                    chooseCurrentSequence();
                    break;
                }

                if((myAsrResultString.contains("torheit") && myAsrResultString.contains("nicht")) | flagRepeatSentenceRequest)
                {
                    String nextSentence = getString(R.string.bg_answer_correct) + getString(R.string.bg_sentence4);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    String nextSentence = "";
                    if(checkForDontKnowAnswer())
                    {
                        nextSentence = getString(R.string.bg_dont_know_answer);
                    }
                    else
                    {
                        nextSentence = getString(R.string.bg_sorry_wrong);
                    }
                    nextSentence += getString(R.string.bg_sentence3_correct) + getString(R.string.bg_sentence4);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                flagRepeatSentenceRequest = false;
                break;
            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 10:
                showTranscription("DEGBUG: myAsrResultString = " + myAsrResultString);
                if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                    currentSequenceStep -= 2;
                    flagRepeatSentenceRequest = true;
                    chooseCurrentSequence();
                    break;
                }

                if((myAsrResultString.contains("wird") && myAsrResultString.contains("kalt")) | flagRepeatSentenceRequest)
                {
                    String nextSentence = getString(R.string.bg_answer_correct) + getString(R.string.bg_sentence5);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    String nextSentence = "";
                    if(checkForDontKnowAnswer())
                    {
                        nextSentence = getString(R.string.bg_dont_know_answer);
                    }
                    else
                    {
                        nextSentence = getString(R.string.bg_sorry_wrong);
                    }
                    nextSentence += getString(R.string.bg_sentence4_correct) + getString(R.string.bg_sentence5);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                flagRepeatSentenceRequest = false;
                break;
            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 12:
                showTranscription("DEGBUG: myAsrResultString = " + myAsrResultString);
                if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                    currentSequenceStep -= 2;
                    flagRepeatSentenceRequest = true;
                    chooseCurrentSequence();
                    break;
                }

                if((myAsrResultString.contains("schnaps") | flagRepeatSentenceRequest))
                {
                    String nextSentence = getString(R.string.bg_answer_correct);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    String nextSentence = "";
                    if(checkForDontKnowAnswer())
                    {
                        nextSentence = getString(R.string.bg_dont_know_answer);
                    }
                    else
                    {
                        nextSentence = getString(R.string.bg_sorry_wrong);
                    }
                    nextSentence += getString(R.string.bg_sentence5_correct);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                flagRepeatSentenceRequest = false;
                break;

            case 13:
                String finish = getString(R.string.bg_finish_question);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(finish, false));
                break;
            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 15:
                showTranscription("DEGBUG: myAsrResultString = " + myAsrResultString);
                if(myAsrResultString.contains("ja"))
                {
                    String nextSentence = getString(R.string.bg_finish_answer_positive);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    String nextSentence = getString(R.string.bg_finish_answer_negative);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(nextSentence, false));
                    // step/case incremeted by the statusChange of tts
                }
                break;

            case 16:
                // Sequence-End
                flagRepeatSentenceRequest = false;
                currentSequenceStep = 0;

            default:
                showTranscription("DEGBUG: default in handleSequenceBrainGame !");
                flagRepeatSentenceRequest = false;
                break;
        }
    }

    // for handleSequenceBrainGame: func to check if the user didnt understand the question/sentence and wants it to be repeated
    private boolean checkRepeatRequest()
    {
        return myAsrResultString.contains("wiederhole") | myAsrResultString.contains("noch mal") | myAsrResultString.contains("nicht verstanden");
    }
    private boolean checkForDontKnowAnswer()
    {
        return    myAsrResultString.contains("weiß ich nicht")
                | myAsrResultString.contains("kenne ich nicht")
                | myAsrResultString.contains("unbekannt")
                | myAsrResultString.contains("keine ahnung");
    }

    public void relocateTemi()
    {   /* //Use similar structure as fillDropdownMenu
        boolean found = false;
        String lowerCaseAsrResult = myAsrResultString.toLowerCase();

        if(myAsrResultString.contains("basisstation")) {
            found = true;
            destination = "home base";
            confirm();
        }

        for (String location : validLocations) {
            if (lowerCaseAsrResult.contains(location)) {
                destination = location;
                found = true;
                confirm();
                break;
            }
        }

        if (!found) {
            String unknownPlace = "Entschuldige diesen Ort kenne ich leider nicht.";
            showTranscription(unknownPlace);
            temi.speak(TtsRequest.create(unknownPlace, false));
        }*/

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
        else if(myAsrResultString.contains("büro"))
        {
            destination = "büro";
            confirm();
        }
        else if(myAsrResultString.contains("esszimmer"))
        {
            destination = "esszimmer";
            confirm();
        }
        else if(myAsrResultString.contains("waschbecken"))
        {
            destination = "waschbecken";
            confirm();
        }
        else if(myAsrResultString.contains("alexa"))
        {
            destination = "alexa";
            confirm();
        }
        else
        {   // default
            String unknownPlace = "Entschuldige diesen Ort kenne ich leider nicht.";
            showTranscription(unknownPlace);
            temi.speak(TtsRequest.create(unknownPlace, false));
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
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
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
            // timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            //String logEntry = timestamp + " - " + logMessage;
            try (FileWriter writer = new FileWriter(logFile, true)) {
                writer.append(logMessage).append("\n");
            } catch (IOException e) {
                Toast.makeText(getApplicationContext(), "Error logging message", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(getApplicationContext(), "Log file is not created", Toast.LENGTH_SHORT).show();
        }
    }

    private void followMe() {
        Log.i(TAG, "Follow the user");
        temi.beWithMe();
    }

    @Override
    public void onRobotReady(boolean b) {
        if (temi.isReady()) {                                     // true instead of 'isReady'????
            Log.i(TAG, "Robot is ready");
            temi.hideTopBar(); // hide temi's top action bar when skill is active
        }
    }

    /* Dark Mode */
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
    private void updateThemeButtonText() {
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        Button theme_button = findViewById(R.id.themeButton);
        if (currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
            theme_button.setText(R.string.dark_theme); // Show "Dark Mode"
        } else {
            theme_button.setText(R.string.light_theme); // Show "Light Mode"
        }
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
        locationList.addAll(validLocations);

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

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
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
        //hideModeElements();
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

    public void addLocations(String[] locations) {
        validLocations.addAll(Arrays.asList(locations));

        // Remove duplicates
        Set<String> set = new HashSet<>(validLocations);
        validLocations = new ArrayList<>(set);

        String floorx = "";

        for (String element : validLocations) {
            if (element.startsWith("floor")) {
                floorx = element;
            }
        }

        validLocations.remove(floorx);

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
            findViewById(R.id.isRecordingImg).setVisibility(View.INVISIBLE);
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

    @Override
    protected void onStop() {
        super.onStop();

        // Remove robot event listeners
        temi.removeOnRobotReadyListener(this);
        temi.removeAsrListener(this);
        temi.removeOnConversationStatusChangedListener(this);
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
