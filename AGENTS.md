# AGENTS.md — Pooch Runner Project Context & Instructions

This document specifies the architecture, coding rules, asset pipelines, game mechanics, and implementation roadmap for building **Pooch Runner**, a pure Kotlin full-screen 2D endless side-scrolling runner for Android designed for **Portrait Mode**.

---

## 1. Project Overview & Vision

**Pooch Runner** is a lightweight, responsive 2D endless runner built without heavy third-party game engines (e.g., LibGDX, Unity) or low-level C++/OpenGL ES wrappers. It utilizes pure Kotlin with native Android UI context, rendering to a surface via `SurfaceView` and `SurfaceHolder.lockHardwareCanvas()`.

### Key Game Mechanics
- **Display & Orientation**: Forced **Portrait Mode** full-screen layout (`android:screenOrientation="portrait"`).
- **Controls**: Single-tap anywhere on screen triggers a jump.
- **States & Flow**:
  - `TOUCH_TO_START`: Full-screen portrait splash screen showing title/tap directive.
  - `PLAYING`: Standard portrait scrolling state. Jump over Shy Guys, collect Fudge snacks.
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
- **Background & Camera System**:
  - Source background image is **Landscape**.
  - Camera clips/zooms into a **10:16 portrait viewport** slice of the landscape texture.
  - Background scrolls horizontally and loops seamlessly.

---

## 2. Technical Stack & Target Environment

- **Language**: Kotlin 1.9+ / 2.0+
- **Platform Specs**:
  - **Min API**: 31 (Android 12+) — eliminating legacy `Build.VERSION.SDK_INT` runtime checks across all engine systems.
  - **Target API**: 36
- **Graphics & Rendering Technology**:
  - **Pipeline**: Native Android `SurfaceView` rendering via **`SurfaceHolder.lockHardwareCanvas()`**.
  - **OpenGL ES Decision**: Raw OpenGL ES (`GLSurfaceView`) is **omitted**. Modern Android hardware-accelerates 2D `Canvas` calls directly via Skia (Vulkan / OpenGL ES backend). `lockHardwareCanvas()` provides GPU acceleration out of the box with zero GL context/lifecycle boilerplate, simplified bitmap management, and optional AGSL (`RuntimeShader`) support for `Paint` effects on API 31+.
- **Frame Rate & Battery Pacing**:
  - **Standard Target**: $60\text{ FPS}$ (frame budget $\approx 16.6\text{ ms}$).
  - **Power-Saving / Game Booster Target**: $30\text{ FPS}$ (frame budget $\approx 33.3\text{ ms}$).
  - **Delta-Time Decoupling**: Physics calculations **MUST** be scaled by elapsed delta time $\Delta t$ in seconds so movement speeds remain identical regardless of whether the device runs at $60\text{ FPS}$ or throttles to $30\text{ FPS}$.
- **Audio Engine**: 
  - `SoundPool` for low-latency SFX (bark, steps, chew, grow, smash).
  - `MediaPlayer` for background music tracks (Main BG & Super Mode BG).
- **Asset Serialization**: `org.json` for parsing JSON array sprite maps.

---

## 3. Game Engine Architecture & Rendering Pipeline

```
[ MainActivity (Portrait API 36) ]
            │
            ▼
   [ GameSurfaceView ] ──(Runs Dynamic Thread @ 60/30 FPS)──► [ GameEngine ]
                                                                     │
       ┌───────────────────┬─────────────────────────────────────────┼───────────────────────────┐
       ▼                   ▼                                         ▼                           ▼
[ State Machine ]   [ Multi-Atlas Loader ]                  [ Dynamic $\Delta t$ Physics ]  [ Sound Manager ]
- TouchToStart      - Pooch Sheet + JSON                    - 10:16 Viewport Clip       - SoundPool (SFX)
- Playing           - ShyGuy Sheet + JSON                   - AABB Hitboxes             - MediaPlayer (BGM)
- SuperMode         - Fudge Sheet + JSON                    - Jump Kinematics
- GameOver Anim     - UI Sheet + JSON
                                                                     │
                                                                     ▼
                                                     [ lockHardwareCanvas() Pipeline ]
                                                     - Zero-Allocation Draw Passes
                                                     - Origin Pivot Sub-Bitmap Slicing
                                                     - 10:16 Aspect Viewport Crop
```

### Core Engine Components

1. **`GameLoopThread`**:
   - Throttles frames dynamically based on device refresh rate ($60\text{ FPS}$ default, $30\text{ FPS}$ battery saver mode).
   - Calculates real-time $\Delta t = (t_{\text{current}} - t_{\text{previous}})$ capped at $0.05\text{ s}$ to prevent physics tunneling during micro-stutters.
   - Handles surface locking via `holder.lockHardwareCanvas()` and unlocking via `holder.unlockCanvasAndPost(canvas)`.

2. **`ViewportCamera` & Background Scrolling**:
   - Logical virtual workspace locked to a **10:16 aspect ratio** (e.g., $1080 \times 1728$).
   - Samples a 10:16 portrait crop slice out of the landscape background bitmap.
   - Calculates two-pass draw calls when scrolling spans the seamless horizontal looping boundary.

