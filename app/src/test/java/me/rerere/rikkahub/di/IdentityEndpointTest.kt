package me.rerere.rikkahub.di

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * What address this build is allowed to dial.
 *
 * The endpoint is deployment input: a build that was never told where the
 * backend is must fail rather than fall back to the emulator loopback, which is
 * the build machine as seen from inside an emulator and resolves nowhere on a
 * phone. These pin the narrow acceptance the value gets — enough to catch a
 * missing or nonsensical address, not a URL policy.
 */
class IdentityEndpointTest {

    @Test
    fun `a supplied address is used as given`() {
        assertEquals(
            "https://ourfairy.example.com",
            resolveIdentityEndpoint("https://ourfairy.example.com"),
        )
    }

    @Test
    fun `surrounding whitespace and a trailing slash are absorbed`() {
        assertEquals(
            "http://192.168.1.20:3080",
            resolveIdentityEndpoint("  http://192.168.1.20:3080/  "),
        )
    }

    @Test
    fun `a build carrying no address is refused instead of dialling the emulator`() {
        try {
            resolveIdentityEndpoint("")
            fail("a blank endpoint must not be accepted")
        } catch (expected: IllegalStateException) {
            assertEquals(true, expected.message!!.contains("ourfairy.identityEndpoint"))
        }
    }

    @Test
    fun `an address that is not http is refused`() {
        try {
            resolveIdentityEndpoint("ourfairy.example.com")
            fail("a scheme-less address cannot be dialled")
        } catch (expected: IllegalStateException) {
            assertEquals(true, expected.message!!.contains("http://"))
        }
    }

    @Test
    fun `an address naming no host is refused`() {
        try {
            resolveIdentityEndpoint("http://")
            fail("an address with no host cannot be dialled")
        } catch (expected: IllegalStateException) {
            assertEquals(true, expected.message!!.contains("names no host"))
        }
    }
}
