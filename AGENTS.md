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
  - `PLAYING`: Standard portrait scrolling state. Jump over Shy Guys, collect Fudge snacks, and catch rare Hearts.
  - `SUPER_MODE`: Triggered upon collecting **10 Fudges**.
    - Player scales up (~1.8x – 2.0x beyond base scale).
    - Game speed increases significantly.
    - Player smashes directly through Shy Guys and Fudge/Shy Guy towers without taking damage.
    - Smashed counter increments.
    - Duration: **5 seconds**, after which the player smoothly scales back and normal play resumes.
  - `HURT`: Triggered when colliding with a Shy Guy in normal mode. Player briefly flickers/flinches, loses 1 Heart.
  - `GAME_OVER`: Triggered when 3 Hearts are depleted.
    - Executes a **Mario-style death animation** (brief vertical jump upwards, disable horizontal collisions, then drop accelerating downward off the bottom of the viewport).
    - Prompts screen reset to `TOUCH_TO_START`.
- **Collectibles**: 
  - **Fudge**: Standard collectible (+1 to super mode counter, plays chew animation/SFX).
  - **Heart**: Rare spawn (~1 in 100 entity spawn rolls). Restores 1 HP up to the maximum of 3 Hearts.
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
  - `SoundPool` for low-latency SFX (bark, steps, chew, grow, smash, heart pickup).
  - `MediaPlayer` for background music tracks (Main BG & Super Mode BG).
- **Asset Serialization**: `org.json` for parsing JSON array sprite maps with optional chroma-key metadata.

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
- TouchToStart      - Pooch Sheet + JSON (baseScale)        - 10:16 Viewport Clip       - SoundPool (SFX)
- Playing           - ShyGuy Sheet + JSON (baseScale)       - AABB Hitboxes             - MediaPlayer (BGM)
- SuperMode         - Load-Time Chroma Keying (RGB Dist)   - Jump Kinematics
- GameOver Anim     - Heart & UI Sheet (baseScale)
                                                                     │
                                                                     ▼
                                                     [ lockHardwareCanvas() Pipeline ]
                                                     - Hierarchical Scale Calculation
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

3. **Multi-Sheet `SpriteAtlasManager` & Base Scale Normalization**:
   - Manages independent PNG/JPEG sheets with matching JSON descriptor files (`pooch.json`, `shyguy.json`, `fudge.json`, `heart.json`, `ui.json`).
   - **JSON Format Standard (Per Atlas / Frame Descriptor)**:
     ```json
     [
       {
         "id": "pooch_run_0",
         "x": 283,
         "y": 116,
         "width": 213,
         "height": 120,
         "originX": 133,
         "originY": 54,
         "chromaKey": "#ffffff",
         "chromaThreshold": 255
       },
       {
         "id": "pooch_run_1",
         "x": 534,
         "y": 107,
         "width": 216,
         "height": 128,
         "originX": 135,
         "originY": 53,
         "chromaKey": "#ffffff",
         "chromaThreshold": 255
       }
     ]
     ```
     *Note: `chromaKey` (hex color string) and `chromaThreshold` (0–255 `UInt8`) are optional per sprite entry or atlas configuration.*

   - **Chroma Keying Subsystem (3D RGB Euclidean Distance)**:
     To support sprites extracted from non-transparent backgrounds (e.g., white or green screens), the atlas loader executes a one-time pixel pass at asset load time. The distance formula between a pixel color $(r, g, b)$ and target chroma color $(r_t, g_t, b_t)$ is:

     $$\text{distSq} = (r - r_t)^2 + (g - g_t)^2 + (b - b_t)^2$$

     If $\text{distSq} \le \text{chromaThreshold}^2$, the pixel's alpha channel is set to $0$ (completely transparent).
     
     ```kotlin
     fun applyChromaKey(bitmap: Bitmap, hexColor: String, threshold: Int): Bitmap {
         val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
         val width = mutableBitmap.width
         val height = mutableBitmap.height
         val pixels = IntArray(width * height)
         mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

         val targetR = Integer.parseInt(hexColor.substring(1, 3), 16)
         val targetG = Integer.parseInt(hexColor.substring(3, 5), 16)
         val targetB = Integer.parseInt(hexColor.substring(5, 7), 16)
         val thresholdSq = threshold * threshold

         for (i in pixels.indices) {
             val p = pixels[i]
             val r = (p shr 16) and 0xFF
             val g = (p shr 8) and 0xFF
             val b = p and 0xFF

             val dr = r - targetR
             val dg = g - targetG
             val db = b - targetB
             val distSq = dr * dr + dg * dg + db * db

             if (distSq <= thresholdSq) {
                 pixels[i] = p and 0x00FFFFFF // Clear alpha channel to 0
             }
         }
         mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
         return mutableBitmap
     }
     ```
     *Executing this process ONCE during asset load ensures rendering remains 100% zero-cost per frame.*

   - **Base Scale Normalization Factor**: Each sprite sheet/entity registry entry defines a `baseScale` property to adjust raw artwork pixel sizes to correct relative game proportions:
     $$\text{finalScale} = \text{baseScale} \times \text{stateScaleMultiplier} \times \text{animScale}$$
   - Engine parses `id` keys to automatically sequence and index animation frames (e.g., `pooch_run_0`, `pooch_run_1`).
   - `originX` and `originY` determine entity ground pivots and relative alignment.

