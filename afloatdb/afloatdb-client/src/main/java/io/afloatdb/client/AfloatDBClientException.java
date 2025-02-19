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

package io.afloatdb.client;

public class AfloatDBClientException extends RuntimeException {

    /**
     * Constructs a new runtime exception with {@code null} as its detail message.
     */
    public AfloatDBClientException() {
        super();
    }

    /**
     * Constructs a new runtime exception with the specified detail message.
     *
     * @param message
     *            the detail message. The detail message is saved for later retrieval by the {@link #getMessage()}
     *            method.
     */
    public AfloatDBClientException(String message) {
        super(message);
    }

    /**
     * Constructs a new runtime exception with the specified detail message and cause.
     *
     * @param message
     *            the detail message (which is saved for later retrieval by the {@link #getMessage()} method).
     * @param cause
     *            the cause (which is saved for later retrieval by the {@link #getCause()} method). (A {@code null}
     *            value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public AfloatDBClientException(String message, Throwable cause) {
        super(message, cause);
    }

    public AfloatDBClientException(Throwable cause) {
        super(cause);
    }

}
