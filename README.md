# Breakthrough ⛁

A native **RayNeo X3 Pro** version of **Breakthrough** — Dan Troyka's 2000
abstract strategy game that won the 2001 8×8 Game Design Competition. Simple to
learn, sharp to play: race a wall of identical pieces across the board and land
just one of them on the far row. Move a cursor with right-temple **swipes**,
**click** to pick up and place — and when a move isn't legal, Breakthrough
**tells you why**.

Refactored from the TapChess codebase and the broader X3 game suite, so it
inherits every on-device-proven pattern: 640×480 logical canvas, binocular
side-by-side rendering, pure-black waveguide background, one-swipe-per-step
navigation, dwell-to-commit moves, auto-save/resume — zero vendor AARs, zero
permissions, zero binary assets (all sound is synthesized at launch).

## The rules

- 8×8 board. Each side starts with **16 pieces** filling its two home rows.
- A piece moves **one square straight or diagonally forward** onto an empty
  square.
- A piece **captures one square diagonally forward only** — never straight
  ahead, never sideways or backward.
- **Win** by landing any one piece on the opponent's home row (a
  *breakthrough*), or by leaving your opponent with no legal move.
- There are no draws.

The chevron on every piece shows the way it advances; the far row glows green
(your target) and your home edge glows red (defend it). The **race bars** on the
left show how far each side's leading piece has pushed.

## Controls (right temple pad)

| Gesture | Action |
|---|---|
| **Swipe ↑ ↓ ← →** | Move the cursor one square (menus: navigate) |
| **Click** (temple tap) | Select your piece · place it · confirm |
| **Double-tap** | Open / close **settings** any time |
| Left temple pad | System volume (ignored) |

Select a piece (its legal squares light up), swipe the cursor to a target, wait
for the ring to fill (~2s — the dwell guard against a stray swipe), then click
to commit. Illegal attempts are explained instantly; selecting is instant.
Also plays on a plain touchscreen with the same gestures.

## Computer difficulty

Five tiers, on the title screen (swipe) or in settings:

| Tier | Strength |
|---|---|
| **1 · Recruit** | Two-ply search, blunders often — a gentle start |
| **2 · Scout** | Three-ply, occasional slips |
| **3 · Soldier** | Four-ply, plays soundly |
| **4 · Captain** | Five-ply, no mercy |
| **5 · General** | Six-ply alpha-beta with move ordering |

Alpha-beta search with an advancement/defense evaluation, capped by a per-move
time budget and run on a background thread so the UI never stalls.

## Speed mode

A chess clock in settings: **10:00 / 5:00 / 3:00 / 1:00** per side, ticking on
the mover's turn, flashing red under ten seconds, flagging the game at zero.
Leave it **Off** for untimed play.

## Settings (double-tap)

Difficulty · play as White/Black · speed mode · show legal moves · show
coordinates · sound volume · swipe sensitivity · flip vertical/horizontal ·
safe tap · particles · frame cap · new game · undo move · resign · reset stats ·
reset settings (at the bottom). One-swipe-per-step navigation.

The game auto-saves after every move (and on pause) and resumes on next launch.

## Build & install

```bash
cd ~/Projects/Breakthrough
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: gradle 8.9 · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17 · compileSdk 35 /
minSdk 29.

## X3 specifics honored

- Black is transparency: the board floats as neon light on the world
- No `ar_mode` meta-data (it would halve the display to one lens)
- Temple click read as a KEY event; swipes classified by net displacement on
  finger-up; one gesture = one step everywhere
- `cyttsp6` (left volume pad) filtered out by device *name*
- RayNeo hardware detected by manufacturer/brand/product, not `Build.MODEL`
  (the X3 Pro reports `ARGF20`); SBS auto-defaults on
- Dwell-to-commit guards against finicky swipes; AI on a background thread;
  the sleep button auto-pauses into settings
