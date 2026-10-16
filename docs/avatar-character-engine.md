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

## Engine12 additions

### Expression transitions without stutter
engine11's `Hesitate` style (partial move → plateau → settle) read as a hitch, and a blink-masked
change could start its morph before the lids actually closed, so two systems fought mid-transition.
Now one system (`ExpressionDirector`) owns the morph, progress is strictly monotonic, a new target
queues behind a running morph (never retargets it), and the styles are:
Blink (waits for lids ≥ 85 % closed, swaps in ~50 ms; falls back to Snap after 180 ms),
Snap 120–220 ms ease-out (reactions), Soft 280–380 ms ease-in-out (self-initiated),
Gradual 0.9–1.4 s (mood drift, drowsiness).

### Character moments (`MomentPlanner`)
Rare, motivated beats between the calm stretches. Global spacing 26 s (calm) … 10 s (lively),
per-moment cooldowns, recency penalty, a weighted "nothing" option, and randomised variants.

| Moment | Motive | Motion |
|---|---|---|
| Spin | playful/happy (or very bored, lazier) | 360° yaw, underdamped spring ζ≈0.5–0.7: slight overshoot, settle |
| PartialSpin | curious / bored | turn 95–150° "to look behind", hold 0.7–1.4 s, spring back |
| Scan | curious / bored | 2–4 fixations with 0.7–1.5 s purposeful holds, then back |
| Peek | curious | critically damped lean + tilt 7–11° to one side, eyes follow |
| Stretch | quiet > 40 s and bored/tired | rise + elongate, eyes shut, hold, settle, blink |
| SleepyDrift | low energy / very bored | head sinks over 3.5–5 s, lids heavy, perks up (double blink) |
| NoticePill | curious, pill on screen | looks at the pill, maybe tilts / small smile |
| Wiggle | nice message (tone valence > 0.4), ~35–65 % | damped roll wiggle + Happy |
| SmugNod | reply finished, ~25 % | Smug accent + nod |

Spins are drawn as a card turn (silhouette narrows by |cos|, real 3D yaw only leans within the shape cap, eyes hidden on the back) so every shape, including the heart, keeps its outline through the spin.

### Up-right never rests
If the combined pose stays up-right for > 0.75 s the up component eases out (150 ms) and gaze
is pulled back near centre.

### Grok eye spacing per shape
Circle/blob is the reference and unchanged (min gap 0.40 × mark radius). Shapes with a smaller
or narrower face scale the minimum gap (`WhEyeSeat.grokGapFix`: heart 0.72, star 0.62, wedge 0.74,
flower 0.87, hex 0.88, sparkle 0.90) so separation relative to the face matches the circle.

### engine12c: rigid, flat, single-curve spins
- Real soft-3D rotation: the spin adds to the solid's yaw after the per-shape cap; the silhouette
  comes from the ring-mesh projection. The body is one flat fill, always. There is no mesh shading.
- Eyes are rigid on the front surface: seated through the same projection at the full yaw with no
  safe-region squeeze, gap push or drift. The rest-pose gap offset is baked into the surface seating
  once per spin. An eye's tangent plane is culled exactly when it turns past edge-on (normal z <= 0),
  and the silhouette clip occludes any part beyond the limb. There is no early fade.
- During a spin, gaze, head look, lead, lids, expression and the other body rotation layers are held.
  Afterwards they hand back over 0.35 s.
- The spin curve is one ease-in-out sweep to just past the stop, then one small ease back.
  Velocity is unimodal, and the only sign change is the end overshoot. Nothing re-targets it.
- Heart loft depth is 0.30, down from 0.62, so edge-on the heart is a slim lens instead of a lump.

## Engine13: motion craft per behaviour

General principles: anticipation only where a real body would wind up. The eyes lead deliberate
looks and lag inertial moves. Endings decelerate to rest; bounce is reserved for genuinely bouncy
emotions (startle, playful hop). Every moment varies its size, timing and side, and avoids
repeating its last variant. Restraint wins: when a moment could read as canned, it was cut or
made smaller.

| Behaviour | Anticipation | Main curve | Eyes vs body | Ending | Variety / restraint |
|---|---|---|---|---|---|
| **Spin** | Optional run-up (≈45–60 %, history-aware): 3–7° counter-turn plus 1.5–3.5 % crouch over 0.2–0.34 s (ease-in-out), released as the turn starts. | Velocity rises from rest on a half-cosine to a peak at 24–36 % of the turn, then falls on a longer half-cosine to exactly zero. Friction-like, unimodal, no body overshoot. 0.95–1.2 s (playful) or 1.25–1.6 s. | Rigid on the surface during the turn (face, gaze and rotation layers held). At the stop the face keeps sliding 0.05–0.1 face units in the spin direction (ease-out, 0.14–0.22 s), then glides back (ease-in-out, 0.38–0.6 s). | Dead stop. Optional shake-off (more likely when tired or playful, less likely if it happened last time): 2–3 decaying 4–5 Hz head shakes of 3.5–6.5°, eyes lagging slightly, 50 % with a blink. | Direction is random but never three times the same way. If the run-up/shake combination would repeat, one part flips. |
| Half turn "look behind" | — | — | — | — | **Removed** (read as canned). |
| **Scan** (look-around) | A blink rides the first big shift ~30 % of the time. | Saccades (fast, no bounce); the head follows bigger shifts ~100 ms later, critically damped. | Eyes lead, head follows. | Final fixation near centre with a 1.5–3 s rest. | 2–4 points with 0.7–1.5 s holds, sometimes doubling back. |
| **Peek** | The eyes go first. | The lean and tilt start 120–220 ms later, critically damped. | Eyes lead. | Critically damped return, no bounce. | 5–8° tilt (was 7–11°), 50 % Curious face. |
| **Stretch** | A small crouch (first 12 %). | Ease-in-out rise and elongation, eyes shut, hold. | Lids close after the rise begins. | Ease-in-out settle, a blink, 50 % a relaxed exhale (sigh). | Only after > 40 s quiet when bored or tired; 180 s cooldown. |
| **Sleepy drift** | — | The head sinks slowly (ease-in-out, 3.5–5 s), lids heavy. | Lids lag the head. | Perk up with a quick ease-out lift, a squint, a blink (double only 35 %). | Only at low energy or high boredom. |
| **Pill glance** | — | A saccade to the real pill. | Eyes land first; a head tilt follows 250–450 ms later (45 %). | Gaze returns by the normal planner. | 35 % small smile. |
| **Wiggle** (nice message) | — | 1.5 decaying roll cycles of 3–5° (was 2.5 cycles of 5–8°). | Eyes counter-lag slightly. | Decays to rest. | Only for clearly positive tone (valence > 0.5), 25–50 %, 40 s cooldown. Toned down (was cringe-prone). |
| **Smug nod** | — | A nod (springy only when playful). | Smug accent first. | — | ~25 % of finished replies, 50 s cooldown. |
| **Reactions** | — | Perception latency 150–600 ms; the response comes from the repertoire (including no visible reaction); intensity scales with mood and salience. | Eyes orient first. | Per motion vocabulary. | History-aware; nothing behind the avatar is noticed before it is perceived. |
| **Idle** | — | Human fixation timing (log-normal 2–8 s), slow drifts, micro-shifts. | — | — | Low motion budget. Up-right never rests (> 0.75 s eases back). |

