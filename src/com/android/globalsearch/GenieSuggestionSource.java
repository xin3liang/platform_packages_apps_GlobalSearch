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

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.database.Cursor;
import android.server.search.SearchableInfo;

/**
 * A suggestion provider which provides content from Genie, a service that offers
 * a superset of the content provided by Google Suggest.
 *
 * TODO: This class should go away once we are able to put all the information
 * below into {@link android.server.search.SearchableInfo}.
 */
public class GenieSuggestionSource extends SearchableSuggestionSource {

    private static final ComponentName GENIE_COMPONENT =
        new ComponentName("com.google.android.providers.genie",
                "com.google.android.providers.genie.GenieLauncher");

    private GenieSuggestionSource(Context context, SearchableInfo searchable) {
        super(context, searchable);
    }

    /**
     * Factory method.
     */
    public static GenieSuggestionSource create(Context context) {
        SearchableInfo si = SearchManager.getSearchableInfo(GENIE_COMPONENT, false);
        if (si == null) {
            return null;
        }
        return new GenieSuggestionSource(context, si);
    }

    @Override
    public boolean shouldIgnoreAfterNoResults() {
        return false;
    }

}
