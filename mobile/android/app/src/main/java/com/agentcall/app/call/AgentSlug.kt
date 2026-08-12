package com.agentcall.app.call

/**
 * Agent identity: the backend names agents with display names (\"AI Agent\"),
 * while Room profile ids are the slugified form. This is the single slug
 * definition so profile lookups never drift between callers.
 */
fun String.agentSlug(): String = lowercase().replace("\\s+".toRegex(), "-")
