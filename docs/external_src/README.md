# 外部工程改动备份（非 nfc_tap_demo 的代码）

本目录用于**记录 nfc_tap_demo 之外的工程改动**：热点静默连接需要改 `PriSettings`，
按团队约定，所有外部仓的改动都要在这里留一份，方便查看、统计与后续移植。

## 规则

1. **先传基线**：在动外部文件之前，把**未修改的原文件**原样拷到 `baseline/<相对路径>`，
   单独提交一次（提交信息用 `外部基线: <工程>/<文件>`）。
   这样任何时刻都能 `diff` 出"我们到底改了什么"，而不依赖外部仓的 git 历史。
2. **再提交改动**：外部仓修改完成、验证通过后，把**修改后的文件**拷到 `modified/<相对路径>`，
   单独提交，提交信息写清楚改了哪些行为。
3. `baseline/` 与 `modified/` 的目录结构必须与外部工程一致（从工程根算起）。
4. 外部仓本身仍要按各自规范提交；本目录只是**在 nfc_tap_demo 里的镜像与台账**。
5. 不要在 `baseline/` 里放改过的文件——它是"原样"的证明。

## 清单

| 外部工程 | 相对路径 | 基线提交 | 改动提交 | 目的 |
|---|---|---|---|---|
| `nfc_test/pri_project/android/pri_app/PriSettings` | `src/com/android/settings/wifi/NetworkRequestDialogActivity.java` | `d46a569` | `b6c6e4f` | 热点静默连接：命中本应用 SSID 前缀时自动批准，不弹窗 |

## 查看改动

```bash
diff -u docs/external_src/PriSettings/baseline/src/com/android/settings/wifi/NetworkRequestDialogActivity.java \
        docs/external_src/PriSettings/modified/src/com/android/settings/wifi/NetworkRequestDialogActivity.java
```

## 背景

- 调查结论见 `docs/LINK_BEARER_HOTSPOT_STUDY.md`。
- `WifiNetworkSpecifier` 首次连接必然弹窗，加 `NETWORK_SETTINGS` 权限无效；
  系统没有"允许某应用免确认连接热点"的开关。
- 唯一"设置侧放行"的落点是本文件的自动批准（约 10 行），不碰 Google 签名的 Wi-Fi APEX。
