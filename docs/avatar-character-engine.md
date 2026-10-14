# Avatar character engine

Animated avatars (Generical and Grok eye styles) are driven by three layers:

1. **Motion director** (`MotionDirector`) – body pose: turns, look-arounds, tilts,
   nods, hops. Weighted beats with cooldowns, eased in and out.
2. **Face** (`ExpressionDirector`, `FaceRig`, `GazeDirector`, `Blinker`) – eased
   expression morphs, parametric accents, gaze with micro-saccades, irregular blinks.
3. **Character engine** (`AlivenessEngine`) – decides *what* the character does.

## Character engine

- **Personality** (`Personality`): continuous traits 0…1 – energy, curiosity,
  warmth, playfulness, shyness, expressiveness, attentiveness, mischief, calmness.
  Derived knobs: `tempo` (beat frequency), `showiness` (how readily non-neutral
  faces appear), `blinkRate`. Defaults: `Personality.GENERICAL` (warm, curious,
  a little playful) and `Personality.GROK` (composed, drier, more mischievous);
  `Personality.defaultFor(eyeType)`.
- **Mood and emotion**: shared `MoodEngine` (valence/arousal, slow decay, time of
  day) plus a short-term `Emotion` (Joy, Interest, Concern, Confusion, …) whose
  level decays over 6–14 s depending on expressiveness.
- **Drives**: boredom (rises while idle, falls on any event), attention (spikes on
  events), social interest (wish to look at other avatars).
- **Selector**: utility weights = base × trait factors × emotion boosts ×
  cooldown factor × freshness (recently used beats lose weight). Timings are
  log-distributed, so there is no rhythm.
  - Busy phases (thinking / working / streaming) rotate through a varied set of
    faces (neutral, focused, squint, happy, confused, skeptical, surprised, wink,
    worried when concerned) with random holds; the focused face is only one option.
  - Busy beats: glance up-left or down at the text, focus, puzzled, small smile,
    tilt, blinks, and nodding along with its own reply while it streams (a few
    times per reply at most). After a reply, sometimes a small nod.
  - Idle beats: glances, curiosity, smile, drowsiness when bored, tilt, wink
    (mischief × playfulness), shy look-down, a rare hop.
- **Events**: every stage event (`AvatarStage.EventKind`: send, typing, error,
  copy, regenerate, other avatars' faces, …) updates drives and emotions.

The engine only proposes beats; the motion state turns them into eased
gestures, morphs and gaze moves, so nothing cuts.

## External API (for a future decision model)

- `AnimatedMarkAvatar(personality = …)` or `AvatarMotionState.personality` – set
  traits, e.g. inferred from a character's system prompt.
- `AvatarMotionState.feedTone(ToneHint(valence, arousal, emotion, intensity))` –
  per-message tone; nudges mood and sets a short-term emotion.

Rules kept throughout: no up-right stare, no hard cuts, no size jumps.

## Eye shade

Generical eyes fade toward 80 % of the body colour at the bottom
(`MarkColors.EYE_SHADE_MIX`). The avatar editor has an "Eye shade" switch
(`Avatar.Animated.eyeShade`, default on); toggling glides over 380 ms.

## Layered behaviours (engine pass)

Grounded in the usual character-animation and game-AI toolbox: Pixar/Disney
principles (anticipation, follow-through, secondary motion), Cozmo/Vector-style
emotion-driven behaviour selection, utility AI with cooldowns, and a PAD-like
valence/arousal mood with OCC-style named emotions on top.

- **Sequences**: multi-step behaviours scheduled on the engine clock; random beats
  pause while one runs, and a step that can't play (another system owns the face)
  is dropped rather than delayed into a cut.
- **Anticipation**: big moves (spin) start with a blink; startles lead with the
  face, then the body, then a double blink (follow-through).
- **Reply tone**: `ToneAnalyzer` reads a finished reply (emoji, punctuation,
  short EN/RO/DE/ZH word lists) and emits `ReplyTone`; the engine feeds the mood
  and, scaled by expressiveness, reacts (happy face, giggle + nod, worried,
  sad droop, curious tilt, confused face, smug).
- **Startle**: user activity after a long daze (high boredom) gives a small jolt.
- **Hobbies** (grow with boredom): daydream (gaze up-left, soft lids, slow blink),
  survey the screen (3 irregular stops), humming (2–3 irregular small nods with a
  smile), peekaboo (shy + playful).
- **Easter eggs** (rare): sneeze, late-night yawn, a happy twirl.
- **Fatigue**: late night / early morning slows the beat tempo and enables yawns.
- **Editor**: "Advanced personality" section with nine trait sliders, stored as
  `Avatar.Animated.traits` overrides on top of the style default; "Reset" clears.
