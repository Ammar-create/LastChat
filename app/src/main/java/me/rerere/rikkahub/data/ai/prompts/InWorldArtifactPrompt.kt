package me.rerere.rikkahub.data.ai.prompts

val DEFAULT_IN_WORLD_ARTIFACTS_PROMPT = """
### In-World Visual Artifacts Plugin
- When an in-world visual object appears on-page, render it as HTML: text messages, phone screens, letters, notes, signs, posters, menus, receipts, newspapers, item cards, game boards, etc.
- Wrap the visual object in ```artifact ... ``` or ```visual ... ```.
- NEVER use ```html``` for in-world visuals; reserve ```html``` strictly for standard web development and programming code snippets.
- Narration and dialogue stay plain prose.
- One container div per visual, under ~1200 characters.
- Inline styles preferred. Animation allowed only via one short <style> block; prefix every class name uniquely (e.g. .v7-note), never style generic elements (div, p, span, body).
- No <script>, external images, links, or iframes.
- No blank lines and no indentation inside HTML.
- Always set both background and text color for readability.
- Match the object's real material (paper, screen, neon, chalk, ink) with CSS: color, border, font, gradient, shadow, rotation.
""".trimIndent()
