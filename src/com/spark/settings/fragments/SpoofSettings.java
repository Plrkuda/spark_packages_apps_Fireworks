/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.spark.settings.fragments;

import android.app.Activity;
import android.app.AlertDialog; 
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemProperties;
import android.util.Log;
import android.widget.Toast;
import android.provider.Settings;

import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.util.spark.SparkUtils;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;

@SearchIndexable
public class SpoofSettings extends SettingsPreferenceFragment implements Preference.OnPreferenceChangeListener {

    public static final String TAG = "SpoofSettings";
    private static final String SYS_GOOGLE_SPOOF = "persist.sys.pixelprops.google";
    private static final String SYS_PIXEL_PROPS = "persist.sys.pixelprops.all";
    private static final String SYS_GAMEPROP_ENABLED = "persist.sys.gameprops.enabled";
    private static final String SYS_GPHOTOS_SPOOF = "persist.sys.pixelprops.gphotos";
    private static final String KEY_GAME_PROPS_JSON_FILE_PREFERENCE = "game_props_json_file_preference";

    private boolean isPixelDevice;

    private Preference mGoogleSpoof;
    private Preference mGphotosSpoof;
    private Preference mPixelProps;
    private Preference mGamePropsJsonFilePreference;
    private Preference mGamePropsSpoof;

    private Handler mHandler;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler();
        addPreferencesFromResource(R.xml.sharpener_spoof);

        mGamePropsSpoof = findPreference(SYS_GAMEPROP_ENABLED);
        mGphotosSpoof = findPreference(SYS_GPHOTOS_SPOOF);
        mGoogleSpoof = findPreference(SYS_GOOGLE_SPOOF);
        mPixelProps = findPreference(SYS_PIXEL_PROPS);
        mGamePropsJsonFilePreference = findPreference(KEY_GAME_PROPS_JSON_FILE_PREFERENCE);

        String model = SystemProperties.get("ro.product.model");
        isPixelDevice = SystemProperties.get("ro.soc.manufacturer").equals("Google");

        mGphotosSpoof.setDependency(SYS_PIXEL_PROPS);
        
        if (isPixelDevice) {
            mGoogleSpoof.setDefaultValue(false);
            if (isMainlineTensorModel(model)) {
                mGoogleSpoof.setEnabled(false);
                mGoogleSpoof.setSummary(R.string.google_spoof_option_disabled);
            }
        }

        mPixelProps.setOnPreferenceChangeListener(this);
        mGoogleSpoof.setOnPreferenceChangeListener(this);
        mGphotosSpoof.setOnPreferenceChangeListener(this);
        mGamePropsSpoof.setOnPreferenceChangeListener(this);

        mGamePropsJsonFilePreference.setOnPreferenceClickListener(preference -> {
            openFileSelector(10001);
            return true;
        });
    }
    
    private boolean isMainlineTensorModel(String model) {
        return model.matches("Pixel [8-9][a-zA-Z ]*");
    }

    private void openFileSelector(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        startActivityForResult(intent, requestCode);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                if (requestCode == 10001) {
                    loadGameSpoofingJson(uri);
                }
            }
        }
    }

    private void loadGameSpoofingJson(Uri uri) {
        Log.d(TAG, "Loading Game Props JSON from URI: " + uri.toString());
        try (InputStream inputStream = getActivity().getContentResolver().openInputStream(uri)) {
            if (inputStream != null) {
                String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                Log.d(TAG, "Game Props JSON data: " + json);
                JSONObject jsonObject = new JSONObject(json);
                for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
                    String key = it.next();
                    if (key.startsWith("PACKAGES_") && !key.endsWith("_DEVICE")) {
                        String deviceKey = key + "_DEVICE";
                        if (jsonObject.has(deviceKey)) {
                            JSONObject deviceProps = jsonObject.getJSONObject(deviceKey);
                            JSONArray packages = jsonObject.getJSONArray(key);
                            for (int i = 0; i < packages.length(); i++) {
                                String packageName = packages.getString(i);
                                Log.d(TAG, "Spoofing package: " + packageName);
                                setGameProps(packageName, deviceProps);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading Game Props JSON or setting properties", e);
        }
        mHandler.postDelayed(() -> {
            SparkUtils.showSystemRestartDialog(getContext());
        }, 1250);
    }

    private void setGameProps(String packageName, JSONObject deviceProps) {
        try {
            for (Iterator<String> it = deviceProps.keys(); it.hasNext(); ) {
                String key = it.next();
                String value = deviceProps.getString(key);
                String systemPropertyKey = "persist.sys.gameprops." + packageName + "." + key;
                SystemProperties.set(systemPropertyKey, value);
                Log.d(TAG, "Set system property: " + systemPropertyKey + " = " + value);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing device properties", e);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mPixelProps
            || preference == mGoogleSpoof
            || preference == mGphotosSpoof
            || preference == mGamePropsSpoof) {
            SparkUtils.showSystemRestartDialog(getContext());
            return true;
        }
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.SPARK_SETTINGS;
    }

    /**
     * For search
     */
    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.sharpener_spoof) {

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);

                    return keys;
                }
            };
}
