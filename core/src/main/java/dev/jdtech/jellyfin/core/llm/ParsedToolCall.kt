package dev.jdtech.jellyfin.core.llm

/**
 * Backend-agnostic tool-call result used by command-parser callers.
 *
 * LiteRT-LM returns a structured [com.google.ai.edge.litertlm.ToolCall] when the
 * model decides to invoke a tool; we lift the `name` + `arguments` into this
 * plain data class so the `:player:xr` parser doesn't have to import LiteRT
 * types. AICore / Gemini Nano doesn't expose tool calling in the current
 * `mlkit-genai-prompt` beta, so its engine simply returns `null` and callers
 * fall back to the JSON-in-text path.
 *
 * Arguments are a best-effort snapshot of whatever the model filled in —
 * values can be `String`, `Number`, `Boolean`, `List<*>`, or nested `Map<*,*>`
 * depending on the OpenAPI schema the tool was registered with. Callers coerce
 * on read.
 */
data class ParsedToolCall(
    val name: String,
    val arguments: Map<String, Any?>,
)
