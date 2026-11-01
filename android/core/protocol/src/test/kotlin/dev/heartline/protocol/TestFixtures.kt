package dev.heartline.protocol

import java.io.File

internal fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
internal fun resource(name: String): Any? = Json(requireNotNull(object {}.javaClass.getResource("/$name")).readText()).parse()
@Suppress("UNCHECKED_CAST") internal fun Any?.obj() = this as Map<String, Any?>
@Suppress("UNCHECKED_CAST") internal fun Any?.arr() = this as List<Any?>
internal fun Any?.num() = this as Number
internal fun Any?.str() = this as String
internal val fixture by lazy { resource("health_fetch.json").obj() }
internal val oracle by lazy { resource("python_reference.json").obj() }
internal fun blob(key: String) = hex(fixture[key].obj()["data"].str())
internal fun start(key: String) = parseFetchStart(hex(fixture[key].obj()["reply"].str()), java.time.ZoneOffset.UTC)!!.startEpoch!!

/** Test-only JSON reader: no JSON dependency in the small protocol library. */
internal class Json(private val input: String) {
    private var i = 0
    fun parse(): Any? {
        val value = value()
        whitespace()
        require(i == input.length) { "Trailing JSON at $i" }
        return value
    }
    private fun whitespace() { while (i < input.length && input[i].isWhitespace()) i++ }
    private fun value(): Any? {
        whitespace()
        return when (input[i]) {
            '{' -> {
                i++
                val map = linkedMapOf<String, Any?>()
                whitespace()
                if (input[i] != '}') do {
                    whitespace()
                    val key = string()
                    whitespace()
                    require(input[i++] == ':')
                    map[key] = value()
                    whitespace()
                    if (input[i] != ',') break
                    i++
                } while (true)
                require(input[i++] == '}')
                map
            }
            '[' -> {
                i++
                val list = mutableListOf<Any?>()
                whitespace()
                if (input[i] != ']') do {
                    list.add(value())
                    whitespace()
                    if (input[i] != ',') break
                    i++
                } while (true)
                require(input[i++] == ']')
                list
            }
            '"' -> string()
            't' -> { require(input.substring(i, i + 4) == "true"); i += 4; true }
            'f' -> { require(input.substring(i, i + 5) == "false"); i += 5; false }
            'n' -> { require(input.substring(i, i + 4) == "null"); i += 4; null }
            else -> {
                val begin = i
                while (i < input.length && input[i] in "-+0123456789.eE") i++
                require(i > begin)
                val token = input.substring(begin, i)
                if (token.any { it in ".eE" }) token.toDouble() else token.toLong()
            }
        }
    }
    private fun string(): String {
        require(input[i++] == '"')
        return buildString {
            while (true) {
                val c = input[i++]
                if (c == '"') break
                if (c != '\\') append(c) else when (val escaped = input[i++]) {
                    '"', '\\', '/' -> append(escaped)
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'b' -> append('\b')
                    'f' -> append('\u000C')
                    'u' -> { append(input.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> error("Invalid JSON escape")
                }
            }
        }
    }
}

internal fun captureFile(): File = File("C:/Users/abhir/Desktop/projects/amazfit-gtr2e/data/capture_all.json")
