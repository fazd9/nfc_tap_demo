### Admission（1.037s）

| 图层 | 通道 key | 类型 | 关键帧（时间 s → 值，缓动 / 表达式） |
|---|---|---|---|
| TopL | `MaterialTL.black_alpha` | float | 0→0 (ipol=spring, spring=[0, 0.19, 0], bezier=[0.33, 0, 0.67, 1])；0.3→0.7 (ipol=bezier, bezier=[0, 0, 0.1, 1]) |
| Light | `MaterialL.castProgress` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；1→720 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Light | `MaterialL.edgeProgress` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；1→2599.2 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) `Screen_Height * 1.083 * Edge_Factor` |
| Light | `MaterialL.castColorProgress` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；1→330 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Light | `MaterialL.ColorAlpha` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.3→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Light | `MaterialL.waveAlpha` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Light | `MaterialL.castCenter` | Vec2 | x: 0→540 `Screen_Width * 0.5`；y: 0→2721.6 `Screen_Height * 1.134` |
| Light | `MaterialL.edgeCenter` | Vec2 | x: 0→540 `Screen_Width * 0.5`；y: 0→2299.9 `Screen_Height * 0.9583` |

### Card appear（0.744s）

| 图层 | 通道 key | 类型 | 关键帧（时间 s → 值，缓动 / 表达式） |
|---|---|---|---|
| Card | `MaterialB.CardScale` | float | 0→0.4511 (ipol=spring, spring=[0.3, 0.6, 0], bezier=[0.33, 0, 0.67, 1]) `Card_Scale * 0.58`；0.74→0.7777 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) `Card_Scale` |
| Card | `MaterialB.XPosition` | float | 0→0.5 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→0.5 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `MaterialB.YPosition` | float | 0→0.564 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→0.564 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.radius` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.blendFactor` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.contrast` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.brightness` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `MaterialB.alpha` | float | 0→0 (ipol=spring, spring=[0, 0.2, 0], bezier=[0.33, 0, 0.67, 1])；0.32→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.CardScale` | float | 0→0.58 (ipol=spring, spring=[0.3, 0.6, 0], bezier=[0.33, 0, 0.67, 1]) `0.58`；0.736→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.XPosition` | float | 0→0.5 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→0.5 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.YPosition` | float | 0→0.53 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→0.53 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.alpha` | float | 0→0 (ipol=spring, spring=[0, 0.2, 0], bezier=[0.33, 0, 0.67, 1])；0.32→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.7442→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Particle-receive | `viewPositionOffset` | Vec3 | y: 0→0.5；0.7442→0.5；z: 0.7442→0；x: 0.7442→0 |
| Particle-receive | `emitterInfo` | Vec4 | x: 0→-10；0.7442→-10；y: 0.7442→1；z: 0.7442→1；w: 0.7442→1 |
| Particle-receive | `sdfCardSize` | Vec2 | x: 0→0.6；0.7442→0.6；y: 0→0.1；0.7442→0.1 |
| Particle-receive | `showRange` | Vec2 | x: 0→1；0.7442→1；y: 0→1；0.7442→1 |

### Card vanish（0.624s）

| 图层 | 通道 key | 类型 | 关键帧（时间 s → 值，缓动 / 表达式） |
|---|---|---|---|
| Card | `MaterialB.CardScale` | float | 0→0.7777 (ipol=spring, spring=[0, 0.4, 0], bezier=[0.33, 0, 0.67, 1]) `Card_Scale`；0.624→0.4511 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) `Card_Scale * 0.58` |
| Card | `MaterialB.YPosition` | float | 0→0.564 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) `origin`；0.624→0.4 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.radius` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.blendFactor` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.contrast` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.brightness` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `MaterialB.alpha` | float | 0→1 (ipol=spring, spring=[0, 0.2, 0], bezier=[0.33, 0, 0.67, 1])；0.32→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.CardScale` | float | 0→1 (ipol=spring, spring=[0, 0.4, 0], bezier=[0.33, 0, 0.67, 1])；0.624→0.58 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.XPosition` | float | 0→0.5 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) `origin` |
| Shadow | `MaterialB_copy.YPosition` | float | 0→0.53 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) `origin` |
| Shadow | `MaterialB_copy.alpha` | float | 0→1 (ipol=spring, spring=[0, 0.2, 0], bezier=[0.33, 0, 0.67, 1])；0.32→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Particle-receive | `viewPositionOffset` | Vec3 | y: 0→0.5 |
| Particle-receive | `emitterInfo` | Vec4 | x: 0→-10 |
| Particle-receive | `sdfCardSize` | Vec2 | x: 0→0.6；y: 0→0.1 |
| Particle-receive | `showRange` | Vec2 | x: 0→1；y: 0→1 |

