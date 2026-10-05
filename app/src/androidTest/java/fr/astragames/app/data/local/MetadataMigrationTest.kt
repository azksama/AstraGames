package fr.astragames.app.data.local

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataMigrationTest {
    @Test fun addingSecondMetadataSourcePreservesVersionEightCatalogue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "metadata-migration-${java.util.UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = instrumentation.context.assets.open("fr.astragames.app.data.local.AstraDatabase/8.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        // Recreate the exact exported v8 schema with the native driver, then let Room validate its real migration.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = 8
            db.execSQL("""INSERT INTO games (id,title,documentUri,executableName,engine,launcher,sourceId,dateAdded,lastModified,favorite,hidden,missing,fingerprint,playCount,autoDetected,aliases,keywords,f95Url)
                VALUES ('kept','Existing game','file:///existing','Game.exe','RPG_MAKER_MV','JOIPLAY','source',1,2,1,0,0,'fingerprint',4,1,'','','https://f95zone.to/threads/42/')""")
        }
        val database = Room.databaseBuilder(context, AstraDatabase::class.java, name).addMigrations(AstraDatabase.MIGRATION_8_9).build()
        try {
            val db = database.openHelper.writableDatabase
            db.query("SELECT title,favorite,playCount,f95Url,ryuugamesUrl FROM games WHERE id='kept'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing game", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(4, cursor.getInt(2))
                assertEquals("https://f95zone.to/threads/42/", cursor.getString(3))
                assertTrue(cursor.isNull(4))
            }
            db.execSQL("UPDATE games SET ryuugamesUrl='https://www.ryuugames.com/example/' WHERE id='kept'")
            db.query("SELECT ryuugamesUrl FROM games WHERE id='kept'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("https://www.ryuugames.com/example/", cursor.getString(0))
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
