package me.rerere.rikkahub.data.memory

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MemorySummaryModelsTest {

    @Test
    fun parseMemorySummaryJson_handlesCodeFencedJson() {
        val json = """
            ```json
            {
              "title": "Your Summary",
              "subtitle": "A quick view",
              "sections": [
                {
                  "icon": "devices",
                  "title": "Devices",
                  "content": "Lenovo Tab M9"
                }
              ],
              "dive_deeper": [
                {
                  "icon": "code",
                  "title": "Explore Setup",
                  "subtitle": "Details about proot",
                  "prompt": "Tell me more about your Linux setup"
                }
              ]
            }
            ```
        """.trimIndent()

        val parsed = parseMemorySummaryJson(json)
        assertNotNull(parsed)
        assertEquals("Your Summary", parsed.title)
        assertEquals(1, parsed.sections.size)
        assertEquals("Devices", parsed.sections[0].title)
        assertEquals("Lenovo Tab M9", parsed.sections[0].content)
        assertEquals("devices", parsed.sections[0].icon)
        assertEquals(1, parsed.diveDeeper.size)
        assertEquals("Explore Setup", parsed.diveDeeper[0].title)
    }

    @Test
    fun parseMemorySummaryJson_handlesBareJsonWithChatter() {
        val raw = """
            Here is your memory summary:
            {
              "title": "Your Profile",
              "subtitle": "All about you",
              "sections": [
                {
                  "icon": "heart",
                  "title": "Interests",
                  "content": "One Piece, Dark"
                }
              ],
              "dive_deeper": []
            }
            Hope you like it!
        """.trimIndent()

        val parsed = parseMemorySummaryJson(raw)
        assertNotNull(parsed)
        assertEquals("Your Profile", parsed.title)
        assertEquals(1, parsed.sections.size)
        assertEquals("Interests", parsed.sections[0].title)
    }

    @Test
    fun parseMarkdownFallback_extractsSectionsFromMarkdown() {
        val markdown = """
            ## Devices
            Lenovo Tab M9 running Android 13.
            
            ## Interests
            One Piece, Dark, Eminem.
        """.trimIndent()

        val parsed = parseMemorySummaryJson(markdown)
        assertNotNull(parsed)
        assertEquals(2, parsed.sections.size)
        assertEquals("Devices", parsed.sections[0].title)
        assertEquals("devices", parsed.sections[0].icon)
        assertEquals("Interests", parsed.sections[1].title)
        assertEquals("heart", parsed.sections[1].icon)
    }

    @Test
    fun renderMemorySummaryToMarkdown_rendersCleanHeaders() {
        val data = MemorySummaryData(
            title = "Ammar Profile",
            subtitle = "Overview",
            sections = listOf(
                MemorySummarySection(icon = "devices", title = "Devices", content = "Lenovo Tab M9"),
                MemorySummarySection(icon = "heart", title = "Interests", content = "One Piece")
            )
        )

        val md = renderMemorySummaryToMarkdown(data)
        assertContains(md, "# Ammar Profile")
        assertContains(md, "## Devices")
        assertContains(md, "Lenovo Tab M9")
        assertContains(md, "## Interests")
        assertContains(md, "One Piece")
    }

    @Test
    fun promptBuilders_includeBudgetAndPayload() {
        val fullPrompt = buildFullSummarizerUserPrompt(
            memories = listOf("Uses Lenovo Tab M9", "Likes anime"),
            tokenBudget = 2048
        )
        assertContains(fullPrompt, "TARGET TOKEN BUDGET: Approximately 2048 tokens.")
        assertContains(fullPrompt, "[1] Uses Lenovo Tab M9")
        assertContains(fullPrompt, "[2] Likes anime")

        val current = MemorySummaryData(
            sections = listOf(MemorySummarySection(icon = "devices", title = "Devices", content = "Lenovo"))
        )
        val patchPrompt = buildIncrementalSummarizerPrompt(
            currentSummary = current,
            changeType = MemoryChangeType.CREATED,
            memoryContent = "Bought an external keyboard",
            tokenBudget = 1000
        )
        assertContains(patchPrompt, "CHANGE TYPE: CREATED")
        assertContains(patchPrompt, "Bought an external keyboard")
        assertContains(patchPrompt, "TARGET TOKEN BUDGET: Approximately 1000 tokens.")

        val askPrompt = buildAskOrUpdatePrompt(
            currentSummary = current,
            userInstruction = "Remove old tablet notes",
            tokenBudget = 1500
        )
        assertContains(askPrompt, "USER INSTRUCTION: \"Remove old tablet notes\"")
        assertContains(askPrompt, "TARGET TOKEN BUDGET: Approximately 1500 tokens.")
    }
}