### Card send（0.920s）

| 图层 | 通道 key | 类型 | 关键帧（时间 s → 值，缓动 / 表达式） |
|---|---|---|---|
| Card | `MaterialB.CardScale` | float | 0→0.7777 (ipol=spring, spring=[0, 0.6, 0], bezier=[0.33, 0, 0.67, 1]) `Card_Scale`；0.928→0.1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `MaterialB.XPosition` | float | 0→0.5 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `MaterialB.YPosition` | float | 0→0.564 (ipol=spring, spring=[0, 0.6, 0], bezier=[0.33, 0, 0.67, 1]) `origin`；0.928→1.05 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.radius` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.3→12 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.5→20 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.blendFactor` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.15→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.5→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.contrast` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.3→2 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.5→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.brightness` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.3→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.5→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Particle-send | `realSpeedFactor` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.05→2 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.74→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Particle-send | `particleAlpha` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Particle-receive | `particleAlpha` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.CardScale` | float | 0→1 (ipol=spring, spring=[0, 0.6, 0], bezier=[0.33, 0, 0.67, 1])；0.928→0.1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.XPosition` | float | 0→0.5 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.YPosition` | float | 0→0.53 (ipol=spring, spring=[0, 0.6, 0], bezier=[0.33, 0, 0.67, 1]) `origin`；0.928→1.03 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.alpha` | float | 0→1 (ipol=spring, spring=[0, 0.2, 0], bezier=[0.33, 0, 0.67, 1])；0.32→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Particle-send | `emitterInfo` | Vec4 | x: 0→100；0.3→100；0.5→-10 |
| Particle-send | `sdfCardSize` | Vec2 | x: 0→0.5645 `Card_Scale * 0.7259`；0.3→0.2004 `Card_Scale *0.2577`；y: 0→0.2；0.3→0.01 |
| Particle-send | `turbulenceForce` | Vec3 | x: 0→1；0.3→0.4；y: 0→1；0.3→0.1 |
| Particle-send | `speed` | Vec4 | y: 0→0.6；0.3→0.4；x: 0→0.4；0.3→0.2 |
| Particle-send | `sizeAndNoise` | Vec4 | y: 0.15→12；0.3→0 |
| Particle-send | `direction` | Vec3 | x: 0→0 `Particle_Send_X`；y: 0→1 `Particle_Send_Y` |

### Card receive（1.130s）

