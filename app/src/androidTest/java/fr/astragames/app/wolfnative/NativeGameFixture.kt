package fr.astragames.app.wolfnative

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/** Authored, unprotected Wolf files. These are test data, not redistributed game assets. */
internal object NativeGameFixture {
    private val magic = byteArrayOf(0, 87, 0, 0, 79, 76, 0, 70, 77, 0)
    private class Writer : ByteArrayOutputStream() {
        fun int(value: Int) { repeat(4) { write(value ushr (it * 8) and 255) } }
        fun short(value: Int) { write(value and 255); write(value ushr 8 and 255) }
        fun string(value: String) { val bytes = value.toByteArray(Charset.forName("windows-31j")); int(bytes.size + 1); write(bytes); write(0) }
    }
    private data class Command(val opcode: Int, val args: IntArray = intArrayOf(), val strings: List<String> = emptyList(), val depth: Int = 0)
    private data class Type(val fields: List<Boolean> = emptyList(), val rows: List<List<Any>> = emptyList()) // true = string

    fun write(root: File, hero: Boolean = true) {
        fun file(path: String, bytes: ByteArray) { File(root, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) } }
        file("Data/BasicData/Game.dat", game(hero))
        file("Data/BasicData/CommonEvent.dat", Writer().run { write(byteArrayOf(0, 87, 0, 0, 79, 76, 0, 70, 67, 0)); write(0x8f); int(0); write(0x8f); toByteArray() })
        for (name in listOf("DataBase", "CDataBase", "SysDatabase")) {
            val types = if (name == "SysDatabase") List(8) { i -> when (i) {
                0 -> Type(listOf(true), listOf(listOf("MapData/title.mps"), listOf("MapData/field.mps")))
                7 -> Type(listOf(false, false, false), listOf(listOf(0, 1, 1)))
                else -> Type()
            } } else listOf(Type())
            val (project, data) = database(types)
            file("Data/BasicData/$name.project", project); file("Data/BasicData/$name.dat", data)
        }
        file("Data/BasicData/TileSetData.dat", Writer().run {
            write(magic); write(209); int(1); string("test tiles"); string("MapChip/base.png"); repeat(15) { string("") }
            write(0xff); int(17); write(ByteArray(17)); write(0xff); int(17); repeat(17) { int(0) }; write(0xcf); toByteArray()
        })
        val title = listOf(
            Command(121, intArrayOf(9_000_008, 16, 0, 0)),
            Command(121, intArrayOf(9_000_073, 1, 0, 0)),
            Command(150, intArrayOf(0, 1, 0, 0, 0, 1, 255, 0, 0, 100, 0), listOf("Picture/title.png")),
            Command(102, intArrayOf(19), listOf("Nouvelle partie", "Quitter")), Command(401, intArrayOf(2)),
            Command(150, intArrayOf(2, 1, 0), depth = 1), Command(130, intArrayOf(-2, 1, 1, 1, 32), depth = 1),
            Command(101, strings = listOf("Bienvenue !"), depth = 1), Command(173, depth = 1),
            Command(401, intArrayOf(3)), Command(175, depth = 1), Command(499),
        )
        file("Data/MapData/title.mps", map("title", title, 1, 1, 1))
        file("Data/MapData/field.mps", map("field", listOf(Command(101, strings = listOf("Dialogue tactile"))), 3, 3, 1))
        file("Data/MapChip/base.png", image(128, 16, Color.rgb(32, 128, 64)))
        file("Data/Picture/title.png", image(96, 96, Color.rgb(96, 32, 160)))
        if (hero) file("Data/CharaChip/hero.png", image(48, 64, Color.CYAN))
        file("Game.exe", "This fixture does not execute an EXE.".toByteArray())
        file("Save/original.sav", "Original Windows save must be preserved".toByteArray())
    }

    private fun game(hero: Boolean): ByteArray = Writer().run {
        write(magic); int(21); write(ByteArray(21).also { b ->
            b[0] = 16; b[1] = 4; b[2] = 4; b[4] = 60; b[7] = 3; b[8] = 1; b[9] = 1
        })
        int(9)
        listOf("Native Android test", "0000-0000", "", "sans", "", "", "", if (hero) "CharaChip/hero.png" else "", "").forEachIndexed { index, text ->
            if (index == 2) int(0) else string(text)
        }
        val sizePosition = size(); int(0); int(0); int(19)
        repeat(19) { short(if (it == 16 || it == 17) 96 else 0) }; int(0); int(0); write(0xc2)
        toByteArray().also { bytes -> repeat(4) { bytes[sizePosition + it] = ((bytes.size - 1) ushr (it * 8)).toByte() } }
    }

    private fun database(types: List<Type>): Pair<ByteArray, ByteArray> {
        val project = Writer().run {
            int(types.size)
            types.forEachIndexed { i, type ->
                string("type $i"); int(type.fields.size); type.fields.forEachIndexed { field, _ -> string("field $field") }
                int(type.rows.size); type.rows.forEachIndexed { row, _ -> string("row $row") }; string("")
                int(type.fields.size); write(ByteArray(type.fields.size)); repeat(4) { int(0) }
            }
            toByteArray()
        }
        val data = Writer().run {
            write(magic); write(0xc1); int(types.size)
            types.forEach { type ->
                int(-2); int(0); int(type.fields.size)
                var number = 0; var text = 0; type.fields.forEach { int(if (it) 2000 + text++ else 1000 + number++) }
                int(type.rows.size)
                type.rows.forEach { row ->
                    type.fields.forEachIndexed { field, isText -> if (!isText) int(row[field] as Int) }
                    type.fields.forEachIndexed { field, isText -> if (isText) string(row[field] as String) }
                }
            }
            write(0xc1); toByteArray()
        }
        return project to data
    }

    private fun map(name: String, commands: List<Command>, trigger: Int, x: Int, y: Int): ByteArray = Writer().run {
        write(ByteArray(10)); write(byteArrayOf(87, 79, 76, 70, 77, 0, 0, 0, 0, 0)); int(100); write(0x65)
        string(name); int(0); int(6); int(6); int(1)
        repeat(6 * 6 * 3) { int(if (it >= 6 * 6 * 2) 32 else 0) }
        write(byteArrayOf(0x6f, 0x39, 0x30, 0, 0)); int(0); string(name); int(x); int(y); int(1); write(ByteArray(4))
        write(0x79); int(-1); string(""); write(byteArrayOf(2, 1, 255.toByte(), 0))
        write(ByteArray(37).also { it[0] = trigger.toByte() }); write(byteArrayOf(3, 3, 3, 0)); write(3); write(2); int(0)
        int(commands.size); commands.forEach { c ->
            write(c.args.size + 1); int(c.opcode); c.args.forEach(::int); write(c.depth); write(c.strings.size); c.strings.forEach(::string); write(0)
        }
        int(3); write(ByteArray(3)); write(0x7a); write(0x70); write(0x66); toByteArray()
    }
    private fun image(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPaint(Paint().apply { this.color = color })
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
}
