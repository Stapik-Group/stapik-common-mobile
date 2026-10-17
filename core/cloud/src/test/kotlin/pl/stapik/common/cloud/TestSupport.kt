package pl.stapik.common.cloud

/** Runs [block] and returns the exception of type [T] it throws; fails the test otherwise. */
inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Expected ${T::class.java.simpleName} but got $e", e)
    }
    throw AssertionError("Expected ${T::class.java.simpleName} but nothing was thrown")
}