4. **Zero-Allocation Rendering Rules**:
   - **CRITICAL**: Absolutely no heap allocations (`Rect`, `RectF`, `Paint`, `Matrix`, `String` concatenations, or collections iterators) inside the main `update()` or `render()` execution loop.
   - Pre-allocate all draw bounds (`srcRect`, `dstRect`), transformation objects, and paint instances during initialization.

---

## 4. Entity, Pickup & Physics System

### Kinematics (Calibrated for $1080 \times 1728$ Workspace)
- **Gravity**: $g \approx 3800\text{ px/s}^2$.
- **Jump Impulse**: $v_y = -1600\text{ px/s}$.
- **Base Scroll Speed**: $v_x = 550\text{ px/s}$.
- **Super Mode Scroll Speed**: $v_{x,\text{super}} = 1100\text{ px/s}$.

### Entity Registry & Base Scale Configuration
```kotlin
data class EntityTypeConfig(
    val atlasKey: String,
    val baseScale: Float, // Normalizes artwork pixels to virtual world units
    val hitboxInsetFactor: Float = 0.8f // 80% inset for fair play collisions
)

object EntityRegistry {
    val POOCH = EntityTypeConfig("pooch", baseScale = 1.2f)
    val SHY_GUY = EntityTypeConfig("shyguy", baseScale = 1.0f)
    val FUDGE = EntityTypeConfig("fudge", baseScale = 0.85f)
    val HEART = EntityTypeConfig("heart", baseScale = 0.9f)
}
```

### Pickups & Spawning Engine
- **Fudge (Common Pickup)**:
  - Standard collectible. Increments fudge counter.
  - Reaching 10 fudges triggers **Super Mode** (5-second duration).
- **Heart (Rare Pickup)**:
  - Spawns rarely (~1 in 100 entity spawn rolls).
  - Collectible only if player has fewer than 3 hearts (`currentHp < 3`), restoring +1 HP.
  - Animates along a gentle sine-wave Y trajectory (`y = groundY - floatOffset + sin(time * freq) * amp`).

### Hitboxes & Collision Rules
- Hitbox bounds are anchored around `originX` and `originY`.
- Internal collision bounds apply `hitboxInsetFactor` (~80%) to prevent unfair visual edge-hits.
- Handled via pre-allocated `RectF.intersects(boxA, boxB)` evaluations.

---

## 5. Development Guidelines for AI Agents

