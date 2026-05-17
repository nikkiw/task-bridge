/*
 * Copyright 2026 Nikolay Vlasov (https://github.com/nikkiw)
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
package io.github.nikkiw.taskbridge.internal

import org.junit.Assert
import org.junit.Test

class TaskBridgeTaskIdTest {
    @Test
    fun `valid task ids pass`() {
        requireValidTaskBridgeTaskId("task-1")
        requireValidTaskBridgeTaskId("abc123")
        requireValidTaskBridgeTaskId("T_0.task-2")
    }

    @Test
    fun `invalid task ids throw`() {
        Assert.assertThrows(IllegalArgumentException::class.java) {
            requireValidTaskBridgeTaskId("")
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            requireValidTaskBridgeTaskId("../other")
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            requireValidTaskBridgeTaskId("bad..id")
        }
        Assert.assertThrows(IllegalArgumentException::class.java) {
            requireValidTaskBridgeTaskId("no/slash")
        }
    }
}
