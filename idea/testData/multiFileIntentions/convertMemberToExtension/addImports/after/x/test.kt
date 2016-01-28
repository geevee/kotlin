package x

interface A {
}

fun A.foo() {
    throw UnsupportedOperationException()
}