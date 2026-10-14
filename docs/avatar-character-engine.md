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

## engine2 update

### Personality: four knobs
`Personality(energy, warmth, playfulness, expressiveness)`, each in 0..1. The engine derives
curiosity, shyness, attentiveness, mischief, calmness, tempo, showiness and blinkRate internally.
The editor exposes no sliders. Per-character defaults are in `Personality.resolve(eyeType, traits)`.

### Motion model
- Pose layers crossfade in 0.14–0.34 s (`FADE_SNAP`) through an underdamped spring
  (ω19, ζ0.62 on angles; position, squash and scale use ω16, critically damped; drowsy uses ω9, ζ1).
- Large target changes add an anticipation kick (`ANTICIPATION`): the head dips back before it goes.
- Gestures (nod, recoil, busy beats) use `snapCurve`: anticipation, fast attack, then a damped
  rebound that settles to exactly 0.
- Holds are short (most under 2 s), so there are no long stares or slow creeping turns.
- An up-right guard removes upward pitch when the head is turned right.

### Grok eye gap
`GrokEyeGap` keeps the two Grok eye paths at least 0.085 × the mark radius apart in every
expression, shape, accent and transition. `GrokEyeGapTest` asserts this across 445 frames per shape.

## engine3 update (motion review fixes)
- Expression changes are a 0.45–0.68 s sine-eased morph (13–20 frames at 30 fps). The masking
  blink on expression change is gone.
- A closed Grok eye is drawn as a thin slit (`GROK_SLIT`, 12 % of open height). The eyes never vanish.
- A final up-right guard runs on the composed look (body pitch + head gaze + eye lead), so no
  layer can produce the up-right stare.
- The motion filter is calmer: ω14, ζ0.74, anticipation 1.8. Busy turns go to 10–20°. Look-around
  makes 2–3 stops, each ≤ 16° and at most ~14° from the previous one (no head shake).
- Nods use `nodCurve`: the eyes dip first, then the head makes one eased down-and-up arc with a
  soft ~12 % settle. There is no ringing.
- `GrokEyeGap.MIN_GAP` = 13 % of mark radius. `GrokEyeGapTest` requires a ~9 % clear gap on the
  rendered pixels and fails when an eye vanishes. It covers blink and lid states, squash, and frames
  taken from a live simulation.
- `AvatarMotionVideoTest` renders at 30 fps (120 Hz simulation) with 120 dp cells.
