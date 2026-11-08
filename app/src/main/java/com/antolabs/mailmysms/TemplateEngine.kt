package com.antolabs.mailmysms

object TemplateEngine {
    private val TOKEN_REGEX = Regex("\\{([a-zA-Z0-9_]+)\\}")

    /** Unknown tokens are left as literal "{token}" text so typos are visible instead of silently vanishing. */
    fun render(template: String, tokens: Map<String, String>): String =
        TOKEN_REGEX.replace(template) { match ->
            tokens[match.groupValues[1]] ?: match.value
        }
}