3. **Multi-Sheet `SpriteAtlasManager`**:
   - Manages independent PNG/JPEG sheets with matching JSON descriptor files (`pooch.json`, `shyguy.json`, `fudge.json`, etc.).
   - **JSON Format Standard (Per Atlas)**:
     ```json
     [
       {
         "id": "pooch_run_0",
         "x": 283,
         "y": 116,
         "width": 213,
         "height": 120,
         "originX": 133,
         "originY": 54
       },
       {
         "id": "pooch_run_1",
         "x": 534,
         "y": 107,
         "width": 216,
         "height": 128,
         "originX": 135,
         "originY": 53
       }
     ]
     ```
   - Engine parses `id` keys to automatically sequence and index animation frames (e.g., `pooch_run_0`, `pooch_run_1`).
   - `originX` and `originY` determine entity ground pivots and relative alignment.

4. **Zero-Allocation Rendering Rules**:
   - **CRITICAL**: Absolutely no heap allocations (`Rect`, `RectF`, `Paint`, `Matrix`, `String` concatenations, or collections iterators) inside the main `update()` or `render()` execution loop.
   - Pre-allocate all draw bounds (`srcRect`, `dstRect`), transformation objects, and paint instances during initialization.

---

## 4. Entity & Physics System (10:16 Viewport Calibrated)

### Kinematics (Calibrated for $1080 \times 1728$ Workspace)
- **Gravity**: $g \approx 3800\text{ px/s}^2$.
- **Jump Impulse**: $v_y = -1600\text{ px/s}$.
- **Base Scroll Speed**: $v_x = 550\text{ px/s}$.
- **Super Mode Scroll Speed**: $v_{x,\text{super}} = 1100\text{ px/s}$.

### Hitboxes & Collision Rules
- Hitbox bounds are anchored around `originX` and `originY`.
- Internal collision bounds apply an ~80% padding factor to prevent unfair visual edge-hits.
- Handled via pre-allocated `RectF.intersects(boxA, boxB)` evaluations.

---

## 5. Development Guidelines for AI Agents

1. **No OpenGL ES / Legacy APIs**: Use `SurfaceHolder.lockHardwareCanvas()` exclusively for graphics output. Since Min API is 31 (Android 12+), directly utilize modern Android APIs without legacy `SDK_INT` version checks.
2. **Dynamic Delta-Time Physics**: Always scale movement, gravity, and animations by elapsed delta time $\Delta t$ in seconds (`positionY += velocityY * dt`).
3. **Strict Zero-Allocation Frame Loop**: Maintain zero memory allocations per frame within `update()` and `draw()`.
4. **Pure Kotlin Architecture**: Maintain clean, idiomatic Kotlin code utilizing standard `Canvas` and `Paint` pipelines without external game engine dependencies.
5. **Lifecycle Hygiene**: Explicitly stop threads, release `SoundPool` resources, stop `MediaPlayer` streams, and safely finalize surface operations inside `surfaceDestroyed()` / `onPause()`.

---

## 6. Project Todo Checklist & Progress Matrix

### Phase 1: Asset Pipeline & Infrastructure
- [x] Sprite Assets
  - [x] Pooch character spritesheet
  - [x] Shy Guys spritesheet
  - [x] Fudge collectible sprite
  - [x] Hearts UI icons
  - [ ] Tap to start text rendering / bitmap font asset
- [x] Background Graphic (Landscape)
- [x] Sprite Editor (External tool setup)
- [ ] Multi-Atlas JSON Parser (`id`, `x`, `y`, `width`, `height`, `originX`, `originY` for each sheet)
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
- [ ] Android App Skeleton (Min API 31, Target API 36, Portrait full-screen activity)
- [ ] Game Loop Thread with `lockHardwareCanvas()`, $60\text{ FPS}$ / $30\text{ FPS}$ frame pacing & $\Delta t$ timing
- [ ] Touch Input Handler (Tap to jump trigger)
- [ ] Multi-Sprite Atlas Loader & Origin-aware Frame Renderer
- [ ] Landscape Background Loader with 10:16 Crop Viewport & Infinite Scroll Loop

### Phase 3: Gameplay & Character Kinematics
- [ ] Scene State Controller (`TOUCH_TO_START` vs `PLAYING`)
- [ ] Pooch Run Animation Controller (indexed frame lookup)
- [ ] Jump Physics & Jump Animation Trigger
- [ ] AABB Hitboxes & Visual Debug Draw Toggles
- [ ] Obstacle & Collectible Spawner (Shy Guys & Fudges)

### Phase 4: UI, Counters & Power-ups
- [ ] Fudge Counter UI Display
- [ ] HP Hearts Counter UI Display (3 Hearts max)
- [ ] Hurt Animation & Invincibility Frames
- [ ] Shy Guy Hurt / Stun Animation
- [ ] Super Mode State Machine:
  - Trigger at 10 Fudges
  - Character scale-up (1.8x - 2.0x) & speed boost
  - Tower spawning (Fudge/Shy Guy stacks)
  - 5-second timer & smooth scale-down transition
- [ ] Shy Guys Smashed Counter UI

### Phase 5: Game Over & Polish
- [ ] Game Over Mario-style Death Animation (Upward pop + downward gravity fall out of portrait screen)
- [ ] Sound Manager Integration (BGM switching, low-latency SFX)
- [ ] Frame Budget & Zero-Allocation Memory Audit ($60\text{ FPS}$ / $30\text{ FPS}$)