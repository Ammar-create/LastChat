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

Rules kept throughout: no hard cuts, no size jumps. (Since engine6 gaze is unrestricted in every direction; see below.)

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

## engine4 update
- Grok "confused" uses two pills of the same size; only one is tilted (−18°). No pill shrinks.
- `GrokEyeGap.MIN_GAP` = 17 %. The gap test requires a ~12 % clear gap, samples tilted faces
  mid-blend on every shape, heart included, and runs 767 frames per shape.
- No nod starts while a face is morphing, and the face does not change while a nod plays. The
  asymmetric "hurt or cute" face is no longer in the busy rotation.
- Continuous micro life: breathing (squash plus bob, 3.4 s period) and micro-saccades every 0.5–1.6 s.
  No idle hold is fully still for more than ~1.4 s.
- The up-right guard runs on the final composed look, so it also covers reaction and startle gaze.

## engine5 update
- Grok Happy is drawn as clean ^ ^ arcs (stroke half-width 0.085, with a bend radius well above
  the stroke width). Closed arcs are 0.07 wide.
- Arc ↔ pill morphs go through a straight bar (`cleanBlend`): first straighten, then morph, then
  bend. The eye stays a clean capsule at every t. A blink on an arc eye only thins it.
- `EyeSafeRegion` scales the turn applied to the eyes (not the body) down only as far as needed to
  keep every eye outline point, plus a margin, inside the projected silhouette (heart, etc.).
- "hurt or cute" is out of all automatic rotation (busy, idle wink, poke reaction).
- Visible-life guarantee: if the combined head and gaze turn changes less than 8° for 1.0–1.8 s
  with no blink, the eyes make a quick glance (sideways or down). No 2.5 s window has less than
  8° of travel. Filter ω17, ζ0.7. The up-right guard starts at a combined yaw of −1°.

## engine6 update (review fixes)
- Gaze is unrestricted: every up-right guard is gone (gaze target, body filter, clip pose
  softening, final composed look). The avatar can look in any direction. Only two rules
  remain: no long frozen hold, and the eyes stay on the surface.
- No frozen holds: a slow organic drift (two incommensurate sines) runs on the head gaze, and
  micro-saccades are larger (±0.13). A visible-life glance in any direction fires when the
  eyes themselves (head gaze + eye lead, ignoring the body) have moved less than 6° for 0.9–1.6 s.
- Eyes stay on the surface: `EyeSafeRegion` margins are now 0.12 (Grok) and 0.11 (Generical) ×
  mark radius. After the turn-scaling fit, `EyeSafeRegion.nudge` slides both eyes toward the
  silhouette centre just far enough to stay inside, on every shape (sphere included).
- Grok `MIN_GAP` = 24 % of mark radius (was 17 %), enforced after every morph and pose.
- Springy nods (`springNod` / `springNodPose`): a quick dip, then a damped spring through an
  overshoot. The body compresses on the way down and stretches on the rebound.
- Real startle (`startlePose`): a ~70 ms pop up and back with stretch, a squashy landing and a
  spring settle. Startle beats are never dropped while the lifecycle changes, and the Startled
  eyes are wider. Bob and squash filter ω 24.
- Bent (^) arc eyes scale uniformly under face accents, so the two Happy arcs stay clean
  mirror images.
- `AvatarMotionVideoTest` writes `metrics.csv`: the Grok gap and the edge inset per drawn frame.

## engine7 update (engine6 review fixes)
- Busy Happy: when the eye spine changes direction (vertical pill → horizontal arc bar), the bar
  shrinks to a stub and grows out along the new direction (`barBlend`). It no longer swings
  through an angry \ / diagonal.
- Grok `MIN_GAP` = 40 % of mark radius. The metrics show ≥ 0.40 on every frame, Sad and Worried included.
- Surface: edge margin is 0.20 (Grok) and 0.17 (Generical). The fit keeps ≥ 75 % of the turn,
  then shrinks the pair about its centre (≥ 0.78; gaze motion survives), then nudges inward.
- No frozen stares: the range of the composed look over a rolling 1.5 s window has to reach
  10°, or a priority glance fires (also on the micro channel) within 0.55–0.9 s.
- Hold cap: gaze is unrestricted, but more than 1.5 s off-centre, or more than 0.85 s
  up-right, triggers an eased return near centre (body turn included).
- All up-right biases are gone: idle glances, the survey, daydream, thinking look-aside and
  ponder now go to either side and up or down. The brand-pose clips (Idle_D, Streaming_B,
  Working_E) stay unscheduled because they REST up-right.
- Startle: the pop is added after the smoothing filter (`startlePose`, ~70 ms rise, stretch,
  squash landing, spring settle), so it is not rounded off. `AvatarMotionVideoTest` asserts it plays.
- `metrics.csv` carries the expression, gap, inset and rendered eye centroid per frame
  (`engine7_report.py` turns it into the gap, inset, freeze and up-right-hold report).

