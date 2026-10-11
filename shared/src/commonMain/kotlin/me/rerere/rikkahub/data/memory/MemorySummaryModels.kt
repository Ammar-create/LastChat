package me.rerere.rikkahub.data.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val memorySummaryJsonFormat = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
data class MemorySummaryData(
    val title: String = "Your Summary",
    val subtitle: String = "A quick view of what I remember about you.",
    val sections: List<MemorySummarySection> = emptyList(),
    @SerialName("dive_deeper")
    val diveDeeper: List<DiveDeeperAction> = emptyList(),
)

@Serializable
data class MemorySummarySection(
    val icon: String = "star",
    val title: String,
    val content: String,
)

@Serializable
data class DiveDeeperAction(
    val icon: String = "code",
    val title: String,
    val subtitle: String,
    val prompt: String,
)

@Serializable
enum class MemorySummaryCadence {
    @SerialName("immediate")
    IMMEDIATE,

    @SerialName("debounce_10m")
    DEBOUNCE_10M,

    @SerialName("hourly")
    HOURLY,

    @SerialName("daily")
    DAILY,

    @SerialName("manual")
    MANUAL;

    companion object {
        fun fromString(value: String?): MemorySummaryCadence {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: IMMEDIATE
        }
    }
}

@Serializable
enum class MemoryChangeType {
    @SerialName("created")
    CREATED,

    @SerialName("updated")
    UPDATED,

    @SerialName("deleted")
    DELETED,
}

/**
 * Parses raw LLM response text into MemorySummaryData.
 * Resilient against markdown code-fences (```json ... ```) and leading/trailing chatter.
 */
fun parseMemorySummaryJson(raw: String): MemorySummaryData? {
    if (raw.isBlank()) return null
    val cleaned = extractJsonPayload(raw)
    return try {
        memorySummaryJsonFormat.decodeFromString<MemorySummaryData>(cleaned)
    } catch (_: Exception) {
        parseMarkdownFallback(raw)
    }
}

/**
 * Renders MemorySummaryData to clean markdown for injection into conversation context.
 */
fun renderMemorySummaryToMarkdown(data: MemorySummaryData): String {
    if (data.sections.isEmpty()) return ""
    return buildString {
        appendLine("# ${data.title}")
        if (data.subtitle.isNotBlank()) {
            appendLine(data.subtitle)
        }
        appendLine()
        data.sections.forEach { section ->
            appendLine("## ${section.title}")
            appendLine(section.content.trim())
            appendLine()
        }
    }.trim()
}

/**
 * Extracts JSON substring if surrounded by markdown code fences or arbitrary text.
 */
fun extractJsonPayload(text: String): String {
    val trimmed = text.trim()
    val fenceStart = trimmed.indexOf("```json")
    if (fenceStart != -1) {
        val jsonStart = fenceStart + 7
        val fenceEnd = trimmed.indexOf("```", jsonStart)
        if (fenceEnd != -1) {
            return trimmed.substring(jsonStart, fenceEnd).trim()
        }
    }
    val genericFenceStart = trimmed.indexOf("```")
    if (genericFenceStart != -1) {
        val nextNewline = trimmed.indexOf('\n', genericFenceStart)
        if (nextNewline != -1) {
            val fenceEnd = trimmed.indexOf("```", nextNewline)
            if (fenceEnd != -1) {
                return trimmed.substring(nextNewline, fenceEnd).trim()
            }
        }
    }
    val firstBrace = trimmed.indexOf('{')
    val lastBrace = trimmed.lastIndexOf('}')
    if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
        return trimmed.substring(firstBrace, lastBrace + 1).trim()
    }
    return trimmed
}

/**
 * Fallback parser when LLM returns plain markdown headers rather than pure JSON.
 */
fun parseMarkdownFallback(raw: String): MemorySummaryData? {
    val lines = raw.lines()
    val sections = mutableListOf<MemorySummarySection>()
    var currentTitle: String? = null
    val currentContent = StringBuilder()

    fun flush() {
        val title = currentTitle?.trim()
        val content = currentContent.toString().trim()
        if (!title.isNullOrBlank() && content.isNotBlank()) {
            val icon = guessIconForTitle(title)
            sections += MemorySummarySection(icon = icon, title = title, content = content)
        }
        currentContent.clear()
    }

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("## ") || trimmed.startsWith("# ")) {
            flush()
            currentTitle = trimmed.removePrefix("## ").removePrefix("# ").trim()
        } else if (trimmed.startsWith("**") && trimmed.endsWith("**") && trimmed.length < 50) {
            flush()
            currentTitle = trimmed.removeSurrounding("**").trim()
        } else {
            currentContent.appendLine(line)
        }
    }
    flush()

    if (sections.isEmpty()) return null
    return MemorySummaryData(
        title = "Your Summary",
        subtitle = "A quick view of what I remember about you.",
        sections = sections,
        diveDeeper = emptyList()
    )
}

fun guessIconForTitle(title: String): String {
    val lower = title.lowercase()
    return when {
        "pref" in lower || "habit" in lower -> "star"
        "interest" in lower || "entertainment" in lower || "hobby" in lower || "music" in lower || "anime" in lower -> "heart"
        "tech" in lower || "code" in lower || "dev" in lower || "stack" in lower || "hardware" in lower -> "code"
        "device" in lower || "tablet" in lower || "phone" in lower || "pc" in lower -> "devices"
        "context" in lower || "goal" in lower || "current" in lower || "work" in lower || "study" in lower -> "target"
        else -> "sparkles"
    }
}

