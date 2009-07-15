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

import junit.framework.TestCase;

import android.content.ComponentName;

/**
 * Tests {@link SuggestionSession.SessionCache}
 */
public class SessionCacheTest extends TestCase {
    private SuggestionSession.SessionCache mCache;
    private TestSuggestionSource.Builder mBuilder;
    private ComponentName mSourceName;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mCache = new SuggestionSession.SessionCache(true);

        mSourceName = new ComponentName(
                "com.android.globalsearch", "com.android.globalsearch.GlobalSearch");

        mBuilder = new TestSuggestionSource.Builder()
                .setComponent(mSourceName);
    }

    public void testZeroResultPrefix() {
        final TestSuggestionSource source = mBuilder.create();
        assertFalse(source.queryAfterZeroResults());

        mCache.reportSourceResult("yo", new SuggestionResult(source));
        assertTrue(mCache.hasReportedZeroResultsForPrefix("yo man", mSourceName));
    }

    public void testZeroResultPrefix_sourceNotIgnored() {
        final TestSuggestionSource source = mBuilder.setQueryAfterZeroResults(true).create();
        assertTrue(source.queryAfterZeroResults());

        mCache.reportSourceResult("yo", new SuggestionResult(source));
        assertFalse(mCache.hasReportedZeroResultsForPrefix("yo man", mSourceName));
    }
}
