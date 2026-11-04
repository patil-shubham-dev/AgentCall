package com.agentcall.app.call

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextCleanerTest {

    @Test
    fun `collapses whitespace and trims raw recognizer output`() {
        assertEquals("Send the report today.", SpeechTextCleaner.clean("  send the   report today "))
    }

    @Test
    fun `strips only edge fillers and keeps meaning intact`() {
        assertEquals("Can you help me with this.", SpeechTextCleaner.clean("um can you help me with this uh"))
        // Interior fillers stay: edge-only cleaning never rewrites meaning.
        assertEquals("Help me with this, um, right now.", SpeechTextCleaner.clean("Help me with this, um, right now."))
    }

    @Test
    fun `adds sentence punctuation and capitalizes`() {
        assertEquals("What time is it?", SpeechTextCleaner.clean("what time is it?"))
        assertEquals("New message.", SpeechTextCleaner.clean("new message"))
    }

    @Test
    fun `pure filler phrase collapses to empty`() {
        assertEquals("", SpeechTextCleaner.clean("um uh hmm"))
    }

    @Test
    fun `blank input stays blank`() {
        assertEquals("", SpeechTextCleaner.clean("   "))
    }
}
