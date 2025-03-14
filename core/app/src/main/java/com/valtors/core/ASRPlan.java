package com.valtors.core;

import android.util.Log;

import com.valtors.core.glassesmanager.eventbusmessages.NEWASRLanguagesEvent;
import com.valtors.core.glassesmanager.speechrecognition.ASRStreamKey;
import com.valtors.core.tpa.EdgeTPASystem;
import com.valtors.lib.enums.ASRStreamType;
import com.valtors.lib.events.KillTpaEvent;
import com.valtors.lib.events.SpeechRecOutputEvent;
import com.valtors.lib.events.StartASRStreamRequestEvent;
import com.valtors.lib.events.StopASRStreamRequestEvent;
import com.valtors.lib.events.TranslateOutputEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class AsrPlanner {
    public static final String TAG = "ASRPlan";
    private final Map<AsrStreamKey, Set<String>> activeStreams = new HashMap<>();
    ArrayList<String> transcriptsBuffer;

    EdgeTPASystem edgeTpaSystem;

    public AsrPlanner(EdgeTPASystem edgeTpaSystemRef){
        this.edgeTpaSystem = edgeTpaSystemRef;
        transcriptsBuffer = new ArrayList<>();
        AsrStreamKey enKey = new AsrStreamKey("en-US");
        addAsrStream("ValtorsOS_INTERNAL", enKey);
    }

    @Subscribe
    public void onTranscript(SpeechRecOutputEvent event) {
        String text = event.text;
        String languageCode = event.languageCode;
        boolean isFinal = event.isFinal;

        AsrStreamKey streamKey = new AsrStreamKey(languageCode);

        if (activeStreams.containsKey(streamKey)) {
            Set<String> activeStreamElements = activeStreams.get(streamKey);

            if (activeStreamElements != null) {
                for (String packageName : activeStreamElements) {
                    if (Objects.equals(packageName, "ValtorsOS_INTERNAL")) {
                        continue;
                    }
                    Log.d(TAG, "Active stream element processed: " + packageName);
                    edgeTpaSystem.sendTranscriptEventToTpa(event, packageName);
                }
            } else {
                Log.w(TAG, "Active stream elements are null, nothing to process.");
            }
        }

        if (isFinal) {
            transcriptsBuffer.add(text);
        }
    }
    @Subscribe
    public void onTranslate(TranslateOutputEvent event){
        String fromLanguageCode = event.fromLanguageCode;
        String toLanguageCode = event.toLanguageCode;
        AsrStreamKey streamKey = new AsrStreamKey(fromLanguageCode, toLanguageCode);

        if (activeStreams.containsKey(streamKey)) {
            Set<String> activeStreamElements = activeStreams.get(streamKey);

            if (activeStreamElements != null) {
                for (String packageName : activeStreamElements) {
                    if (Objects.equals(packageName, "ValtorsOS_INTERNAL")) {
                        continue;
                    }
                    Log.d(TAG, "Active stream element processed: " + packageName);
                    edgeTpaSystem.sendTranslateEventToTpa(event, packageName);
                }
            } else {
                Log.w(TAG, "Active stream elements are null, nothing to process.");
            }
        }
    }

    @Subscribe
    public synchronized void onSubscribeStartAsrStreamRequestEvent(StartAsrStreamRequestEvent event) {
        AsrStreamKey key;
        String transcribeLanguage = event.transcribeLanguage;
        if (event.asrStreamType == AsrStreamType.TRANSLATION) {
            String translateLanguage = event.translateLanguage;
            key = new AsrStreamKey(transcribeLanguage, translateLanguage);
        } else {
            key = new AsrStreamKey(transcribeLanguage);
        }
        addAsrStream(event.packageName, key);
    }

    @Subscribe
    public synchronized void onSubscribeStopAsrStreamRequestEvent(StopAsrStreamRequestEvent event) {
        AsrStreamKey key;
        String transcribeLanguage = event.transcribeLanguage;
        if (event.asrStreamType == AsrStreamType.TRANSLATION) {
            String translateLanguage = event.translateLanguage;
            key = new AsrStreamKey(transcribeLanguage, translateLanguage);
        } else {
            key = new AsrStreamKey(transcribeLanguage);
        }
        removeAsrStream(event.packageName, key);
    }

    private void addAsrStream(String packageName, AsrStreamKey key) {
        Set<String> subscribers = activeStreams.get(key);
        if (subscribers == null) {
            subscribers = new HashSet<>();
            activeStreams.put(key, subscribers);
            updateAsrLanguages();
        }

        subscribers.add(packageName);
        Log.d(TAG, "addAsrStream: " + packageName + " subscribed to " + key);
    }

    public void updateAsrLanguages() {
        EventBus.getDefault().post(new NewAsrLanguagesEvent(getActiveFilteredStreamKeys()));
    }

    private void removeAsrStream(String packageName, AsrStreamKey key) {
        Set<String> subscribers = activeStreams.get(key);
        if (subscribers == null) {
            Log.d(TAG, "removeAsrStream: Key " + key + " not active. Nothing to stop.");
            return;
        }

        subscribers.remove(packageName);
        Log.d(TAG, "removeAsrStream: " + packageName + " unsubscribed from " + key);

        if (subscribers.isEmpty()) {
            activeStreams.remove(key);
            updateAsrLanguages();
        }
    }

    public synchronized List<AsrStreamKey> getActiveFilteredStreamKeys() {
        Set<String> translationLanguages = new HashSet<>();
        for (AsrStreamKey key : activeStreams.keySet()) {
            if (key.streamType == AsrStreamType.TRANSLATION) {
                translationLanguages.add(key.transcribeLanguage);
            }
        }
        List<AsrStreamKey> filteredList = new ArrayList<>();
        for (AsrStreamKey key : activeStreams.keySet()) {
            if (key.streamType == AsrStreamType.TRANSLATION) {
                filteredList.add(key);
            } else if (key.streamType == AsrStreamType.TRANSCRIPTION) {
                if (!translationLanguages.contains(key.transcribeLanguage)) {
                    filteredList.add(key);
                }
            }
        }
        return filteredList;
    }

    @Subscribe
    public void onKillTpaEvent(KillTpaEvent event) {
        String tpaPackageName = event.tpa.packageName;
        Log.d(TAG, "TPA KILLING SELF: " + tpaPackageName);
        unsubscribeTpaFromAllStreams(tpaPackageName);
    }

    private void unsubscribeTpaFromAllStreams(String packageName) {
        for (Map.Entry<AsrStreamKey, Set<String>> entry : activeStreams.entrySet()) {
            entry.getValue().remove(packageName);
        }

        List<AsrStreamKey> keysToRemove = new ArrayList<>();
        for (Map.Entry<AsrStreamKey, Set<String>> entry : activeStreams.entrySet()) {
            AsrStreamKey key = entry.getKey();
            Set<String> subscribers = entry.getValue();

            if (subscribers.isEmpty()) {
                if (key.streamType == AsrStreamType.TRANSCRIPTION
                        && "en-US".equals(key.transcribeLanguage)) {
                    subscribers.add("ValtorsOS_INTERNAL");
                } else {
                    keysToRemove.add(key);
                }
            }
        }

        for (AsrStreamKey removableKey : keysToRemove) {
            activeStreams.remove(removableKey);
        }
        updateAsrLanguages();
    }

}
