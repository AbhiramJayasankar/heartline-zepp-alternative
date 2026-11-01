package dev.heartline.analytics

/** Fixture-only JSON reader. Production has no serialization dependency or reflection. */
internal class Json(private val source: String) {
    private var i = 0
    fun read(): Any? {
        whitespace()
        return when (source[i]) {
            '{' -> {
                i++
                val map = linkedMapOf<String, Any?>()
                whitespace()
                if (source[i] != '}') while (true) {
                    val key = read() as String
                    whitespace(); check(source[i++] == ':')
                    map[key] = read()
                    whitespace()
                    if (source[i] == '}') break
                    check(source[i++] == ',')
                }
                i++; map
            }
            '[' -> {
                i++
                val values = arrayListOf<Any?>()
                whitespace()
                if (source[i] != ']') while (true) {
                    values.add(read())
                    whitespace()
                    if (source[i] == ']') break
                    check(source[i++] == ',')
                }
                i++; values
            }
            '"' -> {
                i++
                val out = StringBuilder()
                while (source[i] != '"') {
                    val c = source[i++]
                    if (c != '\\') out.append(c) else when (val escaped = source[i++]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> { out.append(source.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> out.append(escaped)
                    }
                }
                i++; out.toString()
            }
            't' -> { check(source.substring(i, i + 4) == "true"); i += 4; true }
            'f' -> { check(source.substring(i, i + 5) == "false"); i += 5; false }
            'n' -> { check(source.substring(i, i + 4) == "null"); i += 4; null }
            else -> {
                val start = i
                while (i < source.length && source[i] in "-+0123456789.eE") i++
                source.substring(start, i).toDouble()
            }
        }
    }
    private fun whitespace() { while (i < source.length && source[i].isWhitespace()) i++ }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>
@Suppress("UNCHECKED_CAST")
internal fun Any?.array(): List<Any?> = this as List<Any?>
internal fun Any?.number(): Double = (this as Number).toDouble()
internal fun Any?.nullableNumber(): Double? = (this as Number?)?.toDouble()
internal fun fixture(name: String): Any? = Json(checkNotNull(object {}.javaClass.getResourceAsStream("/$name")) {
    "Missing fixture $name"
}.bufferedReader().use { it.readText() }).read()

internal fun assertTree(expected: Any?, actual: Any?, path: String = "", tolerance: (String, Double) -> Double) {
    when (expected) {
        null -> org.junit.Assert.assertNull(path, actual)
        is Number -> {
            check(actual is Number) { "$path: expected number $expected, actual $actual" }
            org.junit.Assert.assertEquals(path, expected.toDouble(), actual.toDouble(), tolerance(path, expected.toDouble()))
        }
        is Map<*, *> -> {
            val a = actual.obj()
            org.junit.Assert.assertEquals("$path keys", expected.keys, a.keys)
            for ((key, value) in expected) assertTree(value, a[key], "$path.$key", tolerance)
        }
        is List<*> -> {
            val a = actual.array()
            org.junit.Assert.assertEquals("$path size", expected.size, a.size)
            for (i in expected.indices) assertTree(expected[i], a[i], "$path[$i]", tolerance)
        }
        else -> org.junit.Assert.assertEquals(path, expected, actual)
    }
}
