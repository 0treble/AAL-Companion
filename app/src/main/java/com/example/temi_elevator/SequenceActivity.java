package com.example.temi_elevator;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
public class SequenceActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sequence_window);

        Button button1 = findViewById(R.id.sequence1Button);
        button1.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.GREETING));

        Button button2 = findViewById(R.id.sequence2Button);
        button2.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.SEQUENCE_REZEPT));

        Button button3 = findViewById(R.id.sequence3Button);
        button3.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.AAL_SEQUENCE));

        Button button4 = findViewById(R.id.sequence4Button);
        button4.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.SEQUENCE_BRAIN_GAME));

        Button button5 = findViewById(R.id.sequence5Button);
        button5.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.KITCHEN));

        Button button6 = findViewById(R.id.sequence6Button);
        button6.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.ALEXA_INTERACTION));

        Button button7 = findViewById(R.id.sequence7Button);
        button7.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.QUESTIONNAIRE));

        Button button8 = findViewById(R.id.sequence8Button);
        button8.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.ASSISTANCE_QUESTIONAIRE));

        Button button9 = findViewById(R.id.sequence9Button);
        button9.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.VIVI_INTERACTION));

        Button button10 = findViewById(R.id.sequence10Button);
        button10.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.SEQUENCE_MESSE1));

        Button button11 = findViewById(R.id.sequence11Button);
        button11.setOnClickListener(v -> setResultAndFinish(MainActivity.Sequence.SEQUENCE_MESSE2));

        Button backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());
    }
    private void setResultAndFinish(MainActivity.Sequence sequenceType) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("SEQUENCE_TYPE", sequenceType.name());
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}