# OPPO 碰一碰动画逆向素材索引

本目录是 `docs/OPPO_NFC_ANIMATION_STUDY.md` 的配套素材。所有内容仅用于技术研究，不是 OPPO 官方发布物；**预览 GIF 均为近似示意，不是 OPPO 原始渲染**。

> 入仓位置：`docs/oppo_anim_ref/`。本地分析时的同名工作副本在 `_Temp/oppo_anim/`（内容一致，不入仓）。
> 使用前请先读 §6 免责声明 —— 原始二进制素材（`.coz` / `.ogg` / `.he` / `.upk` / 纹理）版权归 OPPO，仅限内部参考，**不得打进我们的 APK**。

## 1. 原始/解包数据

| 路径 | 说明 |
|---|---|
| `CardRotate0424.coz` | APK asset 原文件。全屏卡片 + 光效场景（代码实际加载的就是它） |
| `CardRotate0424/data` | zip 内 `data` 解密后文件（`CO` + base64(JSON)） |
| `CardRotate0424/scene.json` | 已解码的场景 JSON（layer / shader / animator / 关键帧） |
| `CardRotate0424/2130734260` | EasyLight 纹理 PNG，1080×1080，colormap |
| `CardRotate0424/2130734263` | EasyWave 纹理 PNG，1080×1080，colormap |
| `CardRotate0424/2130743833` | 卡片阴影 PNG，360×472，RGBA |
| `blend.coz` / `blend/scene.json` | 同结构的另一版本场景（APK 内但 NFC 代码未加载，见研究文档） |
| `water_0812.coz` / `water_0812/scene.json` | 水波纹场景（1 个 animator，1 个 Quad 层） |
| `PTCConnectSeedling.upk` / `PTCConnectSeedling/` | 状态栏/锁屏“种子卡”UPK（Pantanal 卡片，不是全屏 COE 动画） |
| `audio/*.ogg` | 从 APK `res/` 抽出的 5 个 OGG |
| `haptics/*.he` | RichTap 触觉 JSON（APK `assets/`） |
| `preview/reference_video_frames/` | 从 `Temp/video.mp4` 抽的参考帧（2fps，只作对照） |

`.coz` 解码方法（与 `COEParse.parse` 一致）：zip 内 `data` 文件去掉前两个字符 `CO`，Base64 解码，得到 JSON；其余 zip 条目按文件名（资源 id）作为纹理加载。

## 2. 预览产物（全部在 `preview/`）

| 文件 | 内容 / 打开方式 |
|---|---|
| `textures_contact_sheet.png` | 三张纹理的拼图 + 说明，直接看 |
| `stage_timeline.png` | 全流程阶段/时长总览（代码推导，卡片触发时刻为示例） |
| `keyframe_timelines.png` | 8 个核心 animator 的关键帧曲线大图 |
| `timelines/*.png` | 每个 animator 单独一张曲线图 |
| `keyframes.csv` | 全部 animator、全部通道的逐关键帧数据（时间/值/插值/bezier） |
| `keyframe_tables.md` | 与文档 §7 相同的 Markdown 关键帧表（可直接粘贴） |
| `animators_summary.txt` | 可读的关键帧文字摘要（含 Land_ 变体） |
| `water_ripple_approx.gif` | **近似**水波纹 1.0s 预览（PIL 绘制，非 OPPO shader 输出） |
| `water_edge_light_approx.gif` | **近似**水波 + 边缘光 + 卡片入场/呼吸/收尾 4.4s 预览 |
| `shader_*.glsl` | 从 `scene.json` 抽出的 GLSL（Light/Card/Shadow/EasyLight/EasyWave/Particle/TopL） |
| `gen_preview.py` | 生成本目录预览图的脚本（可重跑） |

> GIF 的几何、颜色、粒子数量均为近似，不可作为逐帧还原依据；逐帧依据请用 `keyframes.csv` 与 `scene.json`。

## 3. 重新生成

```bash
cd docs/oppo_anim_ref/preview
python3 gen_preview.py
```

依赖：Python 3 + Pillow（本机已装）。不需要 numpy/matplotlib。

## 4. 音频/触觉一览

| 资源名 | 文件 | 时长 | 用途 |
|---|---|---|---|
| `raw/touching_start` | `audio/p2.ogg` | 3.15s | 触碰开始（overlay 创建时立即播放） |
| `raw/card_receive` | `audio/rZ.ogg` | 3.15s | 接收方卡片出现时 |
| `raw/card_cancel` | `audio/Tm.ogg` | 0.53s | 发送方超时/下滑取消时 |
| `raw/coui_switch_sound_on` | `audio/1I.ogg` | 0.36s | COUI 开关音，与本流程无关 |
| `raw/coui_switch_sound_off` | `audio/Sc.ogg` | 0.40s | COUI 开关音，与本流程无关 |