1. **No OpenGL ES / Legacy APIs**: Use `SurfaceHolder.lockHardwareCanvas()` exclusively for graphics output. Since Min API is 31 (Android 12+), directly utilize modern Android APIs without legacy `SDK_INT` version checks.
2. **Dynamic Delta-Time Physics**: Always scale movement, gravity, and animations by elapsed delta time $\Delta t$ in seconds (`positionY += velocityY * dt`).
3. **Multi-Atlas Scaling & Chroma Keying**: Parse optional `chromaKey` and `chromaThreshold` fields from JSON descriptors during atlas initialization to apply 3D Euclidean RGB color distance alpha keying at load time.
4. **Strict Zero-Allocation Frame Loop**: Maintain zero memory allocations per frame within `update()` and `draw()`.
5. **Pure Kotlin Architecture**: Maintain clean, idiomatic Kotlin code utilizing standard `Canvas` and `Paint` pipelines without external game engine dependencies.
6. **Lifecycle Hygiene**: Explicitly stop threads, release `SoundPool` resources, stop `MediaPlayer` streams, and safely finalize surface operations inside `surfaceDestroyed()` / `onPause()`.

---

## 6. Project Todo Checklist & Progress Matrix

### Phase 1: Asset Pipeline & Infrastructure
- [x] Sprite Assets
  - [x] Pooch character spritesheet
  - [x] Shy Guys spritesheet
  - [x] Fudge collectible sprite
  - [x] Hearts UI & collectible sprite
  - [ ] Tap to start text rendering / bitmap font asset
- [x] Background Graphic (Landscape)
- [x] Sprite Editor (With 3D RGB Distance Chroma Key export support)
- [x] Multi-Atlas JSON Parser with `baseScale` and Chroma Key (`chromaKey`, `chromaThreshold`) processing
- [ ] Sound & Audio Assets
  - [x] Bark / Arf sound (`sfx_arfarf.wav`)
  - [x] Sad puppy whimper (`sfx_sadpup.wav`)
  - [x] Landing sound (`sfx_thump.wav`)
  - [x] Smash sound (`sfx_smash.wav`)
  - [x] Chew animation sound (`sfx_crunch.wav`)
  - [ ] Grow big powerup sound (`sfx_arfarf.wav`)
  - [ ] Heart pickup sound (`sfx_arfarf.wav`)
  - [x] Main background music track (`bg_main.m4a`)
  - [x] Super mode high-octane BGM track (`bg_super.m4a`)

### Phase 2: Engine Core & Lifecycle
- [x] Android App Skeleton (Min API 31, Target API 36, Portrait full-screen activity)
- [x] Game Loop Thread with `lockHardwareCanvas()`, $60\text{ FPS}$ / $30\text{ FPS}$ frame pacing & $\Delta t$ timing
- [x] Touch Input Handler (Tap to jump trigger)
- [x] Multi-Sprite Atlas Loader & Base-Scale Origin-aware Frame Renderer with Chroma Key Transparency Pre-processing
- [x] Landscape Background Loader with 10:16 Crop Viewport & Infinite Scroll Loop

### Phase 3: Gameplay & Character Kinematics
- [x] Scene State Controller (`TOUCH_TO_START` vs `PLAYING`)
- [x] Pooch Run Animation Controller (indexed frame lookup)
- [x] Jump Physics & Jump Animation Trigger
- [ ] AABB Hitboxes & Visual Debug Draw Toggles
- [ ] Obstacle & Collectible Spawner (Shy Guys, Fudges, & 1-in-100 Rare Hearts)

### Phase 4: UI, Counters & Power-ups
- [ ] Fudge Counter UI Display
- [ ] HP Hearts Counter UI Display (3 Hearts max) & Rare Heart pickup restoration logic
- [x] Hurt Animation & Invincibility Frames
- [x] Shy Guy Hurt / Stun Animation
- [ ] Super Mode State Machine:
  - Trigger at 10 Fudges
  - Character scale-up (1.8x - 2.0x beyond `baseScale`) & speed boost
  - Tower spawning (Fudge/Shy Guy stacks)
  - 5-second timer & smooth scale-down transition
- [ ] Shy Guys Smashed Counter UI

### Phase 5: Game Over & Polish
- [x] Game Over Mario-style Death Animation (Upward pop + downward gravity fall out of portrait screen)
- [ ] Sound Manager Integration (BGM switching, low-latency SFX)
- [ ] Frame Budget & Zero-Allocation Memory Audit ($60\text{ FPS}$ / $30\text{ FPS}$)