/**
 * Default system prompt for full memory synthesis.
 */
val DEFAULT_MEMORY_SUMMARY_SYSTEM_PROMPT = """
You are the Memory Synthesizer. Your job is to analyze the user's recorded memories and produce a cohesive, structured baseline profile.

CORE PRINCIPLES:
1. JUSTICE TO ALL MEMORIES: Do not ignore or discard memories. Synthesize all provided information comprehensively into coherent sections. Preserve specific details, hardware specs, preferences, quirks, entertainment choices, and boundaries.
2. DYNAMIC SECTIONS: Do NOT impose rigid, predefined categories. Create section titles that naturally fit this specific user's life (e.g. Devices, Preferences, Entertainment, Technical Stack, Current Context, Communication Style). If a category is not present in memories, do not generate it.
3. ICON ASSIGNMENTS: For each section, assign an appropriate icon tag from: "star", "heart", "code", "target", "devices", "sparkles", "profile", "book", "music".
4. DIVE DEEPER: Provide 2 to 4 interactive prompts in 'dive_deeper' that allow exploring specific threads or facets of their profile in conversation.
5. JSON OUTPUT: Respond ONLY with a valid JSON object matching the requested schema. No conversational filler or markdown explanations outside the JSON.
""".trimIndent()

/**
 * Builds the prompt instructing the model to generate a full baseline summary.
 */
fun buildFullSummarizerUserPrompt(
    memories: List<String>,
    tokenBudget: Int
): String {
    return buildString {
        appendLine("Generate a comprehensive memory summary based on the following memories.")
        appendLine("TARGET TOKEN BUDGET: Approximately $tokenBudget tokens.")
        appendLine("Do justice to all memories while staying within the target budget.")
        appendLine()
        appendLine("USER MEMORIES (${memories.size} total):")
        memories.forEachIndexed { index, mem ->
            appendLine("[${index + 1}] $mem")
        }
        appendLine()
        appendLine(
            """
            Required JSON format:
            {
              "title": "Your Summary",
              "subtitle": "A quick view of what I remember about you.",
              "sections": [
                {
                  "icon": "devices",
                  "title": "Devices",
                  "content": "Detailed synthesis of user's hardware and setups..."
                },
                {
                  "icon": "heart",
                  "title": "Interests",
                  "content": "Detailed synthesis of entertainment and hobbies..."
                }
              ],
              "dive_deeper": [
                {
                  "icon": "code",
                  "title": "Review my technical preferences",
                  "subtitle": "See what I know about your tech stack, tools and workflows.",
                  "prompt": "Let's review my technical setup and developer preferences."
                }
              ]
            }
            """.trimIndent()
        )
    }
}

/**
 * Builds the incremental patch prompt when a single memory is added/edited/deleted.
 */
fun buildIncrementalSummarizerPrompt(
    currentSummary: MemorySummaryData,
    changeType: MemoryChangeType,
    memoryContent: String,
    tokenBudget: Int
): String {
    val currentJson = memorySummaryJsonFormat.encodeToString(MemorySummaryData.serializer(), currentSummary)
    return buildString {
        appendLine("A memory change occurred. Update the existing memory summary incrementally.")
        appendLine("CHANGE TYPE: ${changeType.name}")
        appendLine("MEMORY CONTENT: \"$memoryContent\"")
        appendLine("TARGET TOKEN BUDGET: Approximately $tokenBudget tokens.")
        appendLine()
        appendLine("CURRENT SUMMARY JSON:")
        appendLine(currentJson)
        appendLine()
        appendLine("INSTRUCTIONS:")
        appendLine("1. Update, add to, or prune the relevant section(s) to accurately incorporate this memory change.")
        appendLine("2. Leave unaffected sections intact.")
        appendLine("3. Update or retain relevant 'dive_deeper' exploration prompts.")
        appendLine("4. Respond ONLY with the updated JSON object matching the same schema.")
    }
}

/**
 * Builds prompt for the conversational "Ask or update" bar at the bottom of the summary page.
 */
fun buildAskOrUpdatePrompt(
    currentSummary: MemorySummaryData,
    userInstruction: String,
    tokenBudget: Int
): String {
    val currentJson = memorySummaryJsonFormat.encodeToString(MemorySummaryData.serializer(), currentSummary)
    return buildString {
        appendLine("The user has requested an edit or update to their memory summary.")
        appendLine("USER INSTRUCTION: \"$userInstruction\"")
        appendLine("TARGET TOKEN BUDGET: Approximately $tokenBudget tokens.")
        appendLine()
        appendLine("CURRENT SUMMARY JSON:")
        appendLine(currentJson)
        appendLine()
        appendLine("INSTRUCTIONS:")
        appendLine("1. Apply the user's requested instruction directly to the summary (e.g. modify sections, update details, rephrase, or prune).")
        appendLine("2. Maintain overall coherence and do justice to all other recorded information.")
        appendLine("3. Respond ONLY with the updated JSON object matching the same schema.")
    }
}
