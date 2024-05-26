package com.example.temi_elevator;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.robotemi.sdk.*;
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.constants.Page;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.navigation.model.SpeedLevel;
import com.robotemi.sdk.listeners.OnConversationStatusChangedListener;
import com.robotemi.sdk.listeners.OnRobotReadyListener;

import java.util.ArrayList;
import java.util.List;

public class Reminder {

    private String message;
    private long timeInMillis;

    public Reminder(String message, long timeInMillis) {
        this.message = message;
        this.timeInMillis = timeInMillis;
    }

    public String getMessage() {
        return message;
    }

    public long getTimeInMillis() {
        return timeInMillis;
    }

    public static class ReminderManager {

        private Context context;
        private AlarmManager alarmManager;
        private List<Reminder> reminders;

        public ReminderManager(Context context) {
            this.context = context;
            this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            this.reminders = new ArrayList<>();
        }

        public void setReminder(Reminder reminder) {
            reminders.add(reminder);
            Intent intent = new Intent(context, ReminderReceiver.class);
            intent.putExtra("message", reminder.getMessage());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, reminders.size(), intent, PendingIntent.FLAG_UPDATE_CURRENT);
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, reminder.getTimeInMillis(), pendingIntent);
        }
    }

    public static class ReminderReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            Robot.getInstance().speak(TtsRequest.create(message, false));
        }
    }
}
