/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.traceur;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.EventLog;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;

public class TraceService extends IntentService {
    /* Indicates Perfetto has stopped tracing due to either the supplied long trace limitations
     * or limited storage capacity. */
    static String INTENT_ACTION_NOTIFY_SESSION_STOPPED =
            "com.android.traceur.NOTIFY_SESSION_STOPPED";
    /* Indicates a Traceur-associated tracing session has been attached to a bug report */
    static String INTENT_ACTION_NOTIFY_SESSION_STOLEN =
            "com.android.traceur.NOTIFY_SESSION_STOLEN";
    private static String INTENT_ACTION_STOP_TRACING = "com.android.traceur.STOP_TRACING";
    private static String INTENT_ACTION_START_TRACING = "com.android.traceur.START_TRACING";
    private static String INTENT_ACTION_START_STACK_SAMPLING =
            "com.android.traceur.START_STACK_SAMPLING";

    private static String INTENT_EXTRA_TAGS= "tags";
    private static String INTENT_EXTRA_BUFFER = "buffer";
    private static String INTENT_EXTRA_APPS = "apps";
    private static String INTENT_EXTRA_LONG_TRACE = "long_trace";
    private static String INTENT_EXTRA_LONG_TRACE_SIZE = "long_trace_size";
    private static String INTENT_EXTRA_LONG_TRACE_DURATION = "long_trace_duration";

    private static String BETTERBUG_PACKAGE_NAME = "com.google.android.apps.internal.betterbug";

    private static int TRACE_NOTIFICATION = 1;
    private static int SAVING_TRACE_NOTIFICATION = 2;

    private static final int MIN_KEEP_COUNT = 3;
    private static final long MIN_KEEP_AGE = 4 * DateUtils.WEEK_IN_MILLIS;

    public static void startTracing(final Context context,
            Collection<String> tags, int bufferSizeKb, boolean apps,
            boolean longTrace, int maxLongTraceSizeMb, int maxLongTraceDurationMinutes) {
        Intent intent = new Intent(context, TraceService.class);
        intent.setAction(INTENT_ACTION_START_TRACING);
        intent.putExtra(INTENT_EXTRA_TAGS, new ArrayList(tags));
        intent.putExtra(INTENT_EXTRA_BUFFER, bufferSizeKb);
        intent.putExtra(INTENT_EXTRA_APPS, apps);
        intent.putExtra(INTENT_EXTRA_LONG_TRACE, longTrace);
        intent.putExtra(INTENT_EXTRA_LONG_TRACE_SIZE, maxLongTraceSizeMb);
        intent.putExtra(INTENT_EXTRA_LONG_TRACE_DURATION, maxLongTraceDurationMinutes);
        context.startForegroundService(intent);
    }

    public static void startStackSampling(final Context context) {
        Intent intent = new Intent(context, TraceService.class);
        intent.setAction(INTENT_ACTION_START_STACK_SAMPLING);
        context.startForegroundService(intent);
    }

    public static void stopTracing(final Context context) {
        Intent intent = new Intent(context, TraceService.class);
        intent.setAction(INTENT_ACTION_STOP_TRACING);
        context.startForegroundService(intent);
    }

    // Silently stops a trace without saving it. This is intended to be called when tracing is no
    // longer allowed, i.e. if developer options are turned off while tracing. The usual method of
    // stopping a trace via intent, stopTracing(), will not work because intents cannot be received
    // when developer options are disabled.
    static void stopTracingWithoutSaving(final Context context) {
        NotificationManager notificationManager =
            context.getSystemService(NotificationManager.class);
        notificationManager.cancel(TRACE_NOTIFICATION);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(context.getString(
            R.string.pref_key_tracing_on), false).commit();
        TraceUtils.traceStop();
    }

    public TraceService() {
        this("TraceService");
    }

    protected TraceService(String name) {
        super(name);
        setIntentRedelivery(true);
    }

