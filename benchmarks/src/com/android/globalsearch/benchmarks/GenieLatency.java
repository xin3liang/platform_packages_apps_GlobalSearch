/*
 * Copyright (C) 2009 Google Inc.
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

package com.android.globalsearch.benchmarks;

import android.content.ComponentName;

/*

To build and run:

mmm vendor/google/apps/GlobalSearch/benchmarks \
&& adb -e install -r $OUT/system/app/GlobalSearchBenchmarks.apk \
&& sleep 10 \
&& adb -e shell am start -a android.intent.action.MAIN \
        -n com.android.globalsearch.benchmarks/.GenieLatency \
&& adb -e logcat

 */
public class GenieLatency extends SourceLatency {

    private static final String TAG = "GenieLatency";

    private static final String[] queries =
            { "", "a", "s", "e", "r", "pub", "taxi", "kilt hire", "pizza",
             "weather london uk", "terminator showtimes", "obama news",
             "12 USD in GBP", "how to pass a drug test", "goog stock",
             "76 Bucking",
             "sanxjkashasrxae" };

    private static ComponentName GENIE_COMPONENT =
            new ComponentName("com.google.android.providers.enhancedgooglesearch",
                    "com.google.android.providers.enhancedgooglesearch.Launcher");

    @Override
    protected void onResume() {
        super.onResume();
        testGenie();
        finish();
    }

    private void testGenie() {
        checkSource("GENIE", GENIE_COMPONENT, queries);
    }

}
