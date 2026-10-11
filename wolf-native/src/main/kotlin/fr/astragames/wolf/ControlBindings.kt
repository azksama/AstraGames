package fr.astragames.wolf

/** Native variable-object fields, including the legacy W2 controller conversion table. */
internal object WolfControlBindings {
    private val initialKeyboard = intArrayOf(144, 145, 146)
    private val initialController = intArrayOf(0x10, 0x20, 0x40)

    /** W2 431c90/42c400 and W3 9b7440/9507c0: names follow the current raw binding. */
    fun name(system: Map<Int, Int>, stringId: Int, compatibilityVersion: Int): String {
        require(stringId in 1..6)
        if (stringId <= 3) return scanNames[keyboard(system, 51 + stringId) - 100] ?: "???"
        val bits = controllerBits(system, 51 + stringId)
        val last = if (compatibilityVersion >= 300) 320 else 315
        val button = (301..last).firstOrNull { physicalButtonBits(it) == bits } ?: return ""
        return "ボタン${button - 300}"
    }

    // The 100 labels match the independent pinned W2 CP932 and W3 UTF16 switch tables.
    private val scanNames = mapOf(
        1 to "ESC",
        2 to "1",
        3 to "2",
        4 to "3",
        5 to "4",
        6 to "5",
        7 to "6",
        8 to "7",
        9 to "8",
        10 to "9",
        11 to "0",
        12 to "-",
        14 to "BS",
        15 to "TAB",
        16 to "Q",
        17 to "W",
        18 to "E",
        19 to "R",
        20 to "T",
        21 to "Y",
        22 to "U",
        23 to "I",
        24 to "O",
        25 to "P",
        26 to "[",
        27 to "]",
        28 to "Enter",
        29 to "左Ctrl",
        30 to "A",
        31 to "S",
        32 to "D",
        33 to "F",
        34 to "G",
        35 to "H",
        36 to "J",
        37 to "K",
        38 to "L",
        39 to ";",
        42 to "左Shift",
        43 to "\\",
        44 to "Z",
        45 to "X",
        46 to "C",
        47 to "V",
        48 to "B",
        49 to "N",
        50 to "M",
        51 to ",",
        52 to ".",
        53 to "/",
        54 to "右Shift",
        55 to "Num *",
        56 to "左Alt",
        57 to "SPACE",
        58 to "Caps",
        59 to "F1",
        60 to "F2",
        61 to "F3",
        62 to "F4",
        63 to "F5",
        64 to "F6",
        65 to "F7",
        66 to "F8",
        67 to "F9",
        68 to "F10",
        69 to "NumLock",
        70 to "Scroll",
        71 to "Num 7",
        72 to "Num 8",
        73 to "Num 9",
        74 to "Num -",
        75 to "Num 4",
        76 to "Num 5",
        77 to "Num 6",
        78 to "Num +",
        79 to "Num 1",
        80 to "Num 2",
        81 to "Num 3",
        82 to "Num 0",
        83 to "Num .",
        87 to "F11",
        88 to "F12",
        125 to "\\",
        144 to "^",
        145 to "@",
        146 to ":",
        156 to "Num Enter",
        157 to "右Ctrl",
        181 to "Num /",
        184 to "右Alt",
        197 to "Pause",
        199 to "HOME",
        200 to "↑",
        201 to "PageUp",
        203 to "←",
        205 to "→",
        207 to "END",
        208 to "↓",
        209 to "PageDown",
        211 to "DEL"
    )

    fun keyboard(system: Map<Int, Int>, id: Int): Int {
        require(id in 52..54)
        return system[id] ?: initialKeyboard[id - 52]
    }

    fun controllerBits(system: Map<Int, Int>, id: Int): Int {
        require(id in 55..57)
        return system[id] ?: initialController[id - 55]
    }

    fun read(system: Map<Int, Int>, id: Int, compatibilityVersion: Int): Int {
        if (id in 52..54) return keyboard(system, id)
        val bits = controllerBits(system, id)
        val last = if (compatibilityVersion >= 300) 320 else 315
        return (301..last).firstOrNull { physicalButtonBits(it) == bits } ?: 0
    }

    fun physicalButtonBits(button: Int): Int {
        require(button in 301..320)
        return 1 shl (button - 297)
    }

    /** Invalid codes are ignored by the shipped setters, rather than assigned or clamped. */
    fun write(system: MutableMap<Int, Int>, id: Int, value: Int, compatibilityVersion: Int) {
        require(id in 52..57)
        val first: Int
        val encoded: Int
        if (id <= 54) {
            if (value !in 100..355) return
            first = 52; encoded = value
        } else {
            if (value !in 301..320) return
            first = 55
            encoded = if (compatibilityVersion >= 300) physicalButtonBits(value) else when (value) {
                318 -> 0x400; 319 -> 0x2000; 320 -> 0x400000
                else -> physicalButtonBits(value)
            }
        }
        val slots = IntArray(3) { offset -> if (first == 52) keyboard(system, first + offset) else controllerBits(system, first + offset) }
        val target = id - first
        // Both 431420 and 9b6d90 execute the swaps in this order. Retaining it also
        // preserves their behavior for duplicate slots read from an older save.
        if (encoded == slots[0] && target != 0) slots[0] = slots[target]
        if (encoded == slots[1] && target != 1) slots[1] = if (target == 0) slots[0] else slots[2]
        if (encoded == slots[2] && target != 2) slots[2] = if (target == 0) slots[0] else slots[1]
        slots[target] = encoded
        slots.forEachIndexed { index, raw -> system[first + index] = raw }
    }
}