## engine8
- **Perceptible life beats** (AvatarMotionState): every 1.5–2.2 s a visible beat alternates look-away (eyes 0.35–0.65 + real head/body turn 7–13°, tilt, roll) and look-back to centre. Hold cap raised to 2.4 s (up-right still 0.85 s) so it no longer cancels beats; recenter keeps 35% motion. Report: longest gap between perceptible events (≥0.08 R eye move, blink or ≥6° turn) ≤ 2.23 s in every clip.
- **Sad/Worried Grok**: inward tilt 28° → 19°, set wider (+0.015 offX, +0.03 spread); inner tops read clearly apart.
- **Heart edge**: inset measured on the true projected silhouette path; Generical margin 0.17 → 0.21 R.
- **Startle**: 0.6 s startle window forces lids fully open (no blink), Startled accent stronger (sx 1.2, sy 1.4) and 0.75 s; double-blink moved to 1.15 s after.

## engine9
- **Gaze itinerary** (AvatarMotionState): rest near centre 0.4–1.0 s → look point (eyes 0.5–0.8 of range, any direction) held 0.5–1.2 s → 60% chain to a second point → rest. Each point carries a 10–18° body/head turn, pitch tilt and (40%) a head tilt, on underdamped springs (ω≈14, ζ≈0.42) for follow-through. Hold cap resets per itinerary point (no more dead 1.5 s recenter); hard recenter is 0.5–0.75 s.
- **Breathing** squash/bob roughly 2× (visible), plus a small bounce every 3.5–6.5 s with landing squash.
- **Startle**: life targets snap to front during the startle window and resume 0.3 s after, so the clip keeps living to the end.
- **Check**: `contact_check.py` samples every 0.5 s, measures eye centre from pixels; FAIL if 5 consecutive samples move < 6 px and turn < 8°. engine9: 0 windows.

## engine10
- **Visual turn cap** (drawMark): total body + head yaw/pitch soft-capped with tanh per shape — sphere 22°/15°, heart 13°/10° (its 3D projection lumps past ~14°), other shapes 17°/12°. Life turns toned down to 6–11° body, 0.45–0.75 eye range. Non-sphere eye in-face shift raised (px 0.07→0.10) so the heart still reads its look direction.
- **Black-eye blends**: blendPairs matches spine direction (opposite spines crossed mid-blend → dot) and floors blend length at 60% of the shorter endpoint; barBlend stub keeps ≥65% of the longer bar.

## Human behavior model (engine11)

Engine8–10 chased a reviewer metric ("no 2.5 s without visible change") and the result was a
hyperactive, sped-up character. Engine11 throws that layer away (life itinerary, anti-freeze
glances, hold-cap recenters, bounces, drift sines) and rebuilds behaviour from how people
actually behave in front of a screen. Goal: a small Generical that lives in the phone —
intentional but unscripted; calm by default; stillness is allowed.

Code: `HumanBehavior.kt` (decisions) + `AvatarMotionState` / `MotionDirector` (execution).

### 1. Temperament (slow mood / energy)
State: `energy`, `boredom`, `curiosity`, `valence` (from the mood engine). Energy and curiosity
wander as Ornstein–Uhlenbeck processes (τ ≈ 1–2 min) around a baseline from the personality
knobs (energy, curiosity derived from energy/playfulness/warmth); time-of-day fatigue lowers it.
Boredom builds over minutes of quiet (≈ 150 s to saturate) and drops fast on any event.
Readable states: Tired, Bored, Calm (default), Curious, Lively.
Outputs: `activity` 0.1–0.8 (**capped: never hyperactive**), `tempo` 0.7–1.1 (move speed),
`amplitude` 0.5–1.0 (turn size), `playful` (good mood + energy + playful personality).
Why: mood should change *how* it moves over minutes, not flip per second.

### 2. Attention & perception
`Attention.perceive()` decides whether and when a stimulus is noticed:
- Probability rises with salience, falls with eccentricity from the current gaze (acuity cone),
  absorption in a task (thinking 0.75, reading 0.6, idle 0.3), boredom, low energy. Out-of-view
  ("behind") events are heard, not seen: lower probability.
- Latency 150–600 ms: longer when tired, absorbed, or the event is far from gaze; higher salience
  is faster. Touch (poke) is felt directly: 100–280 ms, never missed.
- Off-gaze / out-of-view: +0.55–1.35 s, and the response is mostly a **double-take**
  (glance, start to look back, snap back and hold, blink, sometimes Surprised).
- Low-salience events can be missed entirely (logged as `missed`).
No omniscience: everything the avatar reacts to (stage events, typing, pokes, messages) goes
through this gate; the old per-event handlers now run only *after* a percept lands.

### 3. Response repertoire
`Repertoire.choose()` picks from: None, EyesOnly, Glance, GlanceBlink, Acknowledge (nod),
Smile, CuriousTilt, SlowBlink, Sigh, and for big things Startle / dumbfounded Freeze.
Weights depend on salience, energy, boredom, curiosity, warmth, expressiveness. History per
stimulus kind: the previous pick ×0.25, anything in the last three ×0.6, so the same event
gets a different answer. "No visible reaction" is always on the table (more when tired, bored,
low salience, low expressiveness). Startle is likeliest when caught off guard (bored/tired).

