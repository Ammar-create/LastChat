# Animated avatar aliveness

Design notes for the animated mark avatars (`ui/components/avatar/animated`).
Goal: marks that feel present and attentive without looking scripted, staying
faithful to the flat soft-3D mark look (no breathing loops, particles or glow).

## References and what we take from them

- **Procedural robot faces (Cozmo / Vector `ProceduralFace`).** Each eye is a
  small vector of continuous parameters: centre, scale X/Y, angle, corner radii,
  upper / lower lid height, lid angle and bend. Expressions are points in that
  space and transitions are interpolation between them. Animator tools reduced the
  space to a few intuitive axes (worry ↔ curiosity, mad ↔ happy, blink rate,
  gaze). We use the same idea: `EyeShape` (scale, offset, rotation, upper/lower
  openness, lid slant) layered on top of the authored expression outlines.
- **Desk-robot companions (Emo, Loona, Eilik).** Long idle stretches with rare,
  well-timed personality beats; reactions to touch that escalate and then forgive;
  boredom leading to sleep; startle on sudden events.
- **Disney's principles of animation.** Anticipation (the eyes move before the
  head), follow-through and overlap (head overshoots a hair and settles; lids and
  eye shapes spring rather than ease linearly), secondary action (small eye shape
  changes riding a gesture), squash and stretch (eyes stretch along fast saccades
  and squash as the lids meet), timing variety (log-normal holds, irregular blinks).
- **Human gaze behaviour.** Saccades with brief eye-lead before the head turns,
  micro-saccades during fixations, blinks that cluster around gaze shifts.

## Architecture

| Layer | Responsibility |
| --- | --- |
| `MotionDirector` | Body pose: crossfaded beats (idle, busy repertoire, gestures), critically damped output filter, turn-speed cap, scale clamp (no size jumps). Mood biases idle beat weights and hold lengths. |
| `GazeDirector` | Fixations, micro-saccades; eyes on a fast spring, head target delayed 70–150 ms on big looks and slightly underdamped (follow-through). |
| `ExpressionDirector` | Authored expression morphs (path / capsule interpolation). |
| `FaceRig` | Parametric per-eye shaping (`EyeShape`): accents, mood bias, saccade stretch, blink squash, micro jitter, all through springs (ζ≈0.62 for shapes, critically damped offsets). |
| `MoodEngine` | Valence (−1…1) and arousal (0…1). Events nudge it; it decays toward a baseline that depends on the local hour and sinks slowly with boredom. |
| `Blinker` | Log-normal blink gaps, per-blink speed/depth, doubles, gaze-contingent blinks, fewer while concentrating. |
| `AvatarStage` | Shared registry: on-screen avatars, UI look targets with real window bounds (activity pill, streaming text, composer), and app events. |

Grok eyes keep their capsule look: they take scale, offset, rotation and openness
from `EyeShape` (slant becomes a small symmetric rotation). Generical outlines take
the full lid treatment (upper / lower compression with slant across the eye).

## Accents (parametric looks)

Curious, Puzzled, Annoyed, Shy, Startled, Dizzy, Smug, Focused, Drowsy, Worried,
Glee, WakeSquint, Sad. They combine with any authored expression and are short
(≈0.4–3 s), with a cooldown so faces never pile up.

## Behaviour map

- **Idle:** mood picks the occasional face (good mood → glee / smile, high
  arousal → curious tilt, low → drowsy lids, low valence → worried). Long quiet →
  drowsy → droops, sooner late at night.
- **Busy (thinking / tools / streaming):** varied beat repertoire (turns, look-
  arounds, ponder, pill and text glances at their real positions, nods, pauses,
  rare spin) plus focused / puzzled accents.
- **UI:** keyboard opening (glance at the composer), fast fling (rarely dizzy),
  menus (look at the message), copy (small pleased beat), regenerate (puzzled
  blink), delete (watch it go, small sad / surprised), errors (worried, mood
  drops), activity pill expanding (peek), app returning to foreground (wake-up
  squint, double blink).
- **Pokes:** startle → hurt → annoyed turn-away, then forgiving glee a few seconds
  later; many pokes in a row end in a spin.
- **Other avatars:** rare mutual glances (sometimes 🤨), shy look-away after held
  eye contact, occasional mirroring of a neighbour's face, turn-taking looks at the
  sender's avatar when a message is sent. All avatars hear the same events, so a
  surprise lands on all of them together.

## Constraints

- Every change goes through springs or crossfades: no hard cuts, no size jumps.
- No up-right brand stare; upward looks are softened continuously.
- Chat shows the avatar only on the newest assistant turn; older turns ease it
  away (fade + scale, layout space closing) and it returns the same way.