| 图层 | 通道 key | 类型 | 关键帧（时间 s → 值，缓动 / 表达式） |
|---|---|---|---|
| Card | `MaterialB.CardScale` | float | 0→0.1 (ipol=spring, spring=[0.31, 0.6, 0], bezier=[0.33, 0, 0.67, 1])；0.736→0.7777 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) `Card_Scale` |
| Card | `MaterialB.XPosition` | float | 0→0.5 (ipol=spring, spring=[0.31, 0.6, 0], bezier=[0.33, 0, 0.67, 1])；0.736→0.5 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `MaterialB.YPosition` | float | 0→1.05 (ipol=spring, spring=[0.31, 0.6, 0], bezier=[0.33, 0, 0.67, 1])；0.736→0.564 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.radius` | float | 0→20 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.2→12 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.blendFactor` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.3→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.5→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.contrast` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.2→2 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.5→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `plugin._blur_dual_kawase_.brightness` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.2→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.5→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Card | `MaterialB.alpha` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Particle-receive | `particleAlpha` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Particle-send | `particleAlpha` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.CardScale` | float | 0→0.1 (ipol=spring, spring=[0.31, 0.6, 0], bezier=[0.33, 0, 0.67, 1])；0.736→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.XPosition` | float | 0→0.5 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.YPosition` | float | 0→1.03 (ipol=spring, spring=[0.31, 0.6, 0], bezier=[0.33, 0, 0.67, 1])；0.736→0.53 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Shadow | `MaterialB_copy.alpha` | float | 0→0 (ipol=spring, spring=[0, 0.48, 0], bezier=[0.33, 0, 0.67, 1])；0.736→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Particle-receive | `emitterInfo` | Vec4 | x: 0→10；0.3→200；0.5→-10 |
| Particle-receive | `viewPositionOffset` | Vec3 | y: 0→1.03；0.736→0.5；x: 0→0 |
| Particle-receive | `sdfCardSize` | Vec2 | x: 0→0.606 `Card_Scale * 0.7792`；0.5→0.606 `Card_Scale * 0.7792`；y: 0→0.1；0.5→0.1 |
| Particle-receive | `showRange` | Vec2 | x: 0→0.1；0.5→1；y: 0→0.1；0.5→1 |
| Particle-receive | `speed` | Vec4 | y: 0→0.6；0.3→0.01；x: 0→0.2；0.3→0.001 |
| Particle-receive | `turbulenceForce` | Vec3 | y: 0→1.5；0.3→0.1；x: 0→2；0.3→0.4 |
| Particle-receive | `direction` | Vec3 | x: 0→0 `Particle_Send_X`；y: 0→1 `Particle_Send_Y` |

### respire（2.000s）

| 图层 | 通道 key | 类型 | 关键帧（时间 s → 值，缓动 / 表达式） |
|---|---|---|---|
| TopL | `MaterialTL.black_alpha` | float | 0→0.7 (ipol=bezier, bezier=[0, 0, 0.1, 1]) |
| Light | `MaterialL.castProgress` | float | 0→720 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；1→648 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Light | `MaterialL.edgeProgress` | float | 0→2599.2 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) `Screen_Height * 1.083 * Edge_Factor` |
| Light | `MaterialL.castColorProgress` | float | 0→330 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；1→297 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Light | `MaterialL.ColorAlpha` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Light | `MaterialL.waveAlpha` | float | 0→1 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |
| Light | `MaterialL.castCenter` | Vec2 | x: 0→540 `Screen_Width * 0.5`；y: 0→2721.6 `Screen_Height * 1.134` |
| Light | `MaterialL.edgeCenter` | Vec2 | x: 0→540 `Screen_Width * 0.5`；y: 0→2299.9 `Screen_Height * 0.9583` |

### water_0812.coz / animator1（1.000s）

| 图层 | 通道 key | 类型 | 关键帧（时间 s → 值，缓动 / 表达式） |
|---|---|---|---|
| Quad | `Ripple.uAmplitude` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.5→260 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；1→0 (ipol=bezier, bezier=[0, 0, 0.1, 1]) |
| Quad | `Ripple.uRipples` | float | 0→12 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.5→14 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；1→23.350 (ipol=bezier, bezier=[0, 0, 0.1, 1]) |
| Quad | `Ripple.uRadius` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；1→360 (ipol=bezier, bezier=[0, 0, 0.1, 1]) |
| Quad | `Ripple.uMinRadius` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；1→10 (ipol=bezier, bezier=[0, 0, 0.1, 1]) |
| Quad | `Ripple.uFadingFactor` | float | 0→0 (ipol=bezier, bezier=[0.33, 0, 0.67, 1])；0.6→4 (ipol=bezier, bezier=[0.33, 0, 0.67, 1]) |