### Generical expressions: Julian's Figma set (engine13)

`assets/avatar/generical-eyes.json` is generated from Julian's per-frame SVG export
("Generical expressions" Figma, 500×500 frames). Rect and path data, corner radii and gradients
are copied unchanged (`port.py`). The outline is drawn at the house width of 5.

| Julian frame | Engine id |
|---|---|
| Neutral | Neutral |
| Bored | Bored |
| Sad | Sad |
| Happy | Happy |
| Eyes closed | Eyes closed |
| Sad/hurt/confused | **not used** (Julian disliked it) |
| Surprised | Surprised |
| Worried | Worried |
| Suspicious/angered | angered or concentrated |
| Angry | Angry |
| Angry/pouty | pouty |
| hurt/cute | hurt or cute |
| Sleeping | Sleeping |
| Looking to the right | **confused** (raised eyebrow, "hm, really?"), plus "confused left" mirrored about x = 250. A normal expression, never tied to gaze or head turn |
| Looking up | not used (gaze is the 3D turn only) |

Only "Group 3" (playful) is kept from the old set, because no frame of his matches it.

All frames are resampled to the same 96-point outline (top-start, clockwise), so morphs between
any two frames, path or rect, are a single point-to-point blend.

**Confused** (engine13.3): Julian's raised-eyebrow frame is the only confused face, and every
confused trigger uses it:
- confused tone,
- thinking variety,
- the 🤨 skeptic in mutual glances,
- 8 % of curious-tilt responses to something odd.

The variant is chosen in `setReaction`: the raised brow goes on the side it is looking toward
(head look beyond ±0.12). Looking ahead, it alternates sides with a 35 % chance of repeating.

The extra skeptical triggers from engine13.2 (for example on surprised tone) are removed, so
confused appears no more often than in engine13.1. Grok keeps its confused capsules for both
variants.

**No gaze-shape swaps** (engine13.2, Julian): the look-right/look-up frames are not used. Eyes
keep their expression shape while looking around. The look comes only from the real 3D turn,
with the eyes riding the surface projection.

**Angry tone-down.** The Annoyed accent slant went from 0.32 to 0.14 and the upper lid from 0.60
to 0.74, per Julian's note that our angry was too angry.

**Grok** keeps its capsule set. His shapes don't map naturally onto Grok's black capsule eyes.

### engine13.1 polish

- **Recovering pause after a spin.** When a spin (and any shake-off) ends, the eyes rest near
  centre for a random 0.8–2.5 s with only micro-saccades. Idle glances and moments wait; real
  stimuli still get through.
- **No self-noticing.** The avatar no longer "perceives" its own accent events. They used to
  trigger a second look right after its own reaction (a double movement).
- **No moment on top of a reaction.** Self-started moments wait at least 3 s after any
  perceived stimulus.
- **Spin direction history fix.** The never-three-in-a-row rule now also works for leftward
  runs.
- **Grok cues for Angry and Group 3.** Angry is a mild 11° opposite tilt of the normal pills
  with a touch of spread; it never touches. Playful (Group 3) has one eye a hair shorter and
  tipped −10°. Both are small lid/tilt cues in the existing capsule style.
- **Angry stays rare.** Classifier tone "angry" maps to Concern, which shows the Worried
  accent. The Annoyed accent appears only after 3+ rapid pokes, and is milder since engine13.

## Engine14: spacing and Figma 1:1

### Grok spacing
- **One spacing per shape.** The neutral face at rest gets the circle's 0.40 gap, scaled per
  shape (`GROK_GAP_FIX`). That centre distance is baked into every expression and pose.
