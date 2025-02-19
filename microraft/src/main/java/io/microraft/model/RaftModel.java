/*
 * Copyright (c) 2020, MicroRaft.
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

package io.microraft.model;

import io.microraft.model.log.BaseLogEntry;
import io.microraft.model.message.RaftMessage;

import java.io.Serializable;

/**
 * The base interface for the objects that hit network and persistent storage.
 * <p>
 * RaftModel objects must be immutable.
 *
 * @see RaftMessage
 * @see BaseLogEntry
 */
public interface RaftModel extends Serializable {
}
