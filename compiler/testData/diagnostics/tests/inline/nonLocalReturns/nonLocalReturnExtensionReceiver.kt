// !DIAGNOSTICS: -UNUSED_PARAMETER
public inline fun <A, T> ((A) -> T).foo1(arg: A): () -> T = { <!NON_LOCAL_RETURN_NOT_ALLOWED_RECEIVER_PARAMETER!>invoke<!>(arg) }

public inline fun <A, T> ((A) -> T).foo2(arg: A): () -> T = { <!NON_LOCAL_RETURN_NOT_ALLOWED_RECEIVER_PARAMETER!>this<!>.invoke(arg) }

public inline fun <A, T> ((A) -> T).foo3(arg: A): () -> T = { <!NON_LOCAL_RETURN_NOT_ALLOWED_RECEIVER_PARAMETER!>this<!>(arg) }

public inline fun <A, T> ((A) -> T).foo4(arg: A): () -> T = { <!NON_LOCAL_RETURN_NOT_ALLOWED_RECEIVER_PARAMETER!>this@foo4<!>(arg) }

class B<E, F> {
    operator fun invoke(x: E): F = null!!
}
public inline fun <A, T> ((A) -> T).foo5(arg: A): () -> T = {
    with(B<A, T>()) {
        this.invoke(arg)
        this(arg)

        this@with(arg)
        this@with.invoke(arg)

        <!NON_LOCAL_RETURN_NOT_ALLOWED_RECEIVER_PARAMETER!>this@foo5<!>(arg)
        <!NON_LOCAL_RETURN_NOT_ALLOWED_RECEIVER_PARAMETER!>this@foo5<!>.invoke(arg)
    }
}
