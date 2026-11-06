package dev.heartline.service

import org.json.JSONObject

/** A function the Alexa brain can call (OpenRouter tool calling); [parameters] is its JSON Schema. */
data class Tool(val name: String, val description: String, val parameters: JSONObject)