    @Override
    public void onHandleIntent(Intent intent) {
        Context context = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // Checks that developer options are enabled and the user is an admin before continuing.
        boolean developerOptionsEnabled =
                Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
        if (!developerOptionsEnabled) {
            // Refer to b/204992293.
            EventLog.writeEvent(0x534e4554, "204992293", -1, "");
            return;
        }
        UserManager userManager = context.getSystemService(UserManager.class);
        boolean isAdminUser = userManager.isAdminUser();
        boolean debuggingDisallowed = userManager.hasUserRestriction(
                UserManager.DISALLOW_DEBUGGING_FEATURES);
        if (!isAdminUser || debuggingDisallowed) {
            return;
        }

        boolean recordingWasTrace = prefs.getBoolean(
                context.getString(R.string.pref_key_recording_was_trace), true);
        TraceUtils.RecordingType type = recordingWasTrace ? TraceUtils.RecordingType.TRACE
                                                          : TraceUtils.RecordingType.STACK_SAMPLES;

        if (intent.getAction().equals(INTENT_ACTION_START_TRACING)) {
            startTracingInternal(intent.getStringArrayListExtra(INTENT_EXTRA_TAGS),
                intent.getIntExtra(INTENT_EXTRA_BUFFER,
                    Integer.parseInt(context.getString(R.string.default_buffer_size))),
                intent.getBooleanExtra(INTENT_EXTRA_APPS, false),
                intent.getBooleanExtra(INTENT_EXTRA_LONG_TRACE, false),
                intent.getIntExtra(INTENT_EXTRA_LONG_TRACE_SIZE,
                    Integer.parseInt(context.getString(R.string.default_long_trace_size))),
                intent.getIntExtra(INTENT_EXTRA_LONG_TRACE_DURATION,
                    Integer.parseInt(context.getString(R.string.default_long_trace_duration))));
        } else if (intent.getAction().equals(INTENT_ACTION_START_STACK_SAMPLING)) {
            startStackSamplingInternal();
        } else if (intent.getAction().equals(INTENT_ACTION_STOP_TRACING)) {
            stopTracingInternal(TraceUtils.getOutputFilename(type), false);
        } else if (intent.getAction().equals(INTENT_ACTION_NOTIFY_SESSION_STOPPED)) {
            stopTracingInternal(TraceUtils.getOutputFilename(type), false);
        } else if (intent.getAction().equals(INTENT_ACTION_NOTIFY_SESSION_STOLEN)) {
            stopTracingInternal("", true);
        }
    }

    private void startTracingInternal(Collection<String> tags, int bufferSizeKb, boolean appTracing,
            boolean longTrace, int maxLongTraceSizeMb, int maxLongTraceDurationMinutes) {
        Context context = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        Intent stopIntent = new Intent(Receiver.STOP_ACTION,
            null, context, Receiver.class);
        stopIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        boolean attachToBugreport =
                prefs.getBoolean(context.getString(R.string.pref_key_attach_to_bugreport), true);

        Notification.Builder notification = getTraceurNotification(
                context.getString(R.string.trace_is_being_recorded),
                context.getString(R.string.tap_to_stop_tracing),
                Receiver.NOTIFICATION_CHANNEL_TRACING);
        notification.setOngoing(true)
                .setContentIntent(PendingIntent.getBroadcast(context, 0, stopIntent,
                          PendingIntent.FLAG_IMMUTABLE));

        startForeground(TRACE_NOTIFICATION, notification.build());

        if (TraceUtils.traceStart(tags, bufferSizeKb, appTracing,
                longTrace, attachToBugreport, maxLongTraceSizeMb, maxLongTraceDurationMinutes)) {
            stopForeground(Service.STOP_FOREGROUND_DETACH);
        } else {
            // Starting the trace was unsuccessful, so ensure that tracing
            // is stopped and the preference is reset.
            TraceUtils.traceStop();
            prefs.edit().putBoolean(context.getString(R.string.pref_key_tracing_on),
                        false).commit();
            QsService.updateTile();
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        }

        // This is used to keep track of whether the most recent recording was a trace for the
        // purpose of 1) determining which notification should be sent after the recording is done,
        // and 2) choosing the filename format for the saved recording.
        prefs.edit().putBoolean(
                context.getString(R.string.pref_key_recording_was_trace), true).commit();
    }

