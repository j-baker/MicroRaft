/*
 * Copyright (c) 2020, AfloatDB.
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

package io.afloatdb.internal.lifecycle.impl;

import io.afloatdb.internal.lifecycle.ProcessTerminationLogger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.concurrent.atomic.AtomicBoolean;

@Singleton
public class ProcessTerminationLoggerImpl implements ProcessTerminationLogger {

    public static final String PROCESS_TERMINATION_FLAG_KEY = "ProcessTerminationFlag";

    private final AtomicBoolean terminating;

    @Inject
    public ProcessTerminationLoggerImpl(@Named(PROCESS_TERMINATION_FLAG_KEY) AtomicBoolean terminating) {
        this.terminating = terminating;
    }

    @Override
    public boolean isCurrentProcessTerminating() {
        return terminating.get();
    }

}
