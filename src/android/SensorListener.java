package org.apache.cordova.stepper;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;

import org.apache.cordova.BuildConfig;
import org.apache.cordova.stepper.util.API23Wrapper;
import org.apache.cordova.stepper.util.API26Wrapper;
import org.apache.cordova.stepper.util.Util;

import java.text.NumberFormat;
import java.util.Locale;
import android.util.Log;

/**
 * Background service which keeps the step-sensor listener alive to always get
 * the number of steps since boot.
 * <p/>
 * This service won't be needed any more if there is a way to read the
 * step-value without waiting for a sensor event
 */
public class SensorListener extends Service implements SensorEventListener {

  public final static int NOTIFICATION_ID = 1;
  private final static long MICROSECONDS_IN_ONE_MINUTE = 60000000;
  private final static long SAVE_OFFSET_TIME = AlarmManager.INTERVAL_HOUR;
  private final static int SAVE_OFFSET_STEPS = 10;

  private static int steps = 0;
  private static int lastSaveSteps = 0;
  private static long lastSaveTime;

  private static int notificationIconId = 0;

  private final BroadcastReceiver shutdownReceiver = new ShutdownRecevier();

  @Override
  public void onAccuracyChanged(final Sensor sensor, int accuracy) {

  }

  @Override
  public void onSensorChanged(final SensorEvent event) {
    Log.d("STEPPER", "SERVICE: ONSENSOR START");
    if (event.values[0] > Integer.MAX_VALUE) {
      return;
    } else {
      steps = (int) event.values[0];
      updateIfNecessary();
      Log.d("STEPPER", "SERVICE: ONSENSOR END");
    }
  }

  /**
   * @return true, if notification was updated
   */
  private boolean updateIfNecessary() {
    /*if (steps > lastSaveSteps + SAVE_OFFSET_STEPS ||
      (steps > 0 && System.currentTimeMillis() > lastSaveTime + SAVE_OFFSET_TIME)) {
      Database db = Database.getInstance(this);
      if (db.getSteps(Util.getToday()) == Integer.MIN_VALUE) {
        int pauseDifference = steps -
          getSharedPreferences("pedometer", Context.MODE_PRIVATE)
            .getInt("pauseCount", steps);
        db.insertNewDay(Util.getToday(), steps - pauseDifference);
        if (pauseDifference > 0) {
          // update pauseCount for the new day
          getSharedPreferences("pedometer", Context.MODE_PRIVATE).edit()
            .putInt("pauseCount", steps).commit();
        }
      }
      db.saveCurrentSteps(steps);
      db.close();
      lastSaveSteps = steps;
      lastSaveTime = System.currentTimeMillis();
      showNotification(); // update notification
      return true;
    } else {
      showNotification(); // update notification
      return false;
    }*/

    if(steps > (lastSaveSteps + SAVE_OFFSET_STEPS)) {
      Database db = Database.getInstance(this);
      db.saveCurrentSteps(steps);
      db.close();
    }

    showNotification();
    return true;
  }

  private void showNotification() {
    if (Build.VERSION.SDK_INT >= 26) {
      startForeground(NOTIFICATION_ID, getNotification(this));
    } else if (getSharedPreferences("pedometer", Context.MODE_PRIVATE)
      .getBoolean("notification", true)) {
      ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
        .notify(NOTIFICATION_ID, getNotification(this));
    }
  }

  @Override
  public IBinder onBind(final Intent intent) {
    return null;
  }

  /*@Override
  protected void onStop() {
    unregisterReceiver(shutdownReceiver);
    super.onStop();
  }*/

