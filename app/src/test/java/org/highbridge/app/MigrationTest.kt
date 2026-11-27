// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.highbridge.app.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** An app update must keep people's messages: version 1 data survives the move to version 2. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun v1ToV2KeepsMessagesAndNodes() {
        helper.createDatabase("mig.db", 1).apply {
            execSQL("INSERT INTO messages (packetId, protocol, conversation, fromId, fromName, text, timeMs, outgoing, status, viaBridge, read) " +
                "VALUES ('!1', 'MESHTASTIC', 'MESHTASTIC:ch:0', '!a', 'IRVP', 'Hello from v1', 1, 0, 4, 0, 1)")
            execSQL("INSERT INTO nodes (`key`, protocol, id, shortName, longName, lastHeardMs, favorite) VALUES ('MESHTASTIC:!a', 'MESHTASTIC', '!a', 'IRVP', 'Irvine Park', 1, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig.db", 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT text, emoji, replyTo FROM messages").use { c -> c.moveToFirst(); assertEquals("Hello from v1", c.getString(0)); assertEquals(0, c.getInt(1)) }
        db.query("SELECT favorite, ignored FROM nodes").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)); assertEquals(0, c.getInt(1)) }
        db.query("SELECT COUNT(*) FROM wardrive_samples").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }
}
