package fr.astragames.wolf

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class EventAddress(val map: Int, val event: Int = -1, val common: Int = -1)

/** Wolf's value codes are references, not keys in one flat variable dictionary. */
internal class WolfVariables(
    private val databases: Map<WolfDatabaseKind, WolfDatabase>,
    private val systemValue: (Int, EventAddress) -> Int?,
) {
    val numbers = linkedMapOf<Int, Int>()
    val strings = linkedMapOf<Int, String>()
    val self = linkedMapOf<Triple<Int, Int, Int>, Int>()
    val common = linkedMapOf<Pair<Int, Int>, Int>()
    val commonStrings = linkedMapOf<Pair<Int, Int>, String>()
    val system = linkedMapOf<Int, Int>()
    val systemStrings = linkedMapOf<Int, String>()
    val mutableNumbers = linkedMapOf<Triple<Int, Int, Int>, Int>()
    val mutableStrings = linkedMapOf<Triple<Int, Int, Int>, String>()

    fun clear() {
        numbers.clear(); strings.clear(); self.clear(); common.clear(); commonStrings.clear()
        system.clear(); systemStrings.clear(); mutableNumbers.clear(); mutableStrings.clear()
    }

    fun read(code: Int, address: EventAddress): Int = when (code) {
        in Int.MIN_VALUE..999_999 -> code
        in 1_000_000..1_099_999 -> self[Triple(address.map, (code - 1_000_000) / 10, code % 10)] ?: 0
        in 1_100_000..1_199_999 -> self[Triple(address.map, address.event, code % 10)] ?: 0
        in 1_600_000..1_699_999 -> common[address.common to (code % 100)] ?: numericPrefix(commonStrings[address.common to (code % 100)] ?: "")
        in 2_000_000..2_999_999 -> numbers[code - 2_000_000] ?: 0
        in 3_000_000..3_999_999 -> numericPrefix(strings[code - 3_000_000] ?: "")
        in 9_000_000..9_099_999 -> {
            val id = code % 100_000
            require(id in SUPPORTED_SYSTEM_READS) { "Variable système non prise en charge : $code" }
            require(id !in setOf(8, 73) || id in system) { "Valeur initiale système non établie : $code ; une écriture explicite est nécessaire" }
            systemValue(id, address) ?: system[id] ?: 0
        }
        in 9_100_000..9_199_999 -> systemValue(code, address) ?: error("État de personnage non pris en charge : $code")
        in 15_000_000..15_999_999 -> common[((code - 15_000_000) / 100) to (code % 100)] ?: 0
        in 1_000_000_000..1_099_999_999, in 1_100_000_000..1_199_999_999, in 1_300_000_000..1_399_999_999 -> {
            val base = code / 100_000_000 * 100_000_000
            val kind = when (base) { 1_000_000_000 -> WolfDatabaseKind.USER; 1_100_000_000 -> WolfDatabaseKind.MUTABLE; else -> WolfDatabaseKind.SYSTEM }
            val relative = code - base
            databaseNumber(kind, relative / 1_000_000, (relative % 1_000_000) / 100, relative % 100)
        }
        else -> error("Espace de variable non pris en charge : $code")
    }

    fun write(code: Int, value: Int, address: EventAddress) {
        when (code) {
            in 1_000_000..1_099_999 -> self[Triple(address.map, (code - 1_000_000) / 10, code % 10)] = value
            in 1_100_000..1_199_999 -> self[Triple(address.map, address.event, code % 10)] = value
            in 1_600_000..1_699_999 -> common[address.common to (code % 100)] = value
            in 2_000_000..2_999_999 -> numbers[code - 2_000_000] = value
            in 9_000_000..9_099_999 -> {
                val id = code % 100_000
                require(id in SUPPORTED_SYSTEM_WRITES) { "Écriture système non prise en charge : $code" }
                require(id != 8 || value in 1..1024) { "Taille de police système hors capacité : $value" }
                require(id != 73 || value in 0..1) { "Contrôle tactile système invalide : $value" }
                system[id] = value
            }
            in 15_000_000..15_999_999 -> common[((code - 15_000_000) / 100) to (code % 100)] = value
            in 1_100_000_000..1_199_999_999 -> {
                val relative = code - 1_100_000_000
                setDatabaseNumber(relative / 1_000_000, (relative % 1_000_000) / 100, relative % 100, value)
            }
            else -> error("Destination de variable non prise en charge : $code")
        }
    }

    fun readString(code: Int, address: EventAddress): String = when (code) {
        in 1_600_000..1_699_999 -> commonStrings[address.common to (code % 100)] ?: ""
        in 3_000_000..3_999_999 -> strings[code - 3_000_000] ?: ""
        in 15_000_000..15_999_999 -> commonStrings[((code - 15_000_000) / 100) to (code % 100)] ?: ""
        in 9_900_000..9_999_999 -> error("Chaîne système non prise en charge : $code")
        in 1_000_000_000..1_099_999_999, in 1_100_000_000..1_199_999_999, in 1_300_000_000..1_399_999_999 -> {
            val base = code / 100_000_000 * 100_000_000
            val kind = when (base) { 1_000_000_000 -> WolfDatabaseKind.USER; 1_100_000_000 -> WolfDatabaseKind.MUTABLE; else -> WolfDatabaseKind.SYSTEM }
            val relative = code - base
            databaseString(kind, relative / 1_000_000, (relative % 1_000_000) / 100, relative % 100)
        }
        else -> read(code, address).toString()
    }

    fun writeString(code: Int, value: String, address: EventAddress) {
        require(value.length <= 1_000_000) { "Chaîne supérieure à la capacité native" }
        when (code) {
            in 1_600_000..1_699_999 -> { commonStrings[address.common to (code % 100)] = value; common.remove(address.common to (code % 100)) }
            in 3_000_000..3_999_999 -> strings[code - 3_000_000] = value
            in 15_000_000..15_999_999 -> { val key = ((code - 15_000_000) / 100) to (code % 100); commonStrings[key] = value; common.remove(key) }
            in 1_100_000_000..1_199_999_999 -> {
                val relative = code - 1_100_000_000
                setDatabaseString(relative / 1_000_000, (relative % 1_000_000) / 100, relative % 100, value)
            }
            else -> error("Destination de chaîne non prise en charge : $code")
        }
    }

    fun databaseNumber(kind: WolfDatabaseKind, type: Int, row: Int, field: Int): Int {
        if (kind == WolfDatabaseKind.MUTABLE) mutableNumbers[Triple(type, row, field)]?.let { return it }
        return databases[kind]?.types?.getOrNull(type)?.number(row, field)
            ?: error("Valeur DB numérique absente : $kind/$type/$row/$field")
    }

    fun databaseString(kind: WolfDatabaseKind, type: Int, row: Int, field: Int): String {
        if (kind == WolfDatabaseKind.MUTABLE) mutableStrings[Triple(type, row, field)]?.let { return it }
        return databases[kind]?.types?.getOrNull(type)?.string(row, field)
            ?: error("Valeur DB texte absente : $kind/$type/$row/$field")
    }

    fun setDatabaseNumber(type: Int, row: Int, field: Int, value: Int) {
        require(databases[WolfDatabaseKind.MUTABLE]?.types?.getOrNull(type)?.number(row, field) != null) { "Destination DB numérique absente" }
        mutableNumbers[Triple(type, row, field)] = value
    }

    fun setDatabaseString(type: Int, row: Int, field: Int, value: String) {
        require(databases[WolfDatabaseKind.MUTABLE]?.types?.getOrNull(type)?.string(row, field) != null) { "Destination DB texte absente" }
        require(value.length <= 1_000_000) { "Valeur DB texte trop grande" }
        mutableStrings[Triple(type, row, field)] = value
    }

    companion object {
        /** Only fields with explicit runtime effects are admitted, never arbitrary zero defaults. */
        val SUPPORTED_SYSTEM_READS = setOf(7, 8, 12, 13, 24, 71, 72, 73)
        val SUPPORTED_SYSTEM_WRITES = setOf(7, 8, 73)
        fun numericPrefix(text: String): Int {
            val prefix = Regex("^[\\t ]*[+-]?[0-9]+").find(text)?.value?.trim() ?: return 0
            return prefix.toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
                ?: if (prefix.startsWith('-')) Int.MIN_VALUE else Int.MAX_VALUE
        }

        fun compare(a: Int, b: Int, mode: Int): Boolean = when (mode) {
            0 -> a > b; 1 -> a >= b; 2 -> a == b; 3 -> a <= b; 4 -> a < b; 5 -> a != b; 6 -> (a and b) == b
            else -> error("Comparaison numérique non prise en charge : $mode")
        }

        fun calculate(left: Long, right: Long, operation: Int, random: (Int, Int) -> Int): Long = when (operation) {
            0 -> left + right; 1 -> left - right; 2 -> left * right
            3 -> left / (if (right == 0L) 1 else right)
            4 -> left % (if (right == 0L) 1 else right)
            5 -> left and right
            6 -> random(minOf(left, right).toInt(), maxOf(left, right).toInt()).toLong()
            else -> error("Opération numérique non prise en charge : $operation")
        }

        fun assign(previous: Long, value: Double, mode: Int, other: Int = 0): Double = when (mode) {
            0 -> value; 1 -> previous + value; 2 -> previous - value; 3 -> previous * value
            4 -> previous / (if (value == 0.0) 1.0 else value)
            5 -> previous % (if (value == 0.0) 1.0 else value)
            6 -> maxOf(previous.toDouble(), value); 7 -> minOf(previous.toDouble(), value); 8 -> abs(value)
            9 -> (Math.toDegrees(atan2(value, other.toDouble())) * 10 + 3600) % 3600
            10 -> sin(Math.toRadians(value / 10)) * 1000
            11 -> cos(Math.toRadians(value / 10)) * 1000
            12 -> sqrt(maxOf(0.0, value)) * 1000
            else -> error("Affectation numérique non prise en charge : $mode")
        }
    }
}
