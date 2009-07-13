/*
 * Copyright (C) The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.globalsearch;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.server.search.SearchableInfo;
import android.server.search.Searchables;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for setting global search preferences. Changes to search preferences trigger a broadcast
 * intent that causes all SuggestionSources objects to be updated.
 */
public class SearchSettings extends PreferenceActivity
        implements OnPreferenceClickListener, OnPreferenceChangeListener {

    private static final boolean DBG = false;
    private static final String TAG = "SearchSettings";

    // Only used to find the preferences after inflating
    private static final String CLEAR_SHORTCUTS_PREF = "clear_shortcuts";
    private static final String SEARCH_ENGINE_SETTINGS_PREF = "search_engine_settings";
    private static final String SHOW_WEB_SUGGESTIONS_PREF = "show_web_suggestions";
    private static final String SEARCH_SOURCES_PREF = "search_sources";

    private SearchManager mSearchManager;

    // These instances are not shared with SuggestionProvider
    private SuggestionSources mSources;
    private ShortcutRepository mShortcuts;

    // References to the top-level preference objects
    private Preference mClearShortcutsPreference;
    private ListPreference mWebSourcePreference;
    private PreferenceScreen mSearchEngineSettingsPreference;
    private CheckBoxPreference mShowWebSuggestionsPreference;
    private PreferenceGroup mSourcePreferences;

    // Dialog ids
    private static final int CLEAR_SHORTCUTS_CONFIRM_DIALOG = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSearchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);

        mSources = new SuggestionSources(this);
        mSources.load();
        mShortcuts = ShortcutRepositoryImplLog.create(this);
        getPreferenceManager().setSharedPreferencesName(SuggestionSources.PREFERENCES_NAME);

        addPreferencesFromResource(R.xml.preferences);

        PreferenceScreen preferenceScreen = getPreferenceScreen();
        mClearShortcutsPreference = preferenceScreen.findPreference(CLEAR_SHORTCUTS_PREF);
        mWebSourcePreference = (ListPreference) preferenceScreen.findPreference(
                SuggestionSources.WEB_SEARCH_SOURCE_PREF);
        mSearchEngineSettingsPreference = (PreferenceScreen) preferenceScreen.findPreference(
                SEARCH_ENGINE_SETTINGS_PREF);
        mShowWebSuggestionsPreference = (CheckBoxPreference) preferenceScreen.findPreference(
                SHOW_WEB_SUGGESTIONS_PREF);
        mSourcePreferences = (PreferenceGroup) getPreferenceScreen().findPreference(
                SEARCH_SOURCES_PREF);

        mClearShortcutsPreference.setOnPreferenceClickListener(this);
        mShowWebSuggestionsPreference.setOnPreferenceClickListener(this);

        // Note: If a new preference was added to this UI, please set the onchange listener on the
        // new preference here.
        mWebSourcePreference.setOnPreferenceChangeListener(this);

        updateClearShortcutsPreference();
        populateWebSourcePreference();
        populateSourcePreference();
        updateShowWebSuggestionsPreference();
        updateSearchEngineSettingsPreference(mWebSourcePreference.getValue());
    }

    @Override
    protected void onDestroy() {
        mSources.close();
        mShortcuts.close();
        super.onDestroy();
    }

    /**
     * Fills in the web search source pop-up list.
     */
    private void populateWebSourcePreference() {
        SuggestionSource defWebSearch = mSources.getSelectedWebSearchSource();
        ComponentName defComponentName = null;
        if (defWebSearch != null) {
            defComponentName = defWebSearch.getComponentName();
        }

        // Get the list of all packages handling intent action web search, these are the providers
        // that we display in the selection list.
        List<SearchableInfo> webSearchActivities = mSearchManager.getSearchablesForWebSearch();
        PackageManager pm = getPackageManager();

        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<String> values = new ArrayList<String>();
        final int count = webSearchActivities.size();
        Log.i(TAG, "Number of web search activities = " + count);
        for (int i = 0; i < count; ++i) {
            SearchableInfo searchable = webSearchActivities.get(i);
            if (searchable == null) continue;
            
            ComponentName component = searchable.getSearchActivity();
            // If both GoogleSearch and EnhancedGoogleSearch are present, the former is hidden from
            // our list because the latter is a superset of the former.
            if (component.flattenToShortString().equals(Searchables.GOOGLE_SEARCH_COMPONENT_NAME)) {
                try {
                    ComponentName enhancedGoogleSearch = ComponentName.unflattenFromString(
                            Searchables.ENHANCED_GOOGLE_SEARCH_COMPONENT_NAME);
                    pm.getActivityInfo(enhancedGoogleSearch, 0);

                    // Control comes here if EnhancedGoogleSearch is installed, in which case it
                    // overrides the GoogleSearch package in the web source list.
                    continue;
                } catch (PackageManager.NameNotFoundException e) {
                    // Nothing to do as EnhancedGoogleSearch is not installed. Continue below and
                    // add this source to the list.
                }
            }

            try {
                // Add the localised display name and index of the activity within our array as the
                // label and value for each item. The index will be used to identify which item was
                // selected by the user.
                ActivityInfo activityInfo = pm.getActivityInfo(component, 0);
                Resources res = pm.getResourcesForApplication(activityInfo.applicationInfo);
                int labelRes = (activityInfo.labelRes != 0)
                        ? activityInfo.labelRes : activityInfo.applicationInfo.labelRes;
                String name = res.getString(labelRes);
                String value = component.flattenToShortString();
                labels.add(name);
                values.add(value);
                if (DBG) Log.d(TAG, "Listing web search source: " + name);
                if (defComponentName != null
                        && defComponentName.getClassName().equals(activityInfo.name)) {
                    if (DBG) Log.d(TAG, "Default web search source: " + name);
                    mWebSourcePreference.setValue(value);
                }
            } catch (PackageManager.NameNotFoundException exception) {
                // Skip this entry and continue to list other activities.
                Log.w(TAG, "Web search source not found: " + component);
            } catch (Resources.NotFoundException exception) {
                Log.w(TAG, "No name for web search source: " + component);
            }
        }

        // Check if EnhancedGoogleSearch or GoogleSearch are available, and if so insert it at the
        // first position.
        for (int i = 1; i < values.size(); ++i) {
            String value = values.get(i);
            if (value.equals(Searchables.GOOGLE_SEARCH_COMPONENT_NAME) ||
                    value.equals(Searchables.ENHANCED_GOOGLE_SEARCH_COMPONENT_NAME)) {
                values.add(0, values.remove(i));
                labels.add(0, labels.remove(i));
                break;
            }
        }

        try {
            String[] labelsArray = new String[labels.size()];
            String[] valuesArray = new String[values.size()];
            labels.toArray(labelsArray);
            values.toArray(valuesArray);
            mWebSourcePreference.setEntries(labelsArray);
            mWebSourcePreference.setEntryValues(valuesArray);
        } catch (ArrayStoreException exception) {
            // In this case we will end up displaying an empty list.
            Log.e(TAG, "Error loading web search sources", exception);
        }
    }

    /**
     * Enables/disables the "Clear search shortcuts" preference depending
     * on whether there is any search history.
     */
    private void updateClearShortcutsPreference() {
        boolean hasHistory = mShortcuts.hasHistory();
        if (DBG) Log.d(TAG, "hasHistory()=" + hasHistory);
        mClearShortcutsPreference.setEnabled(hasHistory);
    }

    /**
     * Updates the "search engine settings" preference depending on whether
     * the currently selected search engine has settings to expose or not.
     *
     * @param webSourcePreferenceValue the web source preference value to use
     */
    private void updateSearchEngineSettingsPreference(String webSourcePreferenceValue) {
        if (webSourcePreferenceValue == null) return;

        // Get the package name of the current activity chosen for web search.
        ComponentName component = ComponentName.unflattenFromString(webSourcePreferenceValue);
        String packageName = component.getPackageName();

        // Now find out if this package contains an activity which satisfies the
        // WEB_SEARCH_SETTINGS intent, and if so, point the "search engine settings"
        // item there.

        ResolveInfo matchedInfo = findWebSearchSettingsActivity(packageName);

        // If we found a match, that means this web search source provides some settings,
        // so enable the link to its settings. If not, then disable this preference.
        mSearchEngineSettingsPreference.setEnabled(matchedInfo != null);

        PackageManager pm = getPackageManager();
        String engineName = getWebSourceLabel(pm, component);

        // Set the correct summary and intent information for the matched activity, if any.
        int summaryStringRes;
        Intent intent;
        if (matchedInfo != null) {
            summaryStringRes = R.string.search_engine_settings_summary_enabled;
            intent = createWebSearchSettingsIntent(matchedInfo);
        } else {
            summaryStringRes = R.string.search_engine_settings_summary_disabled;
            intent = null;
        }

        // If for some reason the engine name could not be found, just don't set a summary.
        if (engineName != null) {
            mSearchEngineSettingsPreference.setSummary(
                    getResources().getString(summaryStringRes, engineName));
        } else {
            mSearchEngineSettingsPreference.setSummary(null);
        }

        mSearchEngineSettingsPreference.setIntent(intent);
    }

    /**
     * Gets the name of the web source represented in the provided ResolveInfo.
     */
    private String getWebSourceLabel(PackageManager pm, ComponentName component) {
        try {
            ActivityInfo activityInfo = pm.getActivityInfo(component, 0);
            Resources res = pm.getResourcesForApplication(activityInfo.applicationInfo);
            int labelRes = (activityInfo.labelRes != 0) ?
                    activityInfo.labelRes : activityInfo.applicationInfo.labelRes;
            return res.getString(labelRes);
        } catch (PackageManager.NameNotFoundException exception) {
            Log.e(TAG, "Error loading web search source from activity "
                    + component, exception);
            return null;
        }
    }

    /**
     * Returns the activity in the provided package that satisfies the
     * {@link SearchManager#INTENT_ACTION_WEB_SEARCH_SETTINGS} intent, or null
     * if none.
     */
    private ResolveInfo findWebSearchSettingsActivity(String packageName) {
        // Get all the activities which satisfy the WEB_SEARCH_SETTINGS intent.
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(SearchManager.INTENT_ACTION_WEB_SEARCH_SETTINGS);
        List<ResolveInfo> activitiesWithWebSearchSettings = pm.queryIntentActivities(intent, 0);

        // Iterate through them and see if any of them are the activity we're looking for.
        for (ResolveInfo resolveInfo : activitiesWithWebSearchSettings) {
            if (packageName.equals(resolveInfo.activityInfo.packageName)) {
                return resolveInfo;
            }
        }

        return null;
    }

    /**
     * Creates an intent for accessing the web search settings from the provided ResolveInfo
     * representing an activity.
     */
    private Intent createWebSearchSettingsIntent(ResolveInfo info) {
        Intent intent = new Intent(SearchManager.INTENT_ACTION_WEB_SEARCH_SETTINGS);
        intent.setComponent(
                new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
        return intent;
    }

    /**
     * Fills the suggestion source list.
     */
    private void populateSourcePreference() {
        for (SuggestionSource source : mSources.getSuggestionSources()) {
            Preference pref = createSourcePreference(source);
            if (pref != null) {
                if (DBG) Log.d(TAG, "Adding search source: " + source);
                mSourcePreferences.addPreference(pref);
            }
        }
    }
    
    /**
     * Updates the "show web suggestions" preference from the value in system settings.
     */
    private void updateShowWebSuggestionsPreference() {
        int value;
        try {
            value = Settings.System.getInt(
                    getContentResolver(), Settings.System.SHOW_WEB_SUGGESTIONS);
        } catch (SettingNotFoundException e) {
            // No setting found, create one.
            Settings.System.putInt(getContentResolver(), Settings.System.SHOW_WEB_SUGGESTIONS, 1);
            value = 1;
        }
        mShowWebSuggestionsPreference.setChecked(value == 1);
    }

    /**
     * Adds a suggestion source to the list of suggestion source checkbox preferences.
     */
    private Preference createSourcePreference(SuggestionSource source) {
        CheckBoxPreference sourcePref = new CheckBoxPreference(this);
        sourcePref.setKey(mSources.getSourceEnabledPreference(source));
        sourcePref.setDefaultValue(mSources.isSourceDefaultEnabled(source));
        sourcePref.setOnPreferenceChangeListener(this);
        String label = source.getLabel();
        sourcePref.setTitle(label);
        sourcePref.setSummaryOn(source.getSettingsDescription());
        sourcePref.setSummaryOff(source.getSettingsDescription());
        return sourcePref;
    }

    /**
     * Handles clicks on the "Clear search shortcuts" preference.
     */
    public synchronized boolean onPreferenceClick(Preference preference) {
        if (preference == mClearShortcutsPreference) {
            showDialog(CLEAR_SHORTCUTS_CONFIRM_DIALOG);
            return true;
        } else if (preference == mShowWebSuggestionsPreference) {
            Settings.System.putInt(
                    getContentResolver(), 
                    Settings.System.SHOW_WEB_SUGGESTIONS,
                    mShowWebSuggestionsPreference.isChecked() ? 1 : 0);
        }
        return false;
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case CLEAR_SHORTCUTS_CONFIRM_DIALOG:
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.clear_shortcuts)
                        .setMessage(R.string.clear_shortcuts_prompt)
                        .setPositiveButton(R.string.agree, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                if (DBG) Log.d(TAG, "Clearing history...");
                                mShortcuts.clearHistory();
                                updateClearShortcutsPreference();
                            }
                        })
                        .setNegativeButton(R.string.disagree, null).create();
            default:
                Log.e(TAG, "unknown dialog" + id);
                return null;
        }
    }

    /**
     * Inform our listeners (SuggestionSources objects) about the updated settings data.
     */
    private void broadcastSettingsChanged() {
        // We use a message broadcast since the listeners could be in multiple processes.
        sendBroadcast(new Intent(SuggestionSources.ACTION_SETTINGS_CHANGED));
    }

    public synchronized boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mWebSourcePreference) {
            String valueStr = (String)newValue;
            ComponentName activity = ComponentName.unflattenFromString(valueStr);
            if (DBG) Log.i(TAG, "Setting default web search source as " + valueStr);

            mSearchManager.setDefaultWebSearch(activity);
            updateSearchEngineSettingsPreference(valueStr);
        } else {
            broadcastSettingsChanged();
        }

        return true;  // to update the selection in the list if the user brings it up again.
    }
}