  @Override
  public int onStartCommand(final Intent intent, int flags, int startId) {
    reRegisterSensor();
    registerBroadcastReceiver();
    if (!updateIfNecessary()) {
      showNotification();
    }

    // restart service every hour to save the current step count
    long nextUpdate = Math.min(Util.getTomorrow(),
      System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR);
    AlarmManager am =
      (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
    PendingIntent pi = PendingIntent
      .getService(getApplicationContext(), 2, new Intent(this, SensorListener.class),
        PendingIntent.FLAG_UPDATE_CURRENT);
    if (Build.VERSION.SDK_INT >= 23) {
      API23Wrapper.setAlarmWhileIdle(am, AlarmManager.RTC, nextUpdate, pi);
    } else {
      am.set(AlarmManager.RTC, nextUpdate, pi);
    }

    return START_STICKY;
  }

  @Override
  public void onCreate() {
    super.onCreate();
  }

  private static int getNotificationIconId(Context context) {
    int drawableId = context.getResources().getIdentifier("ic_footsteps_silhouette_variant", "drawable",
      context.getApplicationInfo().packageName);
    if (drawableId == 0) {
      drawableId = context.getApplicationInfo().icon;
    }
    return drawableId;
  }

  @Override
  public void onTaskRemoved(final Intent rootIntent) {
    super.onTaskRemoved(rootIntent);
    // Restart service in 500 ms
    ((AlarmManager) getSystemService(Context.ALARM_SERVICE))
      .set(AlarmManager.RTC, System.currentTimeMillis() + 500, PendingIntent
        .getService(this, 3, new Intent(this, SensorListener.class), 0));
  }

  @Override
  public void onDestroy() {
    unregisterReceiver(shutdownReceiver);
    super.onDestroy();
    try {
      SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
      sm.unregisterListener(this);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public static Notification getNotification(final Context context) {
    SharedPreferences prefs = context.getSharedPreferences("pedometer", Context.MODE_PRIVATE);
    Database db = Database.getInstance(context);
    //int today_offset = db.getSteps(Util.getToday());
    int cur_steps = db.getCurrentSteps();

    //Log.d("STEPPER", "STEPS TODAY:"+today_offset+" CURRENT:"+cur_steps+" INSTANCE STEPS: "+steps);

    if (steps == 0)
      steps = db.getCurrentSteps(); // use saved value if we haven't anything better
    db.close();
    int goal = prefs.getInt(PedoListener.GOAL_PREF_INT, PedoListener.DEFAULT_GOAL);
    Notification.Builder notificationBuilder =
      Build.VERSION.SDK_INT >= 26 ? API26Wrapper.getNotificationBuilder(context) :
        new Notification.Builder(context);
    if (steps > 0) {
      /*if (today_offset == Integer.MIN_VALUE) today_offset = -steps;
      notificationBuilder.setProgress(goal, today_offset + steps, false).setContentText(
        today_offset + steps >= goal ?
          String.format(prefs.getString(PedoListener.PEDOMETER_GOAL_REACHED_FORMAT_TEXT, "Goal reached! %s steps and counting"),
            NumberFormat.getInstance(Locale.getDefault())
              .format((today_offset + steps))) :
          String.format(prefs.getString(PedoListener.PEDOMETER_STEPS_TO_GO_FORMAT_TEXT, "%s steps to go"),
            NumberFormat.getInstance(Locale.getDefault())
              .format((goal - today_offset - steps))));
      */

      //notificationBuilder.setProgress(goal, today_offset + steps, false).setContentText(String.format("Steps: %d", steps));
      //notificationBuilder.setContentText(String.format("Steps: %d", steps));
      notificationBuilder.setContentText("Pedometer is running");

    } else { // still no step value?
      notificationBuilder.setContentText(prefs.getString(PedoListener.PEDOMETER_YOUR_PROGRESS_FORMAT_TEXT, "Pedometer is running"));
    }

    PackageManager packageManager = context.getPackageManager();
    Intent launchIntent = packageManager.getLaunchIntentForPackage(context.getPackageName());

    PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
      launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);

    if (notificationIconId == 0) {
      notificationIconId = getNotificationIconId(context);
    }

    notificationBuilder.setPriority(Notification.PRIORITY_DEFAULT).setShowWhen(false)
      .setContentTitle(prefs.getString(PedoListener.PEDOMETER_IS_COUNTING_TEXT, "Pedometer is counting"))
      .setContentIntent(contentIntent).setSmallIcon(notificationIconId)
      .setOngoing(true);
    return notificationBuilder.build();
  }

  private void registerBroadcastReceiver() {
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_SHUTDOWN);
    registerReceiver(shutdownReceiver, filter);
  }

  private void reRegisterSensor() {
    SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
    try {
      sm.unregisterListener(this);
    } catch (Exception e) {
      e.printStackTrace();
    }

    if (BuildConfig.DEBUG) {
      if (sm.getSensorList(Sensor.TYPE_STEP_COUNTER).size() < 1) return; // emulator
    }

    // enable batching with delay of max 5 min
    sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER),
      SensorManager.SENSOR_DELAY_UI, (int) (5 * MICROSECONDS_IN_ONE_MINUTE));
  }
}
