// The frontend resolves to the Test class.
//
// The analysis API adjusts the resolution to be the constructor as that is what the IDE expects.
//
// In this particular case, there is no PSI element for the constructor and in the IDE code,
// the final reference will therefore be to the class.
//
// This test is testing the analysis api behavior, so the resolution result is the
// constructor in Test.

open class Test

class SomeTest : <caret>Test()
