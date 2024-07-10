package com.example.temi_elevator;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
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
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import android.graphics.drawable.Drawable;

import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.SttLanguage;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.UserInfo;
import com.robotemi.sdk.constants.Gender;
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
import com.robotemi.sdk.voice.model.TtsVoice;

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
        OnTelepresenceEventChangedListener, Robot.WakeupWordListener {

    // Member variables
    private final String TAG = "MainActivity";
    private UserInfo contact;
    private String newDest = ""; // is used to save a destination from MQTT
    private final Robot temi = Robot.getInstance();
    private final List<UserInfo> validContacts = new ArrayList<>();
    private boolean waitingForFinish = false;
    private final Handler waitHandler = new Handler();
    private TextView transcription;
    private ScrollView scrollView;
    private ActivityResultLauncher<Intent> sequenceResultLauncher;
    private ActivityResultLauncher<Intent> settingsResultLauncher;
    private ExecutorService myExecutorService;
    private String myAsrResultString = "";

    private boolean DEBUG = false;

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
    }

    enum Sequence {UNDEFINED, GREETING, SEQUENCE_ALEXA, AAL_SEQUENCE, SEQUENCE_BRAIN_GAME, QUESTIONNAIRE,
        KITCHEN, ASSISTANCE_QUESTIONAIRE, ALEXA_INTERACTION, VIVI_INTERACTION}
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
        temi.addWakeupWordListener(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        temi.requestToBeKioskApp();

        if (!temi.isSelectedKioskApp()){
            temi.setKioskModeOn(true);
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupVideoCall();

        myExecutorService = Executors.newSingleThreadExecutor();

        init(savedInstanceState);

        createLogFile();

    }

    public void onWakeupWord(String wakeupWord, int direction)
    {
        //showFace(R.drawable.smileblink_crop);
        if(wakeupWord.equals("Hey Auto"))
        {
            temi.speak(TtsRequest.create("Neuer Aufrufname detektiert"));
        }
    }

    private void init(Bundle savedInstanceState) {
        startTranscription();
        setupThemeButton();
        initCommandsMap();
        showFace(R.drawable.sleeping_crop);

        showTranscription(getString(R.string.survey_title_for_transcript));

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
            showFace(R.drawable.sleeping_crop);
        });

        findViewById(R.id.quitButton).setOnClickListener(view -> {
            showTranscription(getString(R.string.survey_outro_for_transcript));
            logToFile(transcription.getText().toString());
            if (temi.isSelectedKioskApp()){
                temi.setKioskModeOn(false);
            }

            temi.setGoToSpeed(SpeedLevel.SLOW);
            temi.startPage(Page.HOME);
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

        /* Settings Window Launcher*/
        findViewById(R.id.settingsButton).setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this, Settings.class);

            settingsResultLauncher.launch(intent);
        });

        settingsResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        float speed = result.getData().getFloatExtra("SPEED", 0.5f);
                        int pitch = result.getData().getIntExtra("PITCH", 0);
                        Gender gender = Gender.valueOf(result.getData().getStringExtra("GENDER"));

                        TtsVoice ttsVoice = new TtsVoice(gender, speed, pitch);

                        showTranscription("Voice set to: " + ttsVoice);

                        boolean isVoiceSet = temi.setTtsVoice(ttsVoice);
                        if (isVoiceSet) {
                            Log.i("MainActivity", "TTS voice settings updated successfully.");
                        } else {
                            Log.e("MainActivity", "Failed to update TTS voice settings.");
                        }
                    }
                }
        );

        /* Robot Face Display */
        findViewById(R.id.imgOverlayButton).setOnClickListener(view ->  {
            Glide.with(this).asGif().load(R.drawable.smileblink_crop).into((android.widget.ImageView) findViewById(R.id.overlay_image));
            showFace(R.drawable.smileblink_crop);
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

    private void showFace(int gifResource) {
        Glide.with(this).asGif().load(gifResource).into((android.widget.ImageView) findViewById(R.id.overlay_image));
        findViewById(R.id.overlay_image).setVisibility(View.VISIBLE);
        findViewById(R.id.img_close_button).setVisibility(View.VISIBLE);
    }

    private AlertDialog dialog; // Declare the AlertDialog as a field to keep its state

    private void displayCommandPreview(boolean show) {

        // Only create the dialog if it hasn't been initialized
        if (dialog == null || !dialog.isShowing()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            ImageView imageView = new ImageView(this);

            // Depending on the sequence, load the corresponding image
            if (currentSequence == Sequence.VIVI_INTERACTION) {
                @SuppressLint("UseCompatLoadingForDrawables")
                Drawable drawable = getResources().getDrawable(R.drawable.vivi_commands_preview);
                imageView.setImageDrawable(drawable);
            } else if (currentSequence == Sequence.ALEXA_INTERACTION) {
                @SuppressLint("UseCompatLoadingForDrawables")
                Drawable drawable = getResources().getDrawable(R.drawable.alexa_commands_preview);
                imageView.setImageDrawable(drawable);
            }

            builder.setView(imageView); // Set the ImageView as the view for the dialog
            dialog = builder.create(); // Create the AlertDialog from the builder
        }

        // Control the display of the dialog based on the 'show' parameter
        if (show) {
            dialog.show();
        } else {
            dialog.dismiss();
        }

        // Optionally, set the dialog to dismiss when touched outside
        dialog.setCanceledOnTouchOutside(true);
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
        Log.i(TAG, "ASR Result: " + asrResult);
        myAsrResultString = asrResult;
        showTranscription("Transkribiert: " + myAsrResultString);

        analyzeVoiceCommand();
        flagWaitingForUserResponse = false; // relevant for the BrainGame when user doesnt say anything
        temi.finishConversation(); // stop ASR listener
    }

    @Override
    public void onConversationStatusChanged(int status, @NotNull String text) {
        myAsrResultString += text;
        switch (status) {
            case IDLE:
                Log.i(TAG, "Status: IDLE | Text: " + myAsrResultString);
                //showTranscription("Status: IDLE | Text: " + myAsrResultString);
                //showFace(R.drawable.sleeping_crop);
                findViewById(R.id.isRecordingImg).setVisibility(View.INVISIBLE);
                break;
            case LISTENING:
                Log.i(TAG, "Status: LISTENING | Text: " + myAsrResultString);
                //showTranscription("Status: LISTENING | Text: " + myAsrResultString);
                showFace(R.drawable.smileblink_crop);
                findViewById(R.id.isRecordingImg).setVisibility(View.VISIBLE);
                break;
            case THINKING:
                Log.i(TAG, "Status: THINKING | Text: " + myAsrResultString);
                //showTranscription("Status: THINKING | Text: " + myAsrResultString);
                showFace(R.drawable.smileblink_crop);
                break;
            case SPEAKING:
                Log.i(TAG, "Status: SPEAKING | Text: " + myAsrResultString);
                //showTranscription("Status: SPEAKING | Text: " + myAsrResultString);
                showFace(R.drawable.happyblink_crop);
                break;
            default:
                Log.i(TAG, "Status: UNKNOWN | Text: " + myAsrResultString);
                //showTranscription("Status: UNKNOWN | Text: " + myAsrResultString);
                showFace(R.drawable.sleeping_crop);
                break;
        }


        if(flagWaitingForUserResponse & status == IDLE)
        {
            flagWaitingForUserResponse = false;
            showTranscription("System: #### Keine Benutzerantwort detektiert ###");

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
        commandsMap.put(new String[]{"kontaktperson anrufen", "anruf starten", "ruf an"}, command -> findViewById(R.id.confirmCallButton).performClick());
        commandsMap.put(new String[]{"transkription starten", "aufnahme beginnen", "aufnahme starten"}, command -> findViewById(R.id.listen).performClick());
        commandsMap.put(new String[]{"transkription beenden", "aufnahme beenden"}, command -> findViewById(R.id.endTranscription).performClick());
        commandsMap.put(new String[]{"applikation beenden", "app beenden"}, command -> findViewById(R.id.quitButton).performClick());
        commandsMap.put(new String[]{"dunkler modus", "heller modus", "keller modus"}, command -> findViewById(R.id.themeButton).performClick());
        commandsMap.put(new String[]{"stopp","stop","abbrechen","abbruch" }, command -> stopCurrentSequence());
        commandsMap.put(new String[]{"gesprächsmodus", "dialogmodus", "gesprächs modus"}, command -> conversationMode = !conversationMode);
        commandsMap.put(new String[]{"erinnere mich", "erinnerung setzen", "setze eine erinnerung"}, command -> setReminder());
        commandsMap.put(new String[]{"wiederholen", "erneut", "wiederhole", "noch mal", "nicht verstanden"}, command -> findViewById(R.id.repeatButton).performClick());
        /* Sequences */
        commandsMap.put(new String[]{"gäste begrüßen", "begrüßung starten", "begrüßung", "willkommenssequenz starten", "sequenz 1 starten",
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
        commandsMap.put(new String[]{"aal sequenz", "sequenz 2 starten", "rundgang starten", "sequenz aal"}, command -> {
            currentSequence = Sequence.AAL_SEQUENCE;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });

        commandsMap.put(new String[]{"gedächtnisspiel", "denksport", "sequenz gedächtnisspiel"}, command -> {
            currentSequence = Sequence.SEQUENCE_BRAIN_GAME;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });
        commandsMap.put(new String[]{"fragebogen starten", "fragebogensequenz starten", "fragebogen beginnen", "sequenz fragebogen" }, command -> {
            currentSequence = Sequence.QUESTIONNAIRE;
            currentSequenceStep = 1;
            chooseCurrentSequence();
        });
        commandsMap.put(new String[]{"führung durch die küche starten", "sequenz küche" }, command -> {
            currentSequence = Sequence.KITCHEN;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });
        commandsMap.put(new String[]{"unterstützungsbedarf", "sequenz unterstützungsbedarf" }, command -> {
            currentSequence = Sequence.ASSISTANCE_QUESTIONAIRE;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });
        commandsMap.put(new String[]{"alexa interaktion", "sequenz alexa" }, command -> {
            currentSequence = Sequence.ALEXA_INTERACTION;
            currentSequenceStep = 0;
            chooseCurrentSequence();
        });
        commandsMap.put(new String[]{"sequenz vivi", "sequenz video", "sequenz vivaicare", "sequenz stationäres assistenzsystem" }, command -> {
            currentSequence = Sequence.VIVI_INTERACTION;
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

        if (!commandFound & currentSequence == Sequence.UNDEFINED) {
            handleUndefinedCommand();
        } else {
            chooseCurrentSequence();
        }
    }

    public void stopCurrentSequence()
    {
        displayCommandPreview(false);
        showFace(R.drawable.sleeping_crop);
        temi.stopMovement();
        temi.cancelAllTtsRequests();
        currentSequenceStep = 0;
        currentSequence = Sequence.UNDEFINED;
    }

    //Handles result from voice commands and SequenceActivity.java click result
    public void chooseCurrentSequence()
    {
        switch(currentSequence)
        {
            case GREETING:
                scrollToBottom();
                showFace(R.drawable.happyblink_crop);
                handleSequenceGreeting();
                break;
            case SEQUENCE_ALEXA:
                scrollToBottom();
                showFace(R.drawable.happyblink_crop);
                handleSequenceAlexa();
                break;
            case AAL_SEQUENCE:
                scrollToBottom();
                showFace(R.drawable.happyblink_crop);
                handleAALSequence();
                break;
            case SEQUENCE_BRAIN_GAME:
                //scrollToBottom();
                showFace(R.drawable.happyblink_crop);
                handleSequenceBrainGame();
                break;
            case QUESTIONNAIRE:
                scrollToBottom();
                showFace(R.drawable.happyblink_crop);
                if(currentSequenceStep == 0){ currentSequenceStep =1;}
                handleSequenceQuestionnaire();
                break;
            case KITCHEN:
                scrollToBottom();
                showFace(R.drawable.happyblink_crop);
                handleSequenceKitchen();
                break;
            case ASSISTANCE_QUESTIONAIRE:
                scrollToBottom();
                showFace(R.drawable.happyblink_crop);
                handleSequenceAssistanceQuestionaire();
                break;
            case ALEXA_INTERACTION:
                scrollToBottom();
                showFace(R.drawable.happyblink_crop);
                handleSequenceAlexaInteraction();
                break;
            case VIVI_INTERACTION:
                scrollToBottom();
                showFace(R.drawable.happyblink_crop);
                handleSequenceViviInteraction();
                break;
            default:
                showTranscription("Error: No sequence chosen.");
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
        switch (currentSequenceStep)
        {
            case 0:
                showTranscription("\n-------------------------------\nSystem: Start Sequenz Begrüßung\n");
                flagWaitingForTemiToArrive = true;      /// SUPER IMPORTANT BEFORE EVERY GO-TO-COMMAND!!!!
                temi.goTo("tür");               /// so that the next task is started AFTER arriving at destination
                // step increment done by the onGoToStatusListener()
                break;
            case 1:
                nextSentence = getString(R.string.greeting_welcome_door);
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;  /// SUPER IMPORTANT BEFORE EVERY speaking-COMMAND!!!!
                temi.speak(TtsRequest.create(nextSentence, false));
                // step increment done by the onTtsStatusChanged() when temi finished speaking previous string
                /// so that the next task is started AFTER arriving at destination

                break;
            case 2:
                flagWaitingForTemiToArrive = true;
                temi.goTo("wohnzimmer");
                // step increment done by the onGoToStatusListener
                break;
            case 3:
                nextSentence = getString(R.string.greeting_livingroom_have_a_seat);
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextSentence, false));
                // step increment done by the onTtsStatusChanged() when temi finished speaking previous string
                break;
            case 4:
                if(myAsrResultString.contains("stell dich vor") | myAsrResultString.contains("stelle dich vor") | myAsrResultString.contains("beginne die untersuchung") | myAsrResultString.contains("starte die untersuchung"))
                {
                    flagWaitingForTemiToArrive = true;
                    temi.goTo("wohnzimmersitzgruppe");
                    // step increment done by the onGoToStatusListener
                }

                break;
            case 5:
                nextSentence = getString(R.string.greeting_introduction);
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextSentence, false));
                break;
            case 6:
                showTranscription("\nSystem: Ende Sequenz Begrüßung\n-------------------------------\n");
                currentSequenceStep = 0;
                currentSequence = Sequence.KITCHEN;
                break;
            default:
                currentSequenceStep = 0;
                showTranscription("Error: Default in Sequenz GREETING!");
                break;
        }
    }

    private void handleSequenceBrainGame() {
        if(DEBUG) showTranscription("DEGBUG: in handleBrainGame: Schritt = " + currentSequenceStep);



        String nextSentence = "";

        switch (currentSequenceStep) {
            case 0:
                showTranscription("\n-------------------------------\nSystem: Start Sequenz Gedächtnisspiel\n");
                if(DEBUG) showTranscription("DEGBUG: Start Sequence: handleSequenceBrainGame");
                flagRepeatSentenceRequest = false;
                String introducingBrainGame = getString(R.string.bg_introduction);
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(introducingBrainGame, false));
                // step/case incremeted by the statusChange of tts
                break;

            case 1: case 3: case 5: case 7:
            case 9: case 11: case 14:

                temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
                findViewById(R.id.isRecordingImg).setVisibility(View.VISIBLE);
                flagWaitingForUserResponse = true;


                // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 2

                currentSequenceStep++;
                break;

            case 2:

                if(((myAsrResultString.contains("ja") | myAsrResultString.contains("bereit")) & !myAsrResultString.contains("nicht")) | flagRepeatSentenceRequest | checkForContinueNextSequence())
                {
                    flagRepeatSentenceRequest = false;
                    String sentence1 = getString(R.string.bg_letsgo) + getString(R.string.bg_sentence1);
                    showTranscription("Temi: " + sentence1);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(sentence1, false));
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    String repeatIntdroduction = getString(R.string.bg_iRepeat) + getString(R.string.bg_introduction);
                    currentSequenceStep = 0;
                    showTranscription("Temi: " + repeatIntdroduction);
                    flagWaitingForTemiToFinishSpeaking = true;
                    temi.speak(TtsRequest.create(repeatIntdroduction, false));
                    // step/case incremeted by the statusChange of tts > therefore continues at case 1
                }
                flagRepeatSentenceRequest = false;  // just to be save
                break;

            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 4:
                if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                    currentSequenceStep -= 2;
                    flagRepeatSentenceRequest = true;
                    myAsrResultString = "";
                    chooseCurrentSequence();
                    break;
                }
                if(((myAsrResultString.contains("taube") || myAsrResultString.contains("spatz")) && myAsrResultString.contains("dach")) | flagRepeatSentenceRequest)
                {
                    if(!flagRepeatSentenceRequest)  // answer therefore was correct
                    {
                        nextSentence += getString(R.string.bg_answer_correct);
                    }
                    nextSentence += getString(R.string.bg_sentence2);
                }
                else
                {
                    if(checkForDontKnowAnswer())
                    {
                        nextSentence = getString(R.string.bg_dont_know_answer);
                    }
                    else
                    {
                        nextSentence = getString(R.string.bg_sorry_wrong);
                    }
                    nextSentence += getString(R.string.bg_sentence1_correct) + getString(R.string.bg_sentence2);
                    // step/case incremeted by the statusChange of tts
                }
                flagRepeatSentenceRequest = false;
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextSentence, false));
                // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 6
                break;
            case 6:
                if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                    currentSequenceStep -= 2;
                    flagRepeatSentenceRequest = true;
                    myAsrResultString = "";
                    chooseCurrentSequence();
                    break;
                }

                if((myAsrResultString.contains("macht") && myAsrResultString.contains("sommer")) | flagRepeatSentenceRequest)
                {
                    if(!flagRepeatSentenceRequest)  // answer therefore was correct
                    {
                        nextSentence += getString(R.string.bg_answer_correct);
                    }
                    nextSentence += getString(R.string.bg_sentence3);
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    if(checkForDontKnowAnswer())
                    {
                        nextSentence = getString(R.string.bg_dont_know_answer);
                    }
                    else
                    {
                        nextSentence = getString(R.string.bg_sorry_wrong);
                    }
                    nextSentence += getString(R.string.bg_sentence2_correct) + getString(R.string.bg_sentence3);
                    // step/case incremeted by the statusChange of tts
                }
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextSentence, false));
                flagRepeatSentenceRequest = false;
                break;
            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 8:
                if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                    currentSequenceStep -= 2;
                    flagRepeatSentenceRequest = true;
                    myAsrResultString = "";
                    chooseCurrentSequence();
                    break;
                }

                if((myAsrResultString.contains("torheit") && myAsrResultString.contains("nicht")) | flagRepeatSentenceRequest)
                {
                    if(!flagRepeatSentenceRequest)  // answer therefore was correct
                    {
                        nextSentence += getString(R.string.bg_answer_correct);
                    }
                    nextSentence += getString(R.string.bg_sentence4);
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    if(checkForDontKnowAnswer())
                    {
                        nextSentence = getString(R.string.bg_dont_know_answer);
                    }
                    else
                    {
                        nextSentence = getString(R.string.bg_sorry_wrong);
                    }
                    nextSentence += getString(R.string.bg_sentence3_correct) + getString(R.string.bg_sentence4);
                    // step/case incremeted by the statusChange of tts
                }
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextSentence, false));
                flagRepeatSentenceRequest = false;
                break;
            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 10:
                if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                    currentSequenceStep -= 2;
                    flagRepeatSentenceRequest = true;
                    myAsrResultString = "";
                    chooseCurrentSequence();
                    break;
                }

                if((myAsrResultString.contains("wird") && myAsrResultString.contains("kalt")) | flagRepeatSentenceRequest)
                {
                    if(!flagRepeatSentenceRequest)  // answer therefore was correct
                    {
                        nextSentence += getString(R.string.bg_answer_correct);
                    }
                    nextSentence += getString(R.string.bg_sentence5);
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    if(checkForDontKnowAnswer())
                    {
                        nextSentence = getString(R.string.bg_dont_know_answer);
                    }
                    else
                    {
                        nextSentence = getString(R.string.bg_sorry_wrong);
                    }
                    nextSentence += getString(R.string.bg_sentence4_correct) + getString(R.string.bg_sentence5);
                    // step/case incremeted by the statusChange of tts
                }
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextSentence, false));
                flagRepeatSentenceRequest = false;
                break;
            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 12:
                if(!flagRepeatSentenceRequest & checkRepeatRequest()) {
                    currentSequenceStep -= 2;
                    flagRepeatSentenceRequest = true;
                    myAsrResultString = "";
                    chooseCurrentSequence();
                    break;
                }

                if((myAsrResultString.contains("schnaps") | flagRepeatSentenceRequest))
                {
                    if(!flagRepeatSentenceRequest)  // answer therefore was correct
                    {
                        nextSentence += getString(R.string.bg_answer_correct);
                    }
                    //nextSentence += getString(R.string.bg_answer_correct);
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    if(checkForDontKnowAnswer())
                    {
                        nextSentence = getString(R.string.bg_dont_know_answer);
                    }
                    else
                    {
                        nextSentence = getString(R.string.bg_sorry_wrong);
                    }
                    nextSentence += getString(R.string.bg_sentence5_correct);
                    // step/case incremeted by the statusChange of tts
                }
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextSentence, false));
                flagRepeatSentenceRequest = false;
                break;

            case 13:
                String finish = getString(R.string.bg_finish_question);
                showTranscription("Temi: " + finish);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(finish, false));
                break;
            // when answer is transcripted - onAsrResult calls handle-func again to continue with step/case 4

            case 15:
                if(myAsrResultString.contains("ja"))
                {
                    nextSentence = getString(R.string.bg_finish_answer_positive);
                    // step/case incremeted by the statusChange of tts
                }
                else
                {
                    nextSentence = getString(R.string.bg_finish_answer_negative);
                    // step/case incremeted by the statusChange of tts
                }
                nextSentence += getString(R.string.vi_transition);
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextSentence, false));
                break;

            case 16:
                // Sequence-End
                showTranscription("\nSystem: Ende Sequenz Gedächtnisspiel\n-------------------------------\n");

                flagRepeatSentenceRequest = false;
                currentSequence = Sequence.VIVI_INTERACTION;
                currentSequenceStep = 0;
                break;

            default:
                showTranscription("ERR: default in handleSequenceBrainGame !");
                flagRepeatSentenceRequest = false;
                break;
        }
    }
    private void handleSequenceViviInteraction()
    {
        String nextSentence;
        switch(currentSequenceStep)
        {
            case 0:
                showTranscription("\n-------------------------------\nSystem: Start Sequenz Vivi Interaktion\n");
                nextSentence = getString(R.string.vi_vivi_introduction);
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextSentence));
                // now user is talking to Vivi...
                displayCommandPreview(true);
                //showFace(R.drawable.vivi_commands_preview);
                break;
            case 1:
                temi.goTo("wohnzimmer");
                currentSequenceStep++;
                break;
            case 2:
                if(checkForContinueNextSequence())
                {
                    nextSentence = getString(R.string.vi_finised) + getString(R.string.survey_announcement);
                    flagWaitingForTemiToFinishSpeaking = true;
                }
                else
                {
                    nextSentence = getString(R.string.vi_not_understood);
                }
                showTranscription("Temi: " + nextSentence);
                // flag is set in if-statement
                temi.speak(TtsRequest.create(nextSentence));
                break;
            case 3:
                displayCommandPreview(false);
                //showFace(R.drawable.happyblink_crop);
                showTranscription("\nSystem: Ende Sequenz Vivi Interaktion\n-------------------------------\n");
                currentSequence = Sequence.QUESTIONNAIRE;
                currentSequenceStep = 0;
                flagWaitingForTemiToArrive = true;
                temi.goTo("wohnzimmersitzgruppe");
                break;
            default:
                //showTranscription("Debug: in default der handleSequenceViviInteraction!");
                break;
        }
    }

    public void handleSequenceQuestionnaire()
    {
        String nextSentence = "";
        switch (currentSequenceStep)
        {
            // no case 0 because last step is goTo() with waitingFlag in previous sequence
            //case 0: // when sequence is manually called...
                //currentSequenceStep = 1;
                //showTranscription("DEBUG: in QUESTIONAIRE-STEP = 0\n");
            case 1:
                showTranscription("\n-------------------------------\nSystem: Start Sequenz Fragebogen\n");
                //("DEBUG: in QUESTIONAIRE-STEP = 1\n");
                String questions_intro = getString(R.string.survey_questions_intro) + getString(R.string.survey_letsgo);
                showTranscription("Temi: " + questions_intro);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(questions_intro, false));
                break;
            case 2:
                askSurveyQuestion(R.string.survey_question_1);
                break;
            case 3: case 5: case 7: case 9: case 11: case 13: case 15:
            case 17: case 19: case 21: case 23: case 25: case 27: case 29:
            flagWaitingForUserResponse = true;
            temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
            findViewById(R.id.isRecordingImg).setVisibility(View.VISIBLE);
            currentSequenceStep++;
            break;
            case 4:
                askSurveyQuestion(R.string.survey_question_2);
                break;
            case 6:
                askSurveyQuestion(R.string.survey_question_3);
                break;
            case 8:
                askSurveyQuestion(R.string.survey_question_4);
                break;
            case 10:
                askSurveyQuestion(R.string.survey_question_5);
                break;
            case 12:
                askSurveyQuestion(R.string.survey_question_6);
                break;
            case 14:
                askSurveyQuestion(R.string.survey_question_7);
                break;
            case 16:
                askSurveyQuestion(R.string.survey_question_8);
                break;
            case 18:
                askSurveyQuestion(R.string.survey_question_9);
                break;
            case 20:
                askSurveyQuestion(R.string.survey_question_10);
                break;
            case 22:
                askSurveyQuestion(R.string.survey_question_11);
                break;
            case 24:
                askSurveyQuestion(R.string.survey_question_12);
                break;
            case 26:
                askSurveyQuestion(R.string.survey_question_13);
                break;
            case 28:
                String suggestions = getString(R.string.survey_suggestions);
                showTranscription("Temi: " + suggestions);

                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(suggestions, false));
                break;
            case 30: // End of sequence
                String goodbye_string = getString(R.string.survey_goodbye_string);
                showTranscription("Temi: " + goodbye_string);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(goodbye_string, false));

            case 31:
                showTranscription("\nSystem: Ende Sequenz Fragebogen\n-------------------------------\n");
                currentSequenceStep = 0;
                currentSequence = Sequence.UNDEFINED;
                break;
            default:
                showTranscription("Error: Default in Sequenz GREETING!");
                break;
        }
    }

    private void askSurveyQuestion(int questionResId) {
        String question = getString(questionResId);
        showTranscription("Temi: " + question);
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

    private void handleAALSequence() {  // Sequence to presesnt the AAL appartment at the Digitaltag 2024
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



    // for handleSequenceBrainGame: func to check if the user didnt understand the question/sentence and wants it to be repeated
    private boolean checkRepeatRequest()
    {
        return myAsrResultString.contains("wiederhole") | myAsrResultString.contains("noch mal") | myAsrResultString.contains("nicht verstanden");
    }
    private boolean checkForDontKnowAnswer()
    {
        return    myAsrResultString.contains("weiß ich nicht") | myAsrResultString.contains("kenne ich nicht") | myAsrResultString.contains("unbekannt") | myAsrResultString.contains("keine ahnung");
    }


    private void handleSequenceKitchen()
    {
        String nextSentence;
        switch(currentSequenceStep)
        {
            case 0:
                showTranscription("\n-------------------------------\nSystem: Start Sequenz Küchenrundgang\n");
                                                                        // this is the corresponding voice command
                if(checkForContinueNextSequence() | myAsrResultString.contains("führung") | myAsrResultString.contains("küche"))
                {
                    nextSentence = getString(R.string.kt_letsgo);
                    flagWaitingForTemiToFinishSpeaking = true;
                    // step increment done by ConversationStatusListener
                }
                else
                {
                    nextSentence = getString(R.string.insufficant_answer);
                }
                showTranscription("Temi: " + nextSentence);
                //flagWaitingForTemiToFinishSpeaking = true; // HIER NICHT!!!!
                temi.speak(TtsRequest.create(nextSentence, false));
                break;
            case 1:
                flagWaitingForTemiToArrive = true;
                temi.goTo("küche");
                // step increment done by LocationStatusListener
                break;
            case 2:
                nextSentence = getString(R.string.kt_takeDrink);
                showTranscription("Temi: " + nextSentence);
                //flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextSentence, false));
                currentSequenceStep++;
                break;
            case 3:
                if(myAsrResultString.contains("küche"))
                {
                    nextSentence = getString(R.string.aal_kitchen_intro)
                            //               + getString(R.string.aal_kitchen_details1)
                            //               + getString(R.string.aal_kitchen_details2)
                            + getString(R.string.aal_kitchen_summary);
                }
                else
                {
                    nextSentence = getString(R.string.kt_no_tour);
                }



                nextSentence += getString(R.string.aq_introduction);
                showTranscription("Temi: " + nextSentence);
                flagWaitingForTemiToFinishSpeaking = true; // HIER NICHT!!!!
                temi.speak(TtsRequest.create(nextSentence, false));
                break;
            case 4:
                showTranscription("\nSystem: Ende Sequenz Küchenrundgang\n-------------------------------\n");
                currentSequenceStep = 0;
                currentSequence = Sequence.ASSISTANCE_QUESTIONAIRE;
                break;
            default:
                showTranscription("DEGBUG: default in handleSequenceKitchen !");
                currentSequenceStep = 0;
                break;
        }
    }

    private boolean checkForContinueNextSequence()
    {
        return myAsrResultString.contains("weitermachen")
                | myAsrResultString.contains("bin bereit")
                | myAsrResultString.contains("weiter")
                | myAsrResultString.contains("was nun")
                | myAsrResultString.contains("was jetzt");
    }

    private void handleSequenceAssistanceQuestionaire()
    {
        String nextString;
        switch (currentSequenceStep)
        {
            case 0:
                showTranscription("\n-------------------------------\nSystem: Start Sequenz Unterstützungsbedarf\n");
                // called when user says "Hey Temi ich bin bereit"
                if(checkForContinueNextSequence())
                {
                    nextString = getString(R.string.aq_ans_ready)
                            + getString(R.string.aq_remind_no_hey_temi)
                            + getString(R.string.aq_question_1);
                    flagWaitingForTemiToFinishSpeaking = true;  // when finised speaking inc step
                }
                else
                {
                    nextString = getString(R.string.aq_ans_unsufficiant) + getString(R.string.aq_introduction);
                    //currentSequenceStep = 0;
                }
                showTranscription("Temi" + nextString);
                temi.speak(TtsRequest.create(nextString));
                break;
            case 1:
            case 3:
            case 5:
                temi.wakeup(Collections.singletonList(SttLanguage.SYSTEM));
                findViewById(R.id.isRecordingImg).setVisibility(View.VISIBLE);
                flagWaitingForUserResponse = true;
                currentSequenceStep++;
                break;
            case 2:
                nextString = getString(R.string.aq_question_2);
                showTranscription("Temi: " + nextString);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextString));
                break;
            case 4:
                nextString = getString(R.string.aq_question_3);
                showTranscription("Temi: " + nextString);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextString));
                break;
            case 6:
                nextString = getString(R.string.aq_thanks) + getString(R.string.ai_introduction);
                showTranscription("Temi: " + nextString);
                flagWaitingForTemiToFinishSpeaking = true;
                temi.speak(TtsRequest.create(nextString));
                break;
            case 7:
                showTranscription("\nSystem: Ende Sequenz Unterstützungsbedarf\n-------------------------------\n");
                currentSequenceStep = 0;
                currentSequence = Sequence.ALEXA_INTERACTION;
                break;
            default:
                showTranscription("DEFAULT: in der handleSequenceAssistanceQuestionaire!");
                currentSequenceStep = 0;
                break;
        }
    }


    private void handleSequenceAlexaInteraction()
    {
        //showTranscription("DEBUG: In der handleAlexaSequence");
        String nextSentence;
        switch(currentSequenceStep) {
            case 0:
                showTranscription("\n-------------------------------\nSystem: Start Sequenz Alexa Interaktion\n");
                if (checkForContinueNextSequence()) {
                    nextSentence = getString(R.string.ai_ready);
                    flagWaitingForTemiToFinishSpeaking = true;
                    // step increment done by ConversationStatusListener
                }
                else
                {
                    nextSentence = getString(R.string.ai_not_ready) + getString(R.string.ai_introduction);
                }
                showTranscription("Temi: " + nextSentence);
                //flagWaitingForTemiToFinishSpeaking = true; // HIER NICHT!!!!
                temi.speak(TtsRequest.create(nextSentence, false));
                break;
            case 1:
                flagWaitingForTemiToArrive = true;
                temi.goTo("wohnzimmersitzgruppe");
                // step increment done by LocationStatusListener
                break;
            case 2:
                nextSentence = getString(R.string.ai_ready_livingroom);
                showTranscription("Temi: " + nextSentence);
                temi.speak(TtsRequest.create(nextSentence));
                currentSequenceStep++;
                displayCommandPreview(true);
                //showFace(R.drawable.alexa_commands_preview);
                break;
            case 3:
                if (checkForContinueNextSequence() | myAsrResultString.contains("bin fertig")) {
                    nextSentence = getString(R.string.ai_finished) + getString(R.string.bg_info);
                    flagWaitingForTemiToFinishSpeaking = true;
                    // step increment done by ConversationStatusListener
                }
                else
                {
                    nextSentence = getString(R.string.ai_not_understood);
                }
                showTranscription("Temi: " + nextSentence);
                //flagWaitingForTemiToFinishSpeaking = true; // HIER NICHT!!!!
                temi.speak(TtsRequest.create(nextSentence, false));
                break;
            case 4:
                showTranscription("\nSystem: Ende Sequenz Alexa Interaktion\n-------------------------------\n");
                displayCommandPreview(false);
                //showFace(R.drawable.happyblink_crop);
                currentSequenceStep = 0;
                currentSequence = Sequence.SEQUENCE_BRAIN_GAME;
        }
    }


    public void relocateTemi()
    {
        if(myAsrResultString.contains("tür"))
        {
            temi.goTo("tür");
        }
        else if(myAsrResultString.contains("basisstation"))
        {
            temi.goTo("home base");
        }
        else if(myAsrResultString.contains("wohnzimmer"))
        {
            temi.goTo("wohnzimmer");
        }
        else if(myAsrResultString.contains("küche"))
        {
            temi.goTo("küche");
        }
        else if(myAsrResultString.contains("büro"))
        {
            temi.goTo("büro");
        }
        else if(myAsrResultString.contains("esszimmer"))
        {
            temi.goTo("esszimmer");
        }
        else if(myAsrResultString.contains("waschbecken"))
        {
            temi.goTo("waschbecken");
        }
        else if(myAsrResultString.contains("alexa"))
        {
            temi.goTo("alexa");
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

        // Add listener to check when the robot is ready
        temi.addOnRobotReadyListener(isReady -> {
            if (isReady) {
                initializeTemi();
            } else {
                // Retry after a short delay if not ready
                new Handler().postDelayed(this::retryInitialization, 1000);
            }
        });

        // Add click listener for arrived button
        Button yes_button = findViewById(R.id.confirmArrivedButton);
        yes_button.setOnClickListener(v -> arrived());

        // Add listener for dropdown menu
        Spinner spinner = findViewById(R.id.dropdownMenu);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                try {
                    String selectedName = (String) parent.getItemAtPosition(position);
                    if(DEBUG) showTranscription("Selected item: " + selectedName);

                    ContactMapWrapper wrapper = (ContactMapWrapper) parent.getTag();
                    if (wrapper != null) {
                        Map<String, UserInfo> contactMap = wrapper.getContactMap();
                        contact = contactMap.get(selectedName);
                        if (contact != null) {
                            if(DEBUG) showTranscription("Contact = " + contact.getName());
                        } else {
                            if(DEBUG) showTranscription("Selected contact not found in map");
                        }
                    } else {
                        if(DEBUG) showTranscription("Contact map wrapper is null");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error selecting contact", e);
                    if(DEBUG) showTranscription("Error selecting contact: " + e.getMessage());
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
                // Retry again after a short delay
                new Handler().postDelayed(this::retryInitialization, 1000);
            }
        });
    }

    private void initializeTemi() {
        refreshTemiUi();
        updateValidContacts(); // Ensure validContacts is updated
        fillDropdownMenu();
        temi.setHardButtonsDisabled(true);
        temi.setGoToSpeed(SpeedLevel.SLOW);
        currentSequence = Sequence.UNDEFINED;
        //showTranscription("Debug: currentSequence = UNDEFINED");


    }

    public static class ContactMapWrapper {
        private final Map<String, UserInfo> contactMap;

        public ContactMapWrapper(Map<String, UserInfo> contactMap) {
            this.contactMap = contactMap;
        }

        public Map<String, UserInfo> getContactMap() {
            return contactMap;
        }
    }

    private void updateValidContacts() {
        List<UserInfo> allContacts = temi.getAllContact();
        validContacts.clear();
        validContacts.addAll(allContacts);
    }

    private void fillDropdownMenu() {
        try {
            List<UserInfo> allContacts = temi.getAllContact();

            // Combine and remove duplicates
            Set<UserInfo> contactSet = new HashSet<>(allContacts);
            contactSet.addAll(validContacts);

            List<UserInfo> contactList = new ArrayList<>(contactSet);

            // Create a map for contact names to UserInfo objects
            Map<String, UserInfo> contactMap = new HashMap<>();
            List<String> contactNames = new ArrayList<>();

            for (UserInfo userInfo : contactList) {
                if (userInfo != null) {
                    contactNames.add(userInfo.getName());
                    contactMap.put(userInfo.getName(), userInfo);

                    if(DEBUG) showTranscription("Contact added: " + userInfo.getName());
                } else {
                    if(DEBUG) showTranscription("Skipped null user or user with null name");
                }
            }
        /*  Contact: Mario : 638fb4837f2a4210c3313a5c89be0747 : 0
            Contact: sander991 : 5ecc126841b0af8f6cc72feadc090fa3 : 1
            Contact: Hristo : 58c5f1e537525756a295857c7bce8e91 : 1
            Contact: Chris : 17b6a96e6079f842f6e1684a15a7c0cc : 0
            Contact: eric : 4730f86644ae309b1d097002ff075da3 : 0
            Contact: Orlando Gtz : 2378ee1e0716e6cf4e5f2f0ed10b2a44 : 1 */

            // Log contacts for debugging
            if(DEBUG) showTranscription("Total contacts added: " + contactNames.size());

            // Set adapter for dropdown menu
            String[] contacts = contactNames.toArray(new String[0]);
            Spinner dropdownMenu = findViewById(R.id.dropdownMenu);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.dropdown_menu_text_view, contacts);
            adapter.setDropDownViewResource(R.layout.dropdown_menu_pick_text_view);
            dropdownMenu.setAdapter(adapter);

            // Set tag to hold the contact map
            dropdownMenu.setTag(new ContactMapWrapper(contactMap));
        } catch (Exception e) {
            Log.e(TAG, "Error filling dropdown menu", e);
            showTranscription("Error filling dropdown menu: " + e.getMessage());
        }
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
        //target.getUserId() for Hristo: "58c5f1e537525756a295857c7bce8e91"
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

    /* Videocall end */

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
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); // Night mode is not active, activate it
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO); // Night mode is active, deactivate it
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

    // tells the initial temi to reset and resets itself when the user arrives at the destination
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
/*  plan is to take this function and repurpose it for relocateTemi

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
    }*/

    public void reset() {
        temi.goTo("home base");
        this.waitingForFinish = false;
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
        temi.removeOnTelepresenceStatusChangedListener(telepresenceStatusChangedListener);
        temi.removeOnTelepresenceEventChangedListener(this);
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
