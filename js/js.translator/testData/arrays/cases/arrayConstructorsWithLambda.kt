package foo

fun box(): String {
    val s = Array<String>(3) { it.toString() }
    if (s.size != 3) return "Fail size: ${s.size}"
    if (s[1] != "1") return "Fail: ${s[1]}"

    val i = IntArray(3) { it }
    if (i.size != 3) return "Fail size: ${i.size}"
    if (i[1] != 1) return "Fail: ${i[1]}"

    val c = CharArray(3) { it.toChar() }
    if (c.size != 3) return "Fail size: ${c.size}"
    if (c[1] != 1.toChar()) return "Fail: ${c[1]}"

    val b = BooleanArray(3) { true }
    if (b.size != 3) return "Fail size: ${b.size}"
    if (b[1] != true) return "Fail: ${b[1]}"

    val l = LongArray(3) { it.toLong() }
    if (l.size != 3) return "Fail size: ${l.size}"
    if (l[1] != 1L) return "Fail: ${l[1]}"

    return "OK"
}
