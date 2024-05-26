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
                // Define behavior for button 1
                // For example, you can perform an action or start another activity
            }
        });

        Button button2 = findViewById(R.id.sequence2Button);
        button2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Define behavior for button 2
                // For example, you can perform an action or start another activity
            }
        });

        Button button3 = findViewById(R.id.sequence3Button);
        button3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Define behavior for button 3
                // For example, you can perform an action or start another activity
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
}