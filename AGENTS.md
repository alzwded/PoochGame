# AGENTS.md — Pooch Runner Project Context & Instructions

This document specifies the architecture, coding rules, asset pipelines, game mechanics, and implementation roadmap for building **Pooch Runner**, a pure Kotlin full-screen 2D endless side-scrolling runner for Android.

---

## 1. Project Overview & Vision

**Pooch Runner** is a lightweight, responsive 2D endless runner built without heavy game engine dependencies (e.g., LibGDX, Unity). It utilizes pure Kotlin with native Android UI context, rendering to a 60 FPS game loop via `SurfaceView` and `Canvas`.

### Key Game Mechanics
- **Controls**: Single-tap anywhere on the screen triggers a jump.
- **States & Flow**:
  - `TOUCH_TO_START`: Fullscreen splash screen showing title/tap directive.
  - `PLAYING`: Normal scrolling state. Jump over Shy Guys, collect Fudge snacks.
  - `SUPER_MODE`: Triggered upon collecting **10 Fudges**.
    - Player scales up (~1.8x – 2.0x).
    - Game speed increases significantly.
    - Player smashes directly through Shy Guys and Fudge/Shy Guy towers without taking damage.
    - Smashed counter increments.
    - Duration: **5 seconds**, after which the player smoothly scales back and normal play resumes.
  - `HURT`: Triggered when colliding with a Shy Guy in normal mode. Player briefly flickers/flinches, loses 1 Heart.
  - `GAME_OVER`: Triggered when 3 Hearts are depleted.
    - Executes a **Mario-style death animation** (brief vertical jump upwards, disable horizontal collisions, then drop accelerating downward off the bottom of the viewport).
    - Prompts screen reset to `TOUCH_TO_START`.
- **Collectibles**: Fudges (+1 to super mode counter, plays chew animation/SFX).
- **Obstacles**: Shy Guys (single units or towers).
- **Visuals**: Endless scrolling background, bitmap sprite animations parsed via JSON atlas descriptors with custom origins/pivots.

---

## 2. Technical Stack & Target Environment

- **Language**: Kotlin 1.9+ / 2.0+
- **Platform**: Android SDK (Min API 24+, Target API 34+)
- **Graphics Engine**: Native Android `SurfaceView` + custom hardware-accelerated `Canvas` game loop.
- **Audio Engine**: 
  - `SoundPool` for low-latency SFX (bark, steps, chew, grow, smash).
  - `MediaPlayer` for background music tracks (Main BG & Super Mode BG).
- **Asset Serialization**: `org.json` or `kotlinx.serialization` for parsing sprite atlas map files.

---

## 3. Game Engine Architecture

```
[ MainActivity ]
       │
       ▼
[ GameSurfaceView ] ──(Runs Game Thread @ 60 FPS)──► [ GameEngine ]
                                                             │
            ┌───────────────────┬────────────────────────────┼───────────────────────────┐
            ▼                   ▼                            ▼                           ▼
    [ State Machine ]   [ Asset Manager ]            [ Physics / Collision ]     [ Sound Manager ]
    - TouchToStart      - SpriteAtlas JSON parser    - AABB Hitboxes             - SoundPool (SFX)
    - Playing           - Bitmap Sheets              - Jump Kinematics           - MediaPlayer (BGM)
    - SuperMode         - Sound Pool                 - Scroll Speeds
    - GameOver Anim
```

### Core Architecture Components

1. **`GameLoopThread`**:
   - Manages fixed update delta time ($\Delta t$) targeting ~60 FPS (16.6 ms frame budget).
   - Handles `SurfaceHolder` locking/unlocking `Canvas`.
   - Thread-safe pause/resume bound to Activity lifecycle (`onPause`/`onResume`).

2. **`GameViewport` / Scaler**:
   - Maintains a virtual logical resolution (e.g., $1920 \times 1080$).
   - Maps screen dimensions to the virtual canvas, maintaining aspect ratio.

3. **`SpriteAtlas` System**:
   - Loads master atlas PNG/JPEG and corresponding `.json` definition.
   - Computes sub-bitmaps and pivot offsets.
   - **JSON Format Standard**:
     ```json
     {
       "frames": {
         "pooch_run_01.png": {
           "x": 0, "y": 0, "w": 64, "h": 64,
           "pivotX": 0.5, "pivotY": 1.0
         },
         "shy_guy_walk_01.png": {
           "x": 64, "y": 0, "w": 48, "h": 48,
           "pivotX": 0.5, "pivotY": 1.0
         }
       }
     }
     ```