### 4. Idle as human timing
`IdlePlanner` fixations are log-normal: median ≈ 4.8 s when calm down to ≈ 2.5 s when lively,
range 2–8 s. Kinds: rest near front (most), slow drift (smooth pursuit 1.4–2.8 s), thinking
stare off to the side (2–7 s), look down at the conversation, occasional side glance that
returns. Small shifts are eyes only; the head joins bigger ones (~75 % of the way, 80–140 ms
after the eyes). Micro-saccades every 1.2–4 s, tiny. Blinks: log-normal gaps (median 3.4 s,
sometimes a long stare), ~9 % doubles, rare triples. Fidgets: a 2–4° posture shift every
10–28 s / activity. Sighs only when bored or tired, every 25–65 s at most.
Up-right is only ever a short glance (≤ 0.9 s, then back to centre).

### 5. Motion vocabulary
| Action | Curve | Parameters | Why |
|---|---|---|---|
| Saccade | critically damped | ω 38 | ballistic, 30–80 ms, no visible overshoot |
| Micro-saccade | critically damped | ω 38, ≤0.02 | re-fixation inside a stare |
| Smooth pursuit / drift | ease-in-out glide | 1.4–2.8 s | eyes following / wandering, constant-ish speed |
| Head turn | critically damped | ω 7.5×tempo | heads are heavy and controlled — no wobble |
| Head turn (playful) | underdamped | ω 9, ζ 0.62 | overshoot only reads right when happy/lively |
| Body filter | critically damped | ω 8–12 by activity | pose changes land softly; ζ 0.7 only when playful |
| Nod | ease-in-out arc | 0.62–0.8 s | acknowledgement: one dip, no ringing |
| Nod (playful) | spring | springNodPose | happy agreement gets follow-through |
| Startle | snap + spring | 70 ms pop, squash, settle; eyes wide 0.6 s | reflex; the one deliberately springy reaction |
| Dumbfounded freeze | snap + rigid hold | 1.1–1.7 s, thaw on a blink | stiffness as a stylistic choice |
| Settle / layer crossfade | ease (smootherstep) | 0.35–1.3 s | returning to rest is quiet |
| Breathing | sinusoid | 4.2 s × (1.25−0.35·energy), tiny | presence without motion noise |
| Blink | ease-in close / ease-out open | ~60 / ~120 ms | real blink profile |
| Sigh | ease-in-out bump | 2.2 s, head sinks 4°, squash | boredom/tiredness made visible once |
| Fidget | critically damped | ω 6, 2–4° | weight shift, not a gesture |
| Double-take | critically damped | 0.45 s look, 0.25 s back, snap | the second look is faster |
| Curious tilt | eased hold | 1.2–1.6 s | interest, held while looking |
Anticipation (counter-move before a big turn) is only used when playful.

### 6. Expression transitions
Faces change quickly, with a style picked from context:
- **Blink swap** (38 % by default): the face changes while the lids are shut — the blink is the transition.
- **Snap** (40 %): 120–220 ms ease-out; used for reactions (Surprised always snaps).
- **Hesitate** (22 %): a partial move to ~45 %, a short hold, then settle — a micro-expression.
- **Gradual** (0.9–1.4 s ease-in-out): mood drift, tiredness, Bored/Sleeping.
The old 0.45–0.7 s uniform morph read as slow and mechanical.

### 7. Knobs (decision model, no user sliders)
energy → temperament baseline (activity, tempo, amplitude), blink rate.
warmth → acknowledging / smiling responses, social glances, attentiveness.
playfulness → playful mood gate (springy heads, playful nods, anticipation), curiosity.
expressiveness → face responses, startle / freeze likelihood, fewer "no reaction" picks.

### Motion budget
Measured as summed |Δyaw|+|Δpitch|+|Δroll| of the composed look plus eye travel per second
(`budget.py`); engine11 targets ≤ 1/3 of engine10. Measured: idle 9.8 vs 71.9 deg/s (≈1/7),
busy 9.9 vs 64.6 deg/s (≈1/6.5); eye travel 15.8–21 vs 59–69 px/s (≈1/3–1/4); squash ≈1/4.

### What was removed and why
- engine8–10 "life itinerary" (forced look every 1.5–2.2 s), anti-freeze glances, hold-cap
  recenters, periodic bounces, drift sines: they existed to satisfy a "no 2.5 s stillness"
  metric that was wrong. Stillness is now allowed; liveliness comes from timing and variety.
- Underdamped springs as the default for body/head: now critically damped; springiness is
  reserved for startle and playful moods.
- Uniform 0.45–0.7 s expression morphs: replaced by the four transition styles above.

### Verification clips (engine11)
idle 40 s, busy 30 s, reactions 38 s (same ping 6×: EyesOnly, GlanceBlink, Smile,
Acknowledge, EyesOnly, None — 310–360 ms latency; an out-of-view event noticed 1.0 s late →
double-take), startle, expr. Behaviour logs: `<clip>_behavior.csv`.
