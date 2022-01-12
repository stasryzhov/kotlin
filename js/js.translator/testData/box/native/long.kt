// EXPECTED_REACHABLE_NODES: 1273
// DONT_TARGET_EXACT_BACKEND: WASM
// WASM_MUTE_REASON: UNSUPPORTED_JS_INTEROP
// KJS_WITH_FULL_RUNTIME

// Test that APIs expecting Number behave correctly with Long values.

import kotlin.js.Date

fun box(): String {
    assertEquals("1970-01-01T00:00:00.000Z", Date(0L).toISOString())
    assertEquals("1899-12-30T21:29:43.000Z", Date(0, 0, 0, 0, 0, 0, 0L).toISOString())
    assertEquals(-2209075200000.0, Date.UTC(0, 0, 0, 0, 0, 0, 0L))

    return "OK"
}
