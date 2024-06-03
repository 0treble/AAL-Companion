package com.example.temi_elevator;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;
public class SequenceActivity extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sequence_window);

        Button button1 = findViewById(R.id.sequence1Button);
        button1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResultAndFinish(MainActivity.Sequence.GREETING);
            }
        });

        Button button2 = findViewById(R.id.sequence2Button);
        button2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResultAndFinish(MainActivity.Sequence.SEQUENCE_ALEXA);
            }
        });

        Button button3 = findViewById(R.id.sequence3Button);
        button3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResultAndFinish(MainActivity.Sequence.AAL_SEQUENCE);
            }
        });

        Button button4 = findViewById(R.id.sequence4Button);
        button4.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResultAndFinish(MainActivity.Sequence.SEQUENCE_BRAIN_GAME);
            }
        });

        Button backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }
    private void setResultAndFinish(MainActivity.Sequence sequenceType) {
        Intent resultIntent = new Intent();
        resultIntent.putExtra("SEQUENCE_TYPE", sequenceType.name());
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}