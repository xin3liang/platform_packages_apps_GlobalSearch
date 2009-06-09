/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Browser;
import android.util.Log;
import android.text.TextUtils;

/**
 * This class is purely here to get search queries and route them to
 * the appropriate application.
 */
public class GlobalSearch extends Activity {
    private static final boolean DBG = true;
    private static final String TAG = GlobalSearch.class.getSimpleName();

    /**
     * Primarily, we try to just forward along whatever intents we get. In a few
     * special cases, we add some extra stuff to the intent we forward along, or
     * switch a few things up.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent != null) {
            if (DBG) Log.d(TAG, "Got intent: " + intent.toURI());

            // We use the 'extra data' column of the suggestion cursor to report a different
            // component to which this intent should be directed.
            String intentComponent = intent.getStringExtra(SearchManager.EXTRA_DATA_KEY);
            if (intentComponent != null) {
                ComponentName componentName = ComponentName.unflattenFromString(intentComponent);
                intent.setComponent(componentName);
                intent.removeExtra(SearchManager.EXTRA_DATA_KEY);
            } else {
                // No component in the suggestion, so it must have come from one
                // of our built-in sources. Just launch it.
                intent.setComponent(null);

                final String action = intent.getAction();

                if (Intent.ACTION_SEARCH.equals(action)) {
                    // they hit the search button on the search dialog, translate into a web search
                    final String query = intent.getStringExtra(SearchManager.QUERY);
                    if (!TextUtils.isEmpty(query)) {
                        intent.setAction(Intent.ACTION_WEB_SEARCH);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }
                } else if (Intent.ACTION_WEB_SEARCH.equals(action)) {
                    // This is the 'search the web' suggestion at the bottom
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
            }

            setBrowserApplicationId(intent);

            try {
                if (DBG) Log.d(TAG, "Launching intent: " + intent.toURI());
                startActivity(intent);
            } catch (ActivityNotFoundException ex) {
                Log.w(TAG, "No activity found to handle: " + intent);
            }
        }
        finish();
    }

    /**
     * If the intent is to open an HTTP or HTTPS URL, we set
     * {@link Browser#EXTRA_APPLICATION_ID} so that any existing browser window that
     * has been opened by globalsearch for the same URL will be reused.
     */
    private static void setBrowserApplicationId(Intent intent) {
        Uri data = intent.getData();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && data != null) {
            String scheme = data.getScheme();
            if (scheme != null && scheme.startsWith("http")) {
                intent.putExtra(Browser.EXTRA_APPLICATION_ID, data.toString());
            }
        }
    }

}
