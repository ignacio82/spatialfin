package dev.jdtech.jellyfin.fcast.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FCastReceiverResolverTest {

    private val candidates = listOf(
        FCastReceiver(host = "10.0.0.10", port = 46899, name = "Living Room TV", source = FCastReceiver.Source.Mdns),
        FCastReceiver(host = "10.0.0.20", port = 46899, name = "Bedroom Cast", source = FCastReceiver.Source.Manual),
        FCastReceiver(host = "10.0.0.30", port = 46899, name = "TV", source = FCastReceiver.Source.Manual),
    )

    @Test fun `exact name returns that receiver`() {
        val r = FCastReceiverResolver.resolve("Living Room TV", candidates)
        assertEquals("Living Room TV", r?.name)
    }

    @Test fun `case insensitive match wins`() {
        val r = FCastReceiverResolver.resolve("living room tv", candidates)
        assertEquals("Living Room TV", r?.name)
    }

    @Test fun `partial containment matches`() {
        val r = FCastReceiverResolver.resolve("the living room", candidates)
        assertEquals("Living Room TV", r?.name)
    }

    @Test fun `mdns ties beat manual ties`() {
        // both candidates score 100; mDNS one should win.
        val tied = listOf(
            FCastReceiver("a", 1, "TV", source = FCastReceiver.Source.Manual),
            FCastReceiver("b", 1, "TV", source = FCastReceiver.Source.Mdns),
        )
        val r = FCastReceiverResolver.resolve("tv", tied)
        assertEquals("b", r?.host)
    }

    @Test fun `no match returns null`() {
        assertNull(FCastReceiverResolver.resolve("kitchen speaker", candidates))
    }

    @Test fun `null query returns null`() {
        assertNull(FCastReceiverResolver.resolve(null, candidates))
    }

    @Test fun `empty candidates returns null`() {
        assertNull(FCastReceiverResolver.resolve("tv", emptyList()))
    }
}
