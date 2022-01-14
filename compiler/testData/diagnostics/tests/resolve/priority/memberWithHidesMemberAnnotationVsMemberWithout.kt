// FIR_IDENTICAL
// WITH_STDLIB
interface Entities<Target> : MutableCollection<Target>, Sequence<Target>

abstract class StringEntities : Entities<String> {
    fun foo() {
        forEach {
            println(it)
        }
    }
}