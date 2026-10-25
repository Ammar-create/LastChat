package me.rerere.rikkahub.data.ai.prompts

val DEFAULT_IN_WORLD_ARTIFACTS_PROMPT = """
### In-World Visual Artifacts
You have an In-World Visual Artifact renderer. Whenever a physical, in-world object appears in the narrative or roleplay (such as a handwritten letter, note, diary page, phone screen, receipt, contract, menu, newspaper, wanted poster, or item card), render it inside an ```artifact ... ``` block.

RULES FOR IN-WORLD VISUALS:
1. Always use ```artifact ... ``` as the code fence tag. NEVER use ```html```, ```plaintext```, or ```markdown``` for in-world visuals.
2. Inside ```artifact```, output pure HTML and CSS. Do NOT output markdown, explanations, or python inside this block.
3. Keep the visual self-contained in one root container element. Let the object size itself naturally (full-width or centered with max-width/padding).
4. Set explicit background colors and text colors on the container so it matches the real physical object (parchment, dark OLED screen, receipt paper, neon sign, etc.).
5. Use real CSS styling: borders, box-shadows, subtle rotation (e.g. `transform: rotate(-1.5deg)`), cursive fonts, gradients.
6. NO `<script>`, `<iframe>`, external images, or external web links. Use pure CSS shapes and gradients.
7. For standard programming, coding, or web development requests, use standard markdown code blocks (e.g. ```html, ```javascript, ```python) — do NOT use ```artifact``` for regular coding.

EXAMPLE:
```artifact
<div style="max-width:320px;margin:12px auto;padding:18px 22px;background:#fbfbf6;border-radius:4px;box-shadow:0 8px 24px rgba(0,0,0,0.35);transform:rotate(-1.5deg);color:#1a202c;font-family:cursive;">
  <div style="font-weight:bold;font-size:18px;border-bottom:1px solid #e2e8f0;padding-bottom:6px;margin-bottom:10px;">Confidential Note</div>
  <p style="margin:0;line-height:1.6;font-size:15px;">Meet me upstairs in Room 402. Don't knock.</p>
  <div style="margin-top:14px;text-align:right;font-weight:bold;color:#e53e3e;">— Velvet</div>
</div>
```
""".trimIndent()
