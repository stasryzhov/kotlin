interface Entities<Target> : MutableCollection<Target>, Sequence<Target>

abstract class StringEntities : Entities<String> {
    fun foo() {
        <!OVERLOAD_RESOLUTION_AMBIGUITY!>forEach<!> {
            <!OVERLOAD_RESOLUTION_AMBIGUITY!>println<!>(<!UNRESOLVED_REFERENCE!>it<!>)
        }
    }
}

interface PageIterator<V> : Iterator<Collection<V>>, Iterable<Collection<V>>

fun foo(pi: PageIterator<String>) {
    pi.<!OVERLOAD_RESOLUTION_AMBIGUITY!>forEach<!> {
        <!OVERLOAD_RESOLUTION_AMBIGUITY!>println<!>(<!UNRESOLVED_REFERENCE!>it<!>)
    }
}