- **Calibration.** `GROK_GAP_FIX` is calibrated so that neutral centre distance / inscribed face
  diameter = 0.322 on every picker shape (the circle's value; the circle itself is unchanged).
  Inscribed diameter is used because it is the solid "face" of spiky and lobed shapes (cloud,
  sparkle, clover, star), where the bounding box overstates the face.
- **No expression widens the spacing.** The per-expression `offX` spreads and `spreadFor` are
  gone. Wide expressions that would come too close (flat bars, happy arcs on small faces) first
  shrink about their own centres (down to 72 %). Only the remainder is solved by the tiny
  never-touch guard (`TOUCH_GAP` 0.13 × radius).
- **Sphere.** The sphere path carries the baked spacing in `offX`.

Measured table: `lastchat-renders/engine14/grok_spacing_table.csv`. The only rows above
neutral + 3 % are guard-bound (gap = the never-touch minimum).

### Generical = Julian's Figma, 1:1
- **Circle at fit 1.** On the circle the eyes are exactly his 500×500 frames: same eye size,
  spacing and vertical placement, with no re-centring of any frame.
- **Decal mapping.** The sphere decal uses an orthographic lift instead of the arc-length map,
  so at rest it projects back exactly onto the flat design.
- **Outline.** His outline is 8 on 500, scaled with eye size. This replaces our 5.9; the
  earlier "thick outline" rejection predates his designs.
- **Shade.** The gradient runs 4 above to 4 below the eye box, like his. The shade mix is 50 %
  toward the surface (his white → #87D2E9 on #009FE0), replacing the old 80 %. The toggle is kept.
- **Pixel diff.** The diff against his PNGs, rendered with his body colour, gives IoU 0.988–0.999
  and boxes within 1 px. The remaining error is anti-aliasing only
  (`engine14/generical_figma_diff.{png,txt}`).
- **Other shapes.** Eye size scales by the shape's face tune relative to the circle, and spacing
  scales with it (gap = fit). His clear-space-to-eye-width ratio of about 0.41 therefore holds
  on every shape.

### Engine14: message actions collapse
The actions row and toolbar under chat messages now use `ExpandableContent` (one 240ms tween of height+alpha to exactly 0) inside `CollapseSpacingColumn`, so the spacedBy gap shrinks with the row instead of snapping at the end — same fix as the settings menus.

### Engine14.1: weighty spin
360° now takes ~1.8–2.0s with a peak of ~295–317°/s (was ~0.9–1s and ~385–433°/s). Velocity rises along a half-cosine over the first 22% (mass), holds momentum, then glides out over the last 40% with a friction-like (1-s)² decay to exactly zero, so there is no bounce. The run-up is 0.30–0.44s, and the eye drift is longer and scaled to the slower turn.

### Engine14.2: Generical eye size
Eyes stay Figma-faithful for shape, gradient, outline (8/500), spacing and placement, but size on every shape is scaled by `GENERICAL_FIGMA_SIZE = 0.91` (~midway between the pre-engine14 ~0.81 and full 1:1). Grok is unchanged.

### Engine14.3: Generical gap bump
Size stays at 0.91× Figma. Interocular gap is `fit × GENERICAL_GAP_OVER_FIT` with `GENERICAL_GAP_OVER_FIT = 1.04` (~4% more distance). Grok unchanged.

## Engine15: intentional aliveness

The root fix: nothing on the face happens "to do something". Every change has a cause, and
idle looking is attention, not random glances.

### Behaviour model
- **State**: mood (valence/arousal), temperament, an attention episode, and a short
  recent-event memory (`remember` / `recently`: "nice", "notice", "ack", "face:X").
- **Causes**: external (message tone, typing, poke, a message arriving, the pill, a
  neighbour avatar) or internal (noticing something during an attention episode, a
  thought completing, boredom settling after a long stillness, afterglow of a recent nice
  moment, recovering from a spin). Every face change is logged as
  `expr from→to cause=…` or `face from→to cause=…`; the audit asserts there are no
  `cause=unspecified` changes.
- **Micro-stories** (`tell` / `trainUpdate`): 1–3 beats with perception delays, e.g.
  - notice → (0.45–0.75 s) curious → (0.7–1 s) Happy (soft) → nod — "likes what it noticed";
  - notice → curious (+ sometimes a tilt);
  - notice → just a blink;
  - acknowledge → nod → blink.
  While a story runs, spontaneous engine beats wait.
- **No cuts across the mood map**: low → bright (Bored/Sleeping/Sad/Eyes closed/Worried →
  Happy/Group 3/playful) always goes through a blink-swap to Neutral ("notice"), a curious
  accent, then the target. Bright → low settles through ~1.2 s of neutral. The busy-face
  rotation also never jumps Bored/Sad/Worried → Happy.
- **Removed**: the random idle mood faces (Happy/Glee/Smug/Curious/Worried rolls) and the
  idle engine beats that were random faces or glances (curious, smile, glance, survey,
  shyLook — curious/smile remain only while a matching tone emotion is active).
  Stillness and holding a face for seconds is the default.

### Looking around = attention episodes (`IdlePlanner.nextAttention`)
1. A long rest looking roughly ahead (4–12 s, never up-right).
2. An interest: an imagined point anywhere in the full gaze range (sides/below favoured;
   up-right only briefly), saccade with the head, held 1–3 s (micro-saccades on top).
   25–45 % of interests are "noticed" (a story above).
3. Then one of: soft drift away (pursuit), a slow pan across 2–3 related points in one
   direction (pursuit, 0.5–1.4 s holds), an acknowledge (nod + blink), or just look back.
4. Back to rest (slow pursuit).
Eyes lead; the head follows 120–220 ms later and carries ~80 % of the turn on its own
spring (lead-lag, not lockstep). Gaze is only ever the real 3D turn with eyes on the surface.
The Scan moment is now a pan across related points with pursuit, not random teleports.

### Spins
Own cadence: every 45–90 s of calm (×0.65 when lively), only when nothing else is going on
(no reaction/story/social/focus, not recovering). Every spin path (cadence, poke flurry,
twirl beat) uses the engine14.1 weighty spin with run-up / eye drift / shake variants and
the recover pause; the old plain-tween gesture spin is no longer used. Audit (600 s idle,
5 seeds): 7–10 spins ⇒ one every ~60–85 s.

## Engine16: the full character (episodes, spin axes, rest, eye colour, editor)

Goal: a quiet little character that is alive *for a reason*. Stillness is the default; the
catalogue is large but sparse, so a user keeps discovering beats for hours. Every idea was
filtered through "would a small character on a phone do this, with a beat of thought?" —
anything showy or busy-for-its-own-sake was cut or made very rare.

### Idle episode planner (`IdleEpisodes.kt`)
One planner owns every *self-started* idle beat (it replaces the engine12 spontaneous
`MomentPlanner` roll; that class now only serves event moments like Wiggle / SmugNod).

- Checks every 3.5–8.5 s, only when **free** (idle lifecycle, no reaction / train / social /
  focus / spin / recovery / rest, ≥ 3 s after any outside stimulus).
- Global spacing 30 s calm … 14 s lively (×1.3 when drowsy). "Nothing" is always the
  heaviest single option; choosing stillness backs off half a spacing.
- Weight = tier × reason(context) × cooldown² × history (last pick ×0.15, recent ×0.5).
  Reason 0 = cannot happen (e.g. RestEyes needs drowsy + 90 s untouched; Smug needs a nice
  moment in the last 2 min; ContentSlowBlink needs good mood).
- Tiers: Common 1 · Uncommon 0.38 · Rare 0.1 · VeryRare 0.03.

| Episode | Tier | Reason / what it looks like |
|---|---|---|
| CuriousLR | Common | look left, then right, back to rest (sometimes a curious brow), maybe a blink |
| CheckOnYou | Common | looks at the person (front, slightly down); soft smile if mood is good, else a blink |
| TiltChain | Common | eyes to a point, soft head tilt one way, then the other, straighten |
| Scan | Common | slow pan across a few related points |
| SkepticalLR | Uncommon | look left with the left brow raised (`confused left`), then right with the right brow (`confused`), back to rest, blink |
| HmmLean | Uncommon | small lean + puzzled eyes toward something low; sometimes a "got it" nod |
| DoubleTake | Uncommon | glance, start back, snap back (sometimes surprised) |
| Peek | Uncommon | leans to peek to one side |
| FollowSomething | Uncommon | eyes track something drifting across (smooth pursuit), lose it, blink, maybe tilt |
| LookUpRemember | Uncommon | looks up-**left** (never up-right), puzzled … "ah" nod |
| ContentSlowBlink | Uncommon | cat-like slow blink with a soft smile (good mood only) |
| NoticePill | Uncommon | looks at the activity pill (when present) |
| Stretch | Rare | stiff after stillness: stretch up, eyes shut, exhale |
| Yawn | Rare | rises, head back, eyes squeezed (`Eyes closed`) ~1.5 s; 50 % a little head shake + blink-blink + squint to wake up, else a slow blink |
| SleepyDrift | Rare | head sinks … catches itself |
| RestEyes | Rare | after a long untouched spell: lids close slowly (head dips a touch), 5–10 s; may doze (`Sleeping`, 35–80 s) then wake on its own (sometimes with a yawn) |
| WorriedGlance | Rare | glance down-side, worried, blink, back, sometimes a reassured nod |
| ComeToSenses | Rare | zoned out → blink-blink, small head shake, squint, back to the present (no spin) |
| Smug | VeryRare | remembers a nice exchange: small smug nod |
| LookAround360 | VeryRare | long survey far-left → far-right with a tilt |
| Sneeze | VeryRare | scrunch, tiny recoil, blink-blink |

Every episode is logged (`episode X (tier) why=…`) and played as a `tell()` train, so each
step is logged too; any face change inside carries its cause (e.g. `skeptical: sizing
something up on the left`, `yawn (tired)`, `dozed off while resting eyes`).

### Resting eyes / dozing / waking
`restPhase` 1 = resting eyes (lid → 0.06, head dips ~5°, slower breath look), 2 = dozing
(`Sleeping` face, lid 0). Any outside activity (poke, typing, events) wakes **gently**:
lids open over ~0.5 s, WakeSquint + double blink, 35 % a small wake shake. Its own face
accents no longer count as activity (they used to reset the quiet timer, so drowsiness
never set in during a long idle).

### Spins: only when lively, and not always horizontal
- **Idle never spins.** The engine15 45–90 s idle cadence is gone, the aliveness "twirl"
  is disabled, and an aliveness spin beat is only honoured when not idle (or right after a
  nice moment / pokes). Audit asserts 0 idle spins.
- Spin triggers (`trySpin(cause)`, ≥ 20 s apart): poked a lot (6), reply finished in a good
  mood (14 %, ≥ 50 s since last), a really nice message in a playful mood (30 % instead of
  the wiggle), rarely while streaming in a great mood (15 % per 20–40 s check, ≥ 90 s).
- `SpinPath` varies the axis (history-aware, never the same twice in a row):
  Turn 0.28 (vertical) · Top 0.2 (turn leaning into it like a top) · Cartwheel 0.18 (in-plane
  roll) · Flip 0.16 (forward/back somersault) · Tumble 0.1 (diagonal yaw+pitch) · Barrel 0.08
  (roll with a quarter-yaw twist). All share the same weighty timeline (run-up, half-cosine
  rise, momentum, friction glide, follow-through — vertical for a flip — optional shake-off),
  and all end exactly at rest. Flips / tumbles / barrels are ~5 % slower (more mass).
- Recovery varies: after a non-Turn path 30 % brief Dizzy; else blink-blink, a pleased look
  (good mood), a small nod, or simply still. A shake-off already counts as recovery.

### Busy faces
Thinking rotation gains `Eyes closed` (concentrating), `pouty` (effort), `confused left`,
and a little `Worried`; still history-aware with holds, never stuck on one face; never
low → Happy directly.

### Generical eye colour
The eye outline is now **exactly the eye colour** (it was contrast-nudged against the shade
colour, so with shade off — fade == eye — it was always pushed toward white/black, and some
custom colours got a different rim). Fill gradient top = eye colour, bottom = mix toward the
surface (unchanged, `EYE_SHADE_MIX`), shade toggle unchanged. The *default* Generical eye on
a near-white body is ink (#1C1C1E) instead of invisible white.

### Avatar editor
Stage card with the live avatar on a soft spotlight of its own colour and a "Eyes · Shape"
caption; sections as rounded cards (Shape → Colour → Eyes); shape tiles 54 dp with a
body-colour ring when selected; swatches with a selection ring (no check glyph over the
colour); a colour-wheel "custom" swatch that fills with the chosen colour; a segmented
Generical/Grok control with tiny live previews; full-width Save with a pinned bar.

### Audit (AvatarMotionVideoTest.behaviourAudit)
5 seeds × 10 min untouched idle + one untouched hour. Asserts: spins = 0, uncaused face
changes = 0, low → bright cuts = 0. Writes `audit_summary.csv`, `audit_hour_episodes.csv`.

### Engine16.1 (Julian's review)
- Spin paths reduced to **Turn 0.45 · Top 0.33 · Flip 0.22**. Removed Barrel (flat clock-hand
  rotation), Tumble (too wild, upside down) and Cartwheel (pure screen-plane roll — also reads as
  clock rotation). Test: no path rolls in the screen plane by more than the Top lean (16°).
- Grok expression shapes (Generical untouched): no lopsided faces. `confused` / `confused left`
  → new symmetric `hm` (both pills a little shorter); `hurt or cute` (wink) and `Group 3`
  (playful) → the clean `happy` ^ ^ arcs. Face accents on Grok are symmetrised
  (`FaceParams.grokSymmetric`: sizes / lids averaged, only the mirrored rotation kept), so
  Curious / Puzzled / Dizzy / Worried no longer make one eye crooked.
- Grok eyes that are already closed (`sleepy`, `happy` arcs) ignore blinks / lid in proportion
  to how far the morph is into them — closed eyes never "blink".

## Engine16.2: live editor, seamless morphs, gaze holds

**Editor layout** (`AnimatedAvatarEditor.kt`)
- Everything is centred on the phone-width column: the preview, caption, section titles, and the shape grid and colour/eye swatches (centred wrapping `FlowRow`s with no hidden horizontal scroll), plus the eye toggle and the shade row.
- The Shape section has no card; the shapes sit on the sheet background. Colour and Eyes keep their cards.
- The preview stage has no glow (a plain `surfaceContainer` card).
- Cancel/Save is a floating pill. It reuses the floating bar style of the Lorebooks/Skills settings screens: `Surface` with 28 dp corners, `surfaceContainerHigh`, tonal elevation 6, shadow 8, 4 dp padding, and circular items. The primary item (Save) is `primaryContainer`.
- The preview is the live engine. `livePreview=false` exists only for the static Robolectric harness.

**Seamless morphs** (`AnimatedMarkAvatar.kt`)
- `drawMark(..., sink = MarkGeom())` captures the geometry instead of drawing it: the projected body silhouette, the seated eye paths with their gradient axes, the size, and the colours.
- On a shape or eye-style change, the last drawn geometry is frozen as the "from" state. `drawMorph` then blends it toward the live target over 560 ms:
  - contours are resampled (`resamplePath` first union-simplifies multi-contour 3D hulls, then takes the largest-area contour) and blended point by point
  - start points are aligned for the least travel
  - the Generical outline fades in or out, and the eye gradient and colours tween
- Body colour, eye colour and the shade tween over 380 ms.
- Interrupting a morph starts from what is on screen, so there is no pop.
- The old 0.94 size "settle" is gone.
- `MorphSeamTest` checks that the morph endpoints cover exactly what `drawMark` draws, for all shapes, both eye styles and turned poses.

**Editor reactions** (`AvatarMotionState.editorCue`)
- Each control change sends an `EditorCue(kind, at = control centre)`. It is perceived after 0.25–0.5 s.
- About 55% of changes get a glance with a real head turn toward that control, rate-limited to one per 1.6 s.
- Three or more changes within 4 s count as browsing: the avatar gets a curious look, with an 8 s cooldown.
- An eye-style swap sometimes carries a blink.
- About 1.3 s after the last change, at most one after-beat plays:
  - after a shape change, a look down at itself (30%)
  - pleased Glee
  - a nod
  - or it just settles

**Idle gaze** (`IdlePlanner.restFixation` and the `explore` branch)
- Rest directions vary:
  - front 40%
  - side 32%
  - low-side 14%, held at most 9 s
  - up-left 10%, held at most 10 s
  - slightly up 4%
- It never rests up-right.
- 20% of rests are long holds of 12–24 s (kind `*-long`).
- Area exploration: 3–5 eye-only fixations within ±0.12/±0.08 of a comfortable region (|x| ≤ 0.5, not low), each held 0.6–3.2 s.
- Debug logs: `fix <kind> → (x,y) hold`.

Renders are in `/workspace/lastchat-renders/engine16.2/` (box).

## Engine16.2b: gaze specials made very rare, setup-style editor buttons

**Gaze.** The "gaze-staying" moments from 16.2 are now extremely rare special moments. These are:
- long 12–24 s holds
- resting anywhere other than roughly ahead (side, low-side, up-left, slightly up)
- area exploration

How it works:
- `IdlePlanner.special()` puts all of them behind one shared cooldown of 15–35 min of idle time (counted as the sum of fixation time).
- Once the cooldown has passed, each rest or interest decision has a 2% chance to spend it.
- The first special can come 5–35 min into idle.
- Otherwise rests are front/roughly ahead, as before 16.2.
- When a special does happen, the old mix applies (35% of them are long holds).
- `gazeSpecialsAudit` checks this over 60-min runs: planner awake on 8 seeds, plus the full engine on 4 seeds. It asserts ≤5 specials per hour.

**Editor buttons.** Cancel/Save now belong to the first-time-setup button family:
- 52 dp `AppShapes.ButtonPill` pills, side by side with a 12 dp gap
- Cancel is outlined with a 3 dp soft ring; Save is filled `primary` with a check icon
- bold `titleMedium` labels
- spring press scale
- a soft fade-to-sheet gradient behind them

They are implemented in `EditorActionButton`.

`SetupButton` in onboarding is split into a haptics wrapper plus `SetupButtonFace` (the visual only, internal). Onboarding behaviour is unchanged. The split lets `renderSetupButtons` draw the real setup look next to the editor.

## Engine16.2c: editor fixes (dismiss, surfaces, white eyes, flicker, morph cost)

**Scroll closed the editor.** Root cause: `ModalBottomSheet` drag-to-dismiss. When the scrolled content reached its top, the leftover downward drag/fling went to the sheet through nested scroll, and the sheet hid.
- Fix: `sheetGesturesEnabled = false`. Content scrolling can no longer move the sheet.
- Dismissal is explicit: Cancel, system back, or tapping the scrim.

**Surfaces.** Cards, shape tiles, the eye-style track, the caption pill and the Cancel ring use `softSurface(k)`, a small step from the sheet colour (`surfaceContainerLow`) toward `surfaceContainerHigh`. The steps are 0.2–0.55, so the surfaces are low contrast in light and dark themes.

**Eye defaults.** Both Grok and Generical default to white (`DEFAULT_GROK_EYE_HEX = #FFFFFF`). The near-white-body → ink auto-switch is removed. The swatch order is identical for both styles: white (default) first, then ink, then the colours.

**Morph first-frame flash.** Root cause: on the change frame the morph animation still read 1. It can only snap to 0 inside the `LaunchedEffect`, which runs after that frame is drawn, so the target shape was drawn for one frame.
- Fix: `MorphHolder.signatureChanged()` sets `forceZero`, which pins progress at 0 until the effect has snapped.
- The "from" geometry is captured in that same draw, from the last drawn shape (or from the current blend, if a morph is interrupted).
- The draw logic lives in `drawLive()`, the code path the app uses.
- `MorphSeamTest.firstMorphFrameIsPreviousShape` checks that frame 0 matches the previous shape. Only a ≤1 px outline ring may differ, and frame 0 must be far from the target.

**Morph cost.** Root cause of the lag: a turned mesh body's projected hull is about 770 face contours. The resample union-simplified it with `Path.op` every frame, about 460 ms per call, roughly 100 ms per eye-style morph frame on a turned square. The fixes:
- The body is now a radial silhouette: `radialSilhouette`, the max radius in 288 fixed angle bins, a single pass. Blending is per-angle, so the alignment search is gone too.
- In steady state nothing is captured per frame; capture happens only on the change frame.
- Colour, eye-colour and shade tweens are read in the draw phase, so there is no recomposition per tween frame.
- `MorphBenchTest` logs the per-frame costs.

## Engine17 — continuous motion & smooth outlines

**Why ("manual gearbox"):** the engine16 `ExpressionDirector` never let a new face interrupt a
running morph: it queued the target until the morph finished *plus* a 0.25 s dwell, then started a
fresh two-pose lerp from rest. Every change of mind therefore froze the face on a plateau and then
restarted (measured: 46 stall frames in a 6.5 s expression train). Reactions were 120–220 ms
ease-out "snaps"; face accents ran on an underdamped ω17/ζ0.62 spring (wobbly pops); gaze holds
were dead still between saccades; the spin had a constant-velocity middle third.

**Expressions — layered blend.** The face is `base → layer1 (w1) → layer2 (w2)`
(`MotionFrame.exprFrom/exprTo/exprT` + new `exprTop/exprTopT`). A new target is a new layer that
grows over whatever is currently displayed, starting the very next frame. A change of mind back to
the face underneath makes the newest layer *retreat* along its own path (smootherstep from its
current weight). A finished layer collapses everything beneath it. Only a third change inside one
morph whose oldest pose is still >4 % visible waits (≤ a few frames). Eases: reactions quartic-out
0.24–0.34 s (quick attack, long soft landing — poke bounce kept), self-initiated smootherstep
0.48–0.66 s, mood drift 1.0–1.5 s. Blink-masked swaps are unchanged (Generical blinks kept).
Renderers: `GenericalFace.outline(..., top, topT)` (also fixes the closure index key mid-morph) and
`WhSphereEyes.morphPair(..., top, topT)` via `cleanBlend`; Grok closed-eye lid suppression
includes the top layer, so closed Grok eyes still never blink.
`ExpressionContinuityTest` compares with a verbatim legacy copy: max per-frame pop 9.2 → 6.6,
stall frames 46 → 6, max jerk 9.2 → 5.1.

**Gaze.** Continuous fixational drift (incommensurate sines, random phases) on eye (~0.01) and
head (~0.02) targets: holds are alive, never plateaus. Saccade/head-lead/pursuit springs unchanged;
front rest default, specials still via `IdlePlanner.special()` (rare), up-right still glance-only.

**Face accents.** Spring softened to ω11/ζ0.82 (shape) and ω14/ζ1 (offset).

**Spins.** `spinCurve` is the CDF of a smooth asymmetric velocity bump v ∝ u^1.5(1−u)^3.2 (peak
≈⅓ in, long frictional glide), no linear plateau; main turn 2.05–2.3 s. Still never in idle.

**Outlines.** `SmoothPath.closed` (uniform Catmull-Rom → cubics) replaces the lineTo polylines in
Generical seated eyes, Grok sphere/mesh eyes, `radialPath` and `blendContours` — no facets at
editor size. `ArtifactStripTest` renders the strips.

**Chat input.** Hint text uses `chatInputHintColor()` = onSurfaceVariant @ 50 % (both themes).

## Engine17.1 — Grok audit, one-piece morphs, shake, gaze rules

**Grok audit** (`GrokAuditTest`: 16 likely transitions × t 0…1 and a blink row per expression, on
circle/heart/square). Findings at 0d6bf864: Neutral→Bored and Angry→Sad swung through `\ /`
diagonals (straight↔straight pairs bypassed the stub guard); arc↔pill changes were staged
(straighten → turn → bend) with flat-bar plateaus and pops (Happy→Surprised, Happy→Sad,
Neutral→Happy, Neutral→Eyes closed); blinks squashed pills into dots / hairlines (Sad: pointy specks,
Surprised: squarish blobs); happy arcs thinner than the neutral stroke; "angered or concentrated"
reused the bored bars.
Fixes:
- `WhSphereEyes.smoothBlend`: every capsule pair morphs in ONE continuous motion — spine decomposed
  into chord centre / angle / half-length / bend profile, all blended at once; large turns get a
  single smooth length dip (sin πu) so the stroke never reads as a diagonal. `barBlend`/`straight`
  stages removed.
- Blink: `EyeState.closeK` — straight eyes squash (radial per-angle blend) into a soft lid bar at
  the lower middle, about eye-wide, gentle ‿, following the eye's tilt (Generical-like). Arcs only thin.
  Closed Grok faces still never blink.
- `HAPPY_W` 0.085 → 0.097; new Grok "focused" (shorter pills, 6° inward) for "angered or concentrated".

**Worried retired** (Julian): the worried-neutral face is no longer used in either style.
`EyeStyle` maps "worried" → "angered or concentrated"; `MarkLifecycle.Failed`, the Concern
emotion beat (`concern`), the error event and the `ConcernGlance` episode (was WorriedGlance) all
use the calm concentrated face (log causes: "error event: concentrates on it", "concern glance:
concentrated").

**Spins:** expressions keep blending during a turn (changes there use the soft blend, never a
blink swap); no frozen face.

**Head shake:** one shared `wakeShake(amp, cause)` for wake, come-to-senses, yawn and the spin
shake-off: eyes squeeze shut (0.14 s), 2–3 slow decaying swings (1.7–2.1 Hz, 7.5–11°, soft attack),
eyes open with the settle, then a blink. Logged as `head-shake cause=…`.

**Gaze rules:** interest/explore/think/ack glances are ≥ 0.24 from rest (pushed out along their
own direction, never up-right); `focus()` skips near-rest glances while resting; holds scale with
the move (planner ≥ 0.45 + 2.2·d, focus ≥ 0.35 + 1.6·d); every return to rest is an eased glide
(`glideHome`, 0.55–1.15 s by distance). `GazeAuditTest` reports near-rest glances (must be 0),
minimum glance offset and peak return eye speed.

## Engine17.2

- **Squircle / corner smoothing (question).** Preserved. All 26 eye shapes in the asset match the Figma export; path eyes (Bored, Sad, Happy, Angry, pouty, …) carry the Figma smoothing as ~3 cubics per corner and render within ~0.05 px of the export. Neutral / Surprised / Looking up / Eyes closed / Sleeping are exported as `<rect rx>` (pills), so they never had smoothing to lose. Morphs and blinks lerp between those true samplings.
- **Streaming token fade (richtext `Markdown.kt`).** Opacity now outlasts the blur: settle time × `STREAMING_ALPHA_STRETCH` (1.4), the blur finishes at 1/1.4 of it, and alpha = smoothstep(t)^`STREAMING_ALPHA_EASE_POWER` (1.6). Alpha is ≈0.70 when the blur clears and keeps ramping. Same path for prose and code. Capture: `engine17.2/stream/stream_fade_before_after.png`.
- **Shapes removed.** House and wedge (triangle/cone) are gone from the picker and the engine. `MarkShapes.RETIRED` (house, wedge, plus the earlier teardrop/droplet) maps saved ids to `blob` via `MarkShapes.migrate`, which `resolve`, the editor and `AnimatedMarkAvatar` use. Test: `ShapeMigrationTest`.
- **Morph resolution.** Root causes: the morph body was a max-per-angular-bin radial silhouette (stair-stepped, wobbly edges), and morph eyes were resampled to 64 points. Now `radialSilhouette` interpolates the exact contour crossing at each bin-centre angle (falling back to per-bin max for tiny contours), `RADIAL_STEP` 1.5→3 (the old step was already capped by the 400-sample limit on big bodies), and morph eyes use 96 points like the static ones. Bench (median/p95 ms, before → after): shape morph 2.35/3.26 → 2.29/2.77, eye-style morph 4.56/5.50 → 3.90/4.41, change frame 9.72/10.54 → 8.27/9.25, steady 3.68 → 3.84 (noise). Strips: `engine17.2/morph_{before,after}/morph_res.png`, zoom `morph_zoom_before_after.png`.
- **Extreme-contrast safety outline (`ContrastGuard.kt`).** Body vs backdrop and eyes vs body. Strength = (1 − smoothstep(1.12, 1.30, WCAG contrast ratio)) × (1 − smoothstep(0.06, 0.13, RGB distance)), so only near-identical pairs get it; every normal pairing scores exactly 0 (`normalColoursNeverOutlined`). It's a flat, thin stroke (body 2.2 % of the head radius, eyes 1.8 %) in the colour pushed toward white (dark pairs) or black (light pairs). It's computed from the animated colours, so it fades with colour glides. It strokes the outer silhouette of the projected body and the seated eye paths in static, live, morph and spin draws. The Generical eye outline is unchanged (eye colour) otherwise. Backdrop: `ContrastGuard.LocalAvatarBackdrop`, default `colorScheme.background`; the editor provides its container surface. Renders: `engine17.2/contrast/`.
- **Editor container surfaces.** The drawer search bar is `LastChatDrawerSearch` (ui-core), a `TextField` with `TextFieldDefaults.colors()` whose container is M3's default `colorScheme.surfaceContainerHighest`. The editor's `softSurface` now returns exactly that token in both themes (light #DEE4E0, dark #303634 on the default preset). Render: `engine17.2/contrast/surfaces_drawer_vs_editor.png`.

## Engine18 — the avatar mind

Engine18 puts a "mind" between everything that wants the avatar to move and the motion itself (`AvatarMind.kt`). Systems no longer change the face or body directly. They propose **intentions**, and a single **director** decides what lands, when, and for how long.

```
event → Perception (noticed after a mood-varying delay)
      → Appraisal (what it means here)
      → Intention {lane, priority, cause, expiry}
      → MindDirector (owner, safe point, hold, budget, cross-lane rules)
      → motion lanes: Body · Gaze · Face · Lids · Spin
```

### Pass 1 — director, lanes, audit
- **One owner for the face.** A landed face change (expression morph or accent) owns the face. It starts its **hold** once its morph has *settled*. The hold is `MindRules.hold`: 0.7–2.6 s, longer when calm or low-energy, shorter when lively, scaled by expressiveness and patience. Weaker proposals wait in the **priority queue** and **expire** (reaction 3 s, accent 1.2 s, forced accent 2.5 s, plan 2.5 s). A stronger one (poke P3, error P4) only skips the hold. It still waits for the **safe point**: no morph running, ≥ 0.42 s since the last eye change, ≥ 0.16 s after a big gaze shift ("gaze leads, face follows"). Nothing cuts a morph mid-way any more; the engine17 retreat/third-layer paths are never reached in normal play.
- **Reactions** keep their countdown paused until they are actually on screen. A reaction that never reaches a safe point expires and is logged.
- **Lids lane.** No blink while an expression morphs. Requests are deferred and land right after the morph settles; spontaneous blinks wait. A morph waits for a blink already under way ("blink just before"). The blink-masked swap (Style.Blink) is the only morph under closed lids. Slow blinks and the closed-eye head shake also start only on a settled face.
- **Budget.** Per rolling minute, spontaneous changes (≤ P1) are limited to `MindRules.budget`: 2–9, idle ≈ 4, busy ≈ 6, scaled by expressiveness and arousal. Everything below P3 is under a hard cap of 14. Pokes are exempt, and returning to rest is not new activity. Aliveness beats (other than blinks), idle episodes, glance-backs and non-forced accents all ask first.
- **Spins.** Never self-started while idle (blocked and counted). **Never while angry, sad, pouty, annoyed or bored**, including 2 s after such a face and while mood valence < −0.25. A negative face also can't start *during* a spin. The "fine, you win" spin after a long poke burst now comes *after* forgiving, only once the face is no longer cross; if that doesn't happen within 6 s, it never comes.
- **Planner.** Beat plans (notice → pause → react → settle; the old `tell` trains) carry their cause. A new plan replaces a running one only at a safe point: next beat ≥ 0.35 s away and no morph. Otherwise it is queued.
- **Rare behaviours.** Stretch 20 min, Yawn 25, SleepyDrift 15, ConcernGlance 20, ComeToSenses 18, Smug 30, LookAround360 40, Sneeze 50 idle minutes, strict. A fresh avatar starts part-cooled, so the first one comes after 60–80 % of its cooldown. RestEyes, a drowsiness response, keeps 10 min.
- **Causes.** Every handler runs under `because(cause, priority)`. Every face, accent, spin and beat is logged with its cause.

### Pass 2 — perception, appraisal, mood
- **Perception.** The repertoire's latency is scaled by `MindRules.perceptionScale`: ×0.6–2.2 (alert and curious = quicker; tired or drowsy = slower).
- **Appraisal.**
  - Pokes: first = Startled (keeps the surprised bounce); a burst reaching the patience threshold (2–5) = Annoyed; three more = FedUp. Poked again and again across minutes without a burst = Amused, then Habituated (calm blink + look, no bounce).
  - Reply end: Satisfied, or Relieved after a long one (adds a sigh).
  - Events: Interested / Concerned / Curious.
- **Mood.** `MoodEngine` events now push the mood's *target*. The push fades over about 4 min, valence follows with τ 90 s and arousal with τ 120 s, and drift is capped at 0.006/s. The mood moves over minutes; the face reacts at once.

### Pass 3 — memory, knobs
- **ShortTermMemory.**
  - No repeated looks: a glance at a spot looked at in the last 25 s goes the other way instead.
  - Poke habituation: familiarity +1 per poke, τ 150 s.
  - Places where things happened: 8–45 s later it may glance back once, with chance from curiosity, inside the budget.
- **`MindKnobs`** (expressiveness, curiosity, patience, playfulness) is the API for LC Smart Core. Set it with `AvatarMotionState.mindKnobs`, or use `MindKnobs.resolve(base, map)`. When unset, defaults come from `Personality`.

### Also in engine18
- The contrast safety outline is thicker: body 3.6 % and eyes 3.0 % of the head radius (was 2.2 / 1.8). The body outline uses the true outer boundary (path union) instead of the radial silhouette, so the star's side tips no longer kink mid-spin.
- Angry and Sad are a touch softer. Grok: lid 0.5 → 0.51, angry tilt 11° → 9.5°, sad −19° → −17°. Generical: both frames moved 10 % toward Neutral.

### Audit
- `MindAuditTest` runs 12-minute mixed sessions (idle, a 4-poke burst, a 7-poke burst, single pokes, typing, a thinking + streaming reply, idle) over 4 seeds. It fails on:
  - morph interrupted before settling;
  - eye changes < 0.4 s apart;
  - blinks starting mid-morph;
  - uncaused changes;
  - budget violations;
  - idle spins;
  - spins while negative;
  - more than 8 changes/min in quiet idle.

  It also checks the rare cooldowns over 45 idle minutes, perception, appraisal, memory and knobs, and the minutes-scale mood drift.
- `MindFrameAuditTest` measures the same from rendered frames only, so it also runs unchanged on engine17.2 for before/after (`/workspace/lastchat-renders/engine18/{before,after}/frame_audit.csv`).

## Engine19 — direct morphs, one execution layer

Engine18's mind stays; what it drives was rebuilt (Critically's review, `engine19/critically_review.md`).
Engine18 stepped: a face waited for the previous one to finish, passed back through Neutral,
then animated; a poke during a look-around first recentred the head. Engine19 removes every
such gate.

**Face = weight vector.** `ExpressionDirector` is one critically damped spring per expression
weight, shared ω, stepped in exact closed form (frame-rate invariant, Σw = 1). A new target is
just a new goal: the face morphs from wherever it is, mid-transition included. Profiles: Snap ω16
(blink alias), Soft 9 (static avatar), Gradual 4.5, Settle 3.4 (relax / baseline). Renderers
blend the weights directly: `WhSphereEyes.morphWeighted` (Grok, merged by eye name, stable fold)
and `GenericalSeatedEyes.outlineWeighted` (outlines aligned to Neutral, weighted mean).
`MotionFrame.exprWeights` carries the vector whenever no single face has w > 0.999.
When a reaction ends with a follow-up pending (queued face, train, stimuli) the face stays put
instead of relaxing to Neutral first. `mapClipExpression("neutral")` no longer forces Neutral.

**Arbiter (AvatarMind.faceSafe).** Reactive owners (≥ P_EVENT) retarget at once unless a
stronger owner is inside its hold; spontaneous faces need 1.2 s dwell (3.2 s from Neutral) plus
gaze lead. Owner flips are counted (gate ≤ 0.3/s).

**Body.** Beat switches inertialize the base target (quintic, Bollo clamps) before the output
spring. Gestures are additive layers (`Add(name, dur, fn)`): Spin (pre-spring, keeps the turn-rate
cap), Hop, Nod, Recoil (stackable startle pops, 1 − 0.6·live), TurnAway/Tilt (side follows the
current yaw — never swings back through centre), Attend, Droop. Busy nod/bob/spin are additive
over the held pose. No crossfade / anticipation queue.

**Poke reflex.** At the poke moment: base freeze 0.35 s (holds the spring's *current* position,
not where it was heading), beats held, eyes-only saccade toward the touch (head doesn't join,
pending head moves cancelled; bypasses lower-priority holds), Startled accent 0.3 s (0.18 s when
habituated). Perception 0.04–0.1 s. Habituated/default branches no longer recentre the gaze.

**Blinks.** One scheduler: refractory 1.8 s, max interval 7 s, log-normal (median 3 s, σ 0.65),
spontaneous doubles p 0.04 (≥ 6 s apart), requested doubles ≤ 1 / 40 s, saccade-coupled p 0.75.
A blink only starts when the held lid is ≥ 0.98 open, so it never overlaps a lid gesture.
Lid = held-lid spring (ω 22, 30 in a startle) × (1 − blink).

**Trace + CI.** `ChannelTrace` records every channel per tick (face weights, owner, cause,
beat, blinks, pokes); `ChannelMetrics` derives neutral returns, stop-go (per channel and whole
figure), jerk, reversals, owner flips, blink stats, poke reaction times and recentre-before-react.
`ChannelMetricsTest` hard-fails on: neutral returns > 0, blink overlap > 0, gaze react > 120 ms,
face react > 180 ms, any recentre before a poke reaction, owner flips > 0.3/s
(scenarios pan_poke, rapid_pokes, idle12, session × seeds). `BaselineTraceTest` runs the same
scenarios from frames only, so the numbers compare across engines.

Later fixes found by the trace: the up-right ease-out was a first-order lag (it started at full
speed — the biggest jerk spike left); it is now a smootherstep ramp and waits while attention is on
a poke. A beat never switches while the poke freeze is still blending, the new beat's velocity is
measured on the slowed clock, and the body drifts back in after a freeze (ω 2.6) instead of
restarting. Stop-go counts a pause only when the figure is dead for 0.12–0.4 s (a smooth reversal
through zero velocity is not a stop); jerk ignores spin-speed turns (> 120 °/s).

Frame-only comparison (`BaselineTraceTest`, 8 seeds, means; E17.2 = ef1ea0d9, E18 = 317fd1fb):

| scenario | metric | E17.2 | E18 | E19 |
|---|---|---|---|---|
| pan_poke | neutral dips / run | 0.88 | 0.25 | 0.00 |
| pan_poke | stop-go, whole figure /min | 0.05 | 0.05 | 0.14 |
| pan_poke | stop-go, face /min | 0.04 | 0.00 | 0.00 |
| pan_poke | yaw jerk RMS °/s³ | 5318 | 5479 | 1487 |
| pan_poke | pitch jerk RMS °/s³ | 17907 | 12988 | 11634 |
| pan_poke | poke → gaze react, worst s | 9.00 | 9.00 | 0.00 |
| pan_poke | recentre before poke react / run | 0.25 | 0.38 | 0.00 |
| rapid_pokes | neutral dips / run | 0.75 | 0.12 | 0.00 |
| rapid_pokes | stop-go, whole figure /min | 0.38 | 0.38 | 0.38 |
| rapid_pokes | stop-go, face /min | 1.33 | 0.00 | 0.00 |
| rapid_pokes | yaw jerk RMS °/s³ | 5266 | 4654 | 1828 |
| rapid_pokes | pitch jerk RMS °/s³ | 31945 | 32716 | 32026 |
| rapid_pokes | poke → gaze react, worst s | 9.00 | 9.00 | 0.00 |
| rapid_pokes | recentre before poke react / run | 0.12 | 0.00 | 0.12 |
| idle12 | neutral dips / run | 0.75 | 0.00 | 0.00 |
| idle12 | stop-go, whole figure /min | 0.03 | 0.03 | 0.04 |
| idle12 | stop-go, face /min | 0.02 | 0.00 | 0.00 |
| idle12 | yaw jerk RMS °/s³ | 1972 | 1827 | 534 |
| idle12 | pitch jerk RMS °/s³ | 3054 | 1630 | 1174 |
| idle12 | poke → gaze react, worst s | 0.00 | 0.00 | 0.00 |
| idle12 | recentre before poke react / run | 0.00 | 0.00 | 0.00 |
| session | neutral dips / run | 5.12 | 2.00 | 0.00 |
| session | stop-go, whole figure /min | 0.34 | 0.26 | 0.19 |
| session | stop-go, face /min | 0.34 | 0.03 | 0.00 |
| session | yaw jerk RMS °/s³ | 3782 | 4119 | 1809 |
| session | pitch jerk RMS °/s³ | 12183 | 12356 | 13840 |
| session | poke → gaze react, worst s | 9.00 | 9.00 | 0.00 |
| session | recentre before poke react / run | 0.12 | 0.00 | 0.00 |

Internal gate (`ChannelMetricsTest`, worst seed): neutral returns 0, blink overlaps 0, gaze and
face react on the poke frame, recentres 0, owner flips ≤ 0.26/s. Known limits: whole-figure
stop-go in `pan_poke` is ~0.14/min vs 0.05 (the brief, eased poke pause still counts once in a
while); pitch jerk is unchanged (startle pops); blink rate under repeated pokes is ~24/min.