4. **Zero-Allocation Game Loop Standard**:
   - **CRITICAL**: Absolutely no object allocations inside `onDraw()` or `update()` (e.g., no `new Rect()`, `Paint()`, or string concatenations).
   - Pre-allocate all `RectF`, `Matrix`, `Paint`, `StringBuilder`, and array iterator instances during startup.

---

## 4. Entity & Physics System

### Kinematics
- **Gravity**: $g \approx 2500 \text{ px/s}^2$ (scaled to virtual resolution).
- **Jump Impulse**: $v_y = -1100 \text{ px/s}$.
- **Base Scroll Speed**: $v_x = 450 \text{ px/s}$.
- **Super Mode Scroll Speed**: $v_{x,\text{super}} = 950 \text{ px/s}$.

### Hitbox & Collision Detection
- Entities define a **relative bounding box** smaller than their visual frame to avoid forgiving hitboxes (e.g., $80\%$ of visual width/height centered at anchor).
- Collision detection utilizes pre-allocated `RectF.intersects(boxA, boxB)`.

---

## 5. Development Guidelines for AI Agents

1. **Pure Kotlin Focus**: Keep everything in clean, idiomatic Kotlin code. Do not introduce third-party native C++ or external game frameworks unless explicitly instructed.
2. **Lifecycle Awareness**: Always release `SoundPool`, `MediaPlayer`, and `Bitmap` resources in `onDestroy()` / `surfaceDestroyed()`.
3. **Single Responsibility Files**: Split entities into logical files (`Pooch.kt`, `ShyGuy.kt`, `Fudge.kt`, `ParallaxBackground.kt`, `SoundManager.kt`, `SpriteAtlas.kt`).
4. **Resolution Independence**: Express physics and rendering using floating-point virtual coordinates.

---

## 6. Project Todo Checklist & Progress Matrix

### Phase 1: Asset Pipeline & Infrastructure
- [x] Sprite Assets
  - [x] Pooch character spritesheet
  - [x] Shy Guys spritesheet
  - [x] Fudge collectible sprite
  - [x] Hearts UI icons
  - [ ] Tap to start text rendering / bitmap font asset
- [x] Background Graphic
- [x] Sprite Editor (External tool setup)
- [ ] Sprite Maps (JSON atlas coordinates for frames & pivots)
- [ ] Sound & Audio Assets
  - [ ] Bark / Arf sound (`pooch_bark.wav`)
  - [ ] Sad puppy whimper (`pooch_hurt.wav`)
  - [ ] Footsteps loop (`pooch_steps.wav`)
  - [ ] Smash sound (`smash_tower.wav`)
  - [ ] Chew animation sound (`fudge_eat.wav`)
  - [ ] Grow big powerup sound (`grow_big.wav`)
  - [ ] Main background music track (`bg_main.ogg`)
  - [ ] Super mode high-octane BGM track (`bg_super.ogg`)

### Phase 2: Engine Core & Lifecycle
- [ ] Android App Skeleton (Fullscreen `ComponentActivity` / `Activity`, hides system bars)
- [ ] Initial Engine Thread & Touch Input Handler
- [ ] Sprite Map JSON Loader & Frame Renderer
- [ ] Parallax Background Loader & Infinite Scrolling Loop

### Phase 3: Gameplay & Character Kinematics
- [ ] Scene State Controller (`TOUCH_TO_START` vs `PLAYING`)
- [ ] Pooch Run Animation Controller
- [ ] Jump Physics & Jump Animation Trigger
- [ ] AABB Hitboxes & Visual Debug Draw Toggles
- [ ] Enemy & Collectible Spawner (Shy Guys & Fudges)

### Phase 4: UI, Counters & Power-ups
- [ ] Fudge Counter UI Display
- [ ] HP Hearts Counter UI Display (3 Hearts max)
- [ ] Hurt Animation & Temporary Invincibility Frames
- [ ] Shy Guy Hurt / Stun Animation
- [ ] Power-Up State Engine:
  - Trigger at 10 Fudges
  - Character scale-up transition & high speed
  - Towers spawning (Fudge/Shy Guy stacks)
  - 5-second timer & reversion state
- [ ] Shy Guys Smashed Counter UI

### Phase 5: Game Over & Polish
- [ ] Game Over Mario-style Death Animation (Jump up + gravity fall out of screen)
- [ ] Sound Manager Integration (BGM switching, SFX playback)
- [ ] Final Frame Budget & Memory Allocation Audit