触觉：`WaterAnimation.he`（触碰，900ms）、`ReceiveCard.he`（接收卡片，425ms + 428ms 瞬态）、`SendCard.he`（发送上滑，425ms + 428ms 瞬态）。

## 5. 完整文件清单（逐个）

| 文件/目录 | 说明 |
|---|---|
| `CardRotate0424.coz` | 原始 coz（zip） |
| `CardRotate0424/data` | zip 内 `data`（CO+base64 JSON）原文件 |
| `CardRotate0424/scene.json` | 解码后的场景 JSON |
| `CardRotate0424/2130734260` | EasyLight 光晕纹理（1080×1080 PNG） |
| `CardRotate0424/2130734263` | EasyWave 光环纹理（1080×1080 PNG） |
| `CardRotate0424/2130743833` | 阴影纹理（360×472 PNG RGBA） |
| `blend.coz` / `blend/data` / `blend/scene.json` | 另一版本场景；代码未加载（差异见研究文档附录 A） |
| `blend/2130734260` / `2130734263` / `2130743833` | 与 CardRotate0424 同名纹理字节完全相同 |
| `water_0812.coz` | 水波 coz |
| `water_0812/data` / `water_0812/scene.json` | 水波 data / 解码 JSON |
| `PTCConnectSeedling.upk` | 种子卡 UPK（zip：config/pages/assets/i18n） |
| `PTCConnectSeedling/config.json` | UPK 标识/宿主/入口（statusbar/lockscreen/notification） |
| `PTCConnectSeedling/pages/index.json` | 种子卡主页面模板 |
| `PTCConnectSeedling/pages/default.json` | 另一页面模板 |
| `PTCConnectSeedling/assets/card-config.json` | 卡片 host 配置 |
| `PTCConnectSeedling/assets/images/*` | 种子卡图标/失败图/分享图等（PNG/WebP） |
| `PTCConnectSeedling/assets/res/*.json` | 种子卡深色/默认资源 |
| `PTCConnectSeedling/i18n/*.json` | 文案占位 |
| `audio/1I.ogg` | `raw/coui_switch_sound_on`（无关） |
| `audio/Sc.ogg` | `raw/coui_switch_sound_off`（无关） |
| `audio/Tm.ogg` | `raw/card_cancel` |
| `audio/p2.ogg` | `raw/touching_start` |
| `audio/rZ.ogg` | `raw/card_receive` |
| `haptics/WaterAnimation.he` | 触碰触觉（RichTap JSON） |
| `haptics/SendCard.he` | 发送上滑触觉 |
| `haptics/ReceiveCard.he` | 接收卡片触觉 |
| `preview/gen_preview.py` | 预览生成脚本 |
| `preview/textures_contact_sheet.png` | 三张纹理拼图 |
| `preview/stage_timeline.png` | 阶段总览 |
| `preview/keyframe_timelines.png` | 核心 animator 曲线大图 |
| `preview/timelines/Admission.png` | Admission 曲线 |
| `preview/timelines/Card_appear.png` | Card appear 曲线 |
| `preview/timelines/Card_receive.png` | Card receive 曲线 |
| `preview/timelines/Card_send.png` | Card send 曲线 |
| `preview/timelines/Card_vanish.png` | Card vanish 曲线 |
| `preview/timelines/Card_respire.png` | Card respire 曲线（coz 中存在但代码未播放） |
| `preview/timelines/respire.png` | respire 曲线 |
| `preview/timelines/All_light_disappears.png` | 全光熄灭曲线 |
| `preview/timelines/Top_light_disappears.png` | 顶部光熄灭曲线 |
| `preview/timelines/Edge_light_disappears.png` | 边缘光熄灭曲线 |
| `preview/animators_summary.txt` | 26 个 animator 的文字摘要 |
| `preview/keyframes.csv` | 全量关键帧 CSV |
| `preview/keyframe_tables.md` | 研究文档 §7 的 Markdown 表 |
| `preview/shader_*.glsl` | 各层 GLSL（TopL/Light/Card/Shadow/EasyLight/EasyWave/Particle） |
| `preview/water_ripple_approx.gif` | 水波 1s 近似预览 |
| `preview/water_edge_light_approx.gif` | 全流程 4.4s 近似预览 |
| `preview/reference_video_frames/*.jpg` | `Temp/video.mp4` 抽帧（2fps，仅对照） |

## 6. 免责声明

`CardRotate0424.coz`、纹理、OGG、`.he`、`PTCConnectSeedling.upk` 的版权归 OPPO/原权利人所有。本目录仅做互操作研究与参数比对；若要随产品分发，必须自行获得授权或重新制作素材。