    private void startStackSamplingInternal() {
        Context context = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        Intent stopIntent = new Intent(Receiver.STOP_ACTION, null, context, Receiver.class);
        stopIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        boolean attachToBugreport =
                prefs.getBoolean(context.getString(R.string.pref_key_attach_to_bugreport), true);

        Notification.Builder notification = getTraceurNotification(
                context.getString(R.string.stack_samples_are_being_recorded),
                context.getString(R.string.tap_to_stop_stack_sampling),
                Receiver.NOTIFICATION_CHANNEL_TRACING);
        notification.setOngoing(true)
                .setContentIntent(PendingIntent.getBroadcast(context, 0, stopIntent,
                          PendingIntent.FLAG_IMMUTABLE));

        startForeground(TRACE_NOTIFICATION, notification.build());

        if (TraceUtils.stackSampleStart(attachToBugreport)) {
            stopForeground(Service.STOP_FOREGROUND_DETACH);
        } else {
            // Starting stack sampling was unsuccessful, so ensure that it is stopped and the
            // preference is reset.
            TraceUtils.traceStop();
            prefs.edit().putBoolean(
                    context.getString(R.string.pref_key_stack_sampling_on), false).commit();
            QsService.updateTile();
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        }

        // This is used to keep track of whether the most recent recording was a trace for the
        // purpose of 1) determining which notification should be sent after the recording is done,
        // and 2) choosing the filename format for the saved recording.
        prefs.edit().putBoolean(
                context.getString(R.string.pref_key_recording_was_trace), false).commit();
    }

    private void stopTracingInternal(String outputFilename, boolean sessionStolen) {
        Context context = getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        NotificationManager notificationManager =
            getSystemService(NotificationManager.class);

        // This helps determine which text to show on the post-recording notifications.
        boolean recordingWasTrace = prefs.getBoolean(
                context.getString(R.string.pref_key_recording_was_trace), true);

        Notification.Builder notification = getTraceurNotification(context.getString(
                sessionStolen ? R.string.attaching_to_report :
                        recordingWasTrace ? R.string.saving_trace : R.string.saving_stack_samples),
                null, Receiver.NOTIFICATION_CHANNEL_OTHER);
        notification.setProgress(1, 0, true);

        startForeground(SAVING_TRACE_NOTIFICATION, notification.build());

        notificationManager.cancel(TRACE_NOTIFICATION);

        if (sessionStolen) {
            Notification.Builder notificationAttached = getTraceurNotification(
                    context.getString(R.string.attached_to_report), null,
                    Receiver.NOTIFICATION_CHANNEL_OTHER);
            notification.setAutoCancel(true);

            Intent openIntent =
                    getPackageManager().getLaunchIntentForPackage(BETTERBUG_PACKAGE_NAME);
            if (openIntent != null) {
                // Add "Tap to open BetterBug" to notification only if intent is non-null.
                notificationAttached.setContentText(getString(
                        R.string.attached_to_report_summary));
                notificationAttached.setContentIntent(PendingIntent.getActivity(
                        context, 0, openIntent, PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_CANCEL_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE));
            }

            // Adds an action button to the notification for starting a new trace. This is currently
            // not enabled for stack samples because we don't expect continuous stack samples
            // (repeatedly stopped and attached to different bug reports) to be a common use case.
            if (recordingWasTrace) {
                Intent restartIntent = new Intent(context, InternalReceiver.class);
                restartIntent.setAction(InternalReceiver.START_ACTION);
                PendingIntent restartPendingIntent = PendingIntent.getBroadcast(context, 0,
                        restartIntent, PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_CANCEL_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);
                Notification.Action action = new Notification.Action.Builder(
                        R.drawable.bugfood_icon, context.getString(R.string.start_new_trace),
                        restartPendingIntent).build();
                notificationAttached.addAction(action);
            }

            NotificationManager.from(context).notify(0, notificationAttached.build());
        } else {
            File file = TraceUtils.getOutputFile(outputFilename);

            if (TraceUtils.traceDump(file)) {
                FileSender.postNotification(getApplicationContext(), file);
            }
        }

        stopForeground(Service.STOP_FOREGROUND_REMOVE);

        TraceUtils.cleanupOlderFiles(MIN_KEEP_COUNT, MIN_KEEP_AGE);
    }

    // Creates a Traceur notification for the given channel using the provided title and message.
    private Notification.Builder getTraceurNotification(String title, String msg, String channel) {
        Context context = getApplicationContext();
        Notification.Builder notification = new Notification.Builder(context, channel)
                .setContentTitle(title)
                .setTicker(title)
                .setSmallIcon(R.drawable.bugfood_icon)
                .setLocalOnly(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color));

        // Some Traceur notifications only have a title.
        if (msg != null) {
            notification.setContentText(msg);
        }

        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            notification.extend(new Notification.TvExtender());
        }

        return notification;
    }
}
