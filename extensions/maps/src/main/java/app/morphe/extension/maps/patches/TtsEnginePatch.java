package app.morphe.extension.maps.patches;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

import app.morphe.extension.shared.Utils;

/**
 * Resolves Text-To-Speech engines for Google Maps, enabling third-party/AOSP engines
 * (such as RHVoice, Sherpa-ONNX, and system default) and routing synthesized guidance
 * to BYD's vendor navigation audio stream when available.
 */
@SuppressWarnings("unused")
public final class TtsEnginePatch {
    private static final String TAG = "MorpheTtsEngine";
    private static final String GOOGLE_TTS_PACKAGE = "com.google.android.tts";
    private static final int STREAM_NAVI = 14;
    private static TextToSpeech sEagerTts = null;

    private TtsEnginePatch() {
    }

    /**
     * Eagerly initializes and binds the TTS engine during application startup,
     * ensuring RHVoice/default synth is connected and ready before any navigation prompt.
     */
    public static void init(Context context) {
        if (context == null) {
            context = Utils.getContext();
        }
        if (context == null) {
            Log.w(TAG, "Cannot init eager TTS: context is null");
            return;
        }

        final Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Log.i(TAG, "Initializing TTS eagerly on app startup with appContext: " + appContext);

        try {
            final String engine = resolveEngine(appContext, GOOGLE_TTS_PACKAGE);
            Log.i(TAG, "Eager TTS engine resolved to: '" + engine + "'");

            sEagerTts = new TextToSpeech(appContext, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    Log.i(TAG, "Eager TextToSpeech onInit called: status=" + status + " (SUCCESS=" + TextToSpeech.SUCCESS + ")");
                    if (status == TextToSpeech.SUCCESS && sEagerTts != null) {
                        try {
                            setAudioAttributes(sEagerTts, null);
                            Log.i(TAG, "Eager TextToSpeech successfully initialized and bound to engine: " + engine);
                        } catch (Throwable t) {
                            Log.w(TAG, "Error configuring eager TTS audio attributes", t);
                        }
                    } else {
                        Log.w(TAG, "Eager TextToSpeech failed initialization with status: " + status);
                    }
                }
            }, engine);
        } catch (Throwable t) {
            Log.e(TAG, "Failed eager TextToSpeech initialization", t);
        }
    }

    /**
     * Resolves the best available TTS engine package name.
     * Checks user-configured default synth in Settings, validates availability of
     * the requested engine, and falls back to installed third-party engines or system default.
     */
    public static String resolveEngine(String requestedEngine) {
        Context context = Utils.getContext();
        return resolveEngine(context, requestedEngine);
    }

    public static String resolveEngine(Context context, String requestedEngine) {
        Log.i(TAG, "resolveEngine called with requested: '" + requestedEngine + "', context: " + context);
        if (context == null) {
            context = Utils.getContext();
        }
        if (context == null) {
            Log.w(TAG, "Context is null, returning requested: " + requestedEngine);
            return requestedEngine;
        }

        try {
            PackageManager pm = context.getPackageManager();

            // 1. Check if user configured a system default TTS engine in Settings
            String defaultSynth = Settings.Secure.getString(
                    context.getContentResolver(), "tts_default_synth");
            Log.d(TAG, "Secure.tts_default_synth = '" + defaultSynth + "'");
            if (!TextUtils.isEmpty(defaultSynth) && isTtsEngineInstalled(pm, defaultSynth)) {
                Log.i(TAG, "Using system default TTS engine: " + defaultSynth);
                return defaultSynth;
            }

            // 2. If the requested engine is installed, use it
            if (!TextUtils.isEmpty(requestedEngine) && isTtsEngineInstalled(pm, requestedEngine)) {
                Log.i(TAG, "Using requested TTS engine: " + requestedEngine);
                return requestedEngine;
            }

            // 3. Fallback: Check for any available installed TTS engines
            List<ResolveInfo> services = pm.queryIntentServices(
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
            if (services != null && !services.isEmpty()) {
                for (ResolveInfo info : services) {
                    if (info.serviceInfo != null && info.serviceInfo.packageName != null) {
                        String pkg = info.serviceInfo.packageName;
                        Log.i(TAG, "Found available TTS engine: " + pkg);
                        return pkg;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error resolving TTS engine", e);
        }

        Log.i(TAG, "Falling back to requested engine: " + requestedEngine);
        return requestedEngine;
    }

    /**
     * Intercepts TextToSpeech.setAudioAttributes calls to route speech through
     * BYD's STREAM_NAVI (stream 14) on BYD head units for proper media ducking.
     */
    public static int setAudioAttributes(TextToSpeech tts, AudioAttributes attributes) {
        if (tts == null) {
            return TextToSpeech.ERROR;
        }
        if (!isBydBuild()) {
            return tts.setAudioAttributes(attributes);
        }

        try {
            Context context = Utils.getContext();
            AudioManager audioManager = context == null
                    ? null
                    : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int maximum = audioManager == null
                    ? 0
                    : audioManager.getStreamMaxVolume(STREAM_NAVI);
            if (maximum > 0) {
                AudioAttributes navigationAttributes = new AudioAttributes.Builder()
                        .setLegacyStreamType(STREAM_NAVI)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                Log.d(TAG, "Setting TTS audio attributes to STREAM_NAVI (14)");
                return tts.setAudioAttributes(navigationAttributes);
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "stream=14 TTS attributes rejected; using original", exception);
        }

        return tts.setAudioAttributes(attributes);
    }

    /**
     * Intercepts TextToSpeech.setEngineByPackageName calls to resolve the engine package.
     */
    public static int setEngineByPackageName(TextToSpeech tts, String enginePackageName) {
        if (tts == null) {
            return TextToSpeech.ERROR;
        }
        String resolved = resolveEngine(enginePackageName);
        if (resolved == null) {
            return TextToSpeech.SUCCESS;
        }
        Log.d(TAG, "setEngineByPackageName resolved '" + enginePackageName + "' -> '" + resolved + "'");
        return tts.setEngineByPackageName(resolved);
    }

    private static boolean isTtsEngineInstalled(PackageManager pm, String packageName) {
        if (pm == null || TextUtils.isEmpty(packageName)) {
            return false;
        }
        try {
            Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            intent.setPackage(packageName);
            List<ResolveInfo> list = pm.queryIntentServices(intent, 0);
            if (list != null && !list.isEmpty()) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            pm.getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBydBuild() {
        return startsWithByd(Build.MANUFACTURER) || startsWithByd(Build.BRAND);
    }

    private static boolean startsWithByd(String value) {
        return value != null && value.regionMatches(true, 0, "BYD", 0, 3);
    }
}
