# NasPhoto · NAS 照片管家

**把手机相册的"母本"放到你自己的 NAS 上，本地只留一份可逆的降级小图。**

手机存储永远不够，但照片又舍不得删。云相册的常规做法是把原图传上去、本地留缩略图——代价是数据交出去了，想要回原图还得看服务商的脸色。

NasPhoto 换个思路：**原图存进你自己的 NAS，本地留一份降级小图**。空间腾出来了，数据所有权没变，随时能把原图要回来。

[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2012%2B-green.svg)](#开发环境)
[![Backend](https://img.shields.io/badge/backend-Python%203.12%20%C2%B7%20stdlib-blue.svg)](#开发环境)

[中文](#中文) · [English](#english)

---

## 中文

### 它做什么

装上之后，它做四件事：

1. **扫描**手机上符合"可以卸载"条件的照片（按天数、体积、分辨率、是否收藏筛选）
2. **上传**原图到你自己的 NAS，备份完成后再把本地原图**降级**成小图
3. 需要时**从 NAS 把原图要回来**（回灌），一键恢复
4. 在**NasPhoto 回收站**里查看和还原任何被删掉的东西

核心承诺只有一条：**本地永远是降级，不是删除。** 只要 NAS 上那份还在，原图就能回来。

### 功能

| 功能 | 状态 | 说明 |
|---|---|---|
| 扫描可卸载照片 | ✅ | 门槛可配：保留天数 / 最小体积 / 最小长边 / 是否跳过收藏 |
| 上传并降级 | ✅ | 先确认备份成功，再降级本地；失败不会动本地文件 |
| 已卸载列表 / 恢复原图 | ✅ | 记录每一张的去向，随时单张或批量恢复 |
| 从 NAS 找回照片 | ✅ | NAS 归档 → 手机的回灌通道 |
| 回收站（最近删除） | ✅ | 还原到原位 / 彻底清空；超过保留天数的后台自动清理 |
| 删除同步 | ✅ | 相册里删了照片，会问你要不要连 NAS 上的备份一起删——**默认不删** |
| 误删熔断 | ✅ | 单轮删除量超过阈值就中止并告警，防止一次误操作清空归档 |
| 实时日志 | ✅ | App 内直看每条记录的处理结果与失败原因 |
| 后台自动同步 | ✅ | 定时 + 系统任务调度 + 内容变化触发，三条通道互为冗余 |
| 折叠屏适配 | ✅ | 折叠屏与平板的大屏布局 |

### 传输通道

走 **NAS 自带的 WebDAV**，纯 HTTP。

不是没试过 SFTP——试过，然后放弃了：Android 上的 SSH 库处理批量小文件时开销高得离谱，同一批文件实测比 WebDAV 慢一个数量级。而 WebDAV 服务是 NAS 系统本就自带的，不用在设备上多装任何东西。

### 双层架构：App 与 NAS 端

```
┌─────────────────────────────┐         ┌──────────────────────────────┐
│   Android App (Kotlin)      │         │   NAS 端 (Python 3, 标准库)   │
│                             │         │                              │
│  扫描 → 上传 → 降级 → 恢复   │◄───────►│  · 回收站核心 np_core         │
│  Room 记着每张照片的状态     │  WebDAV │  · 环境探针 np_doctor         │
│  三条后台通道保活            │  纯HTTP │  · 快照后端 snap_backends     │
│                             │         │  · 快照守卫 snap_guard        │
└─────────────────────────────┘         └──────────────────────────────┘
```

NAS 端刻意做了**核心/壳分离**：`np_core.py` 里不含任何路径常量、端口、uid、框架名——配置全部由外壳通过 `NP_*` 环境变量注入。判据很硬：**换一台宿主，核心一个字节都不用改**。目前提供了 CGI 壳、独立进程壳和 Docker 壳三种包装方式。

### 仓库结构

```
NasPhoto/
├── nasphoto/            Android App（Kotlin + Room，36 个源文件）
│   ├── app/src/main/java/cn/dsr213/nasphoto/
│   │   ├── engine/      降级引擎、路径对账
│   │   ├── media/       MediaStore 读写
│   │   ├── net/         通道选择、WebDAV 客户端
│   │   ├── policy/      格式与降级策略
│   │   ├── service/     前台服务、三条调度通道、删除监听
│   │   ├── ui/          主界面、恢复、回收站
│   │   └── video/       视频探测与转码
│   └── app/build.gradle.kts
├── nasphoto-core/       NAS 端核心（纯标准库）
│   ├── np_core.py       回收站业务逻辑（宿主无关）
│   ├── np_config.py     配置取值层（只认 NP_* 环境变量）
│   ├── np_doctor.py     环境探测
│   └── snap_backends.py 快照后端：Btrfs / ZFS / Null
├── nasphoto-plugin/     小米 NAS 插件面板（CGI + 前端页面）
├── _nasphoto_snap/      快照守卫：定期打快照，发现文件消失就按回收站契约搬回
└── docs/
```

### 开发环境

下表数值**取自实际设备**（`getprop` / `uname`），不是照抄参考配置。

**手机端**

| 项 | 值 |
|---|---|
| 实测设备 | Xiaomi 18 Fold（型号 `2608BPX34C` / 代号 `lhasa`） |
| 系统 | Android **17**（API 37）· 构建号 `CP2A.260605.016` · 安全补丁 2026-08-01 |
| 处理器 | `xring_o3_asic`（O3）· `arm64-v8a` |
| 语言 | Kotlin **2.0.20** |
| 构建工具 | AGP **8.6.1** · Gradle **8.9** · KSP **2.0.20-1.0.25** · JDK **17** |
| SDK | compileSdk **35** / minSdk **31**（Android 12）/ targetSdk **35** |
| 当前版本 | 0.1.0 |
| 主要依赖 | Room 2.6.1 · WorkManager 2.9.1 · Coroutines 1.8.1 · ExifInterface 1.3.7 |

**NAS 端**

| 项 | 值 |
|---|---|
| 设备 | 小米智能存储 `rp05`（RTD1619B / 4 GB） |
| 系统 | Yocto **5.0.3** scarthgap · 内核 `6.6.35-yocto-standard` |
| 运行时 | Python **3.12.4**（`/usr/bin/python3`）——核心**只用标准库**，零第三方依赖 |
| 文件系统 | Btrfs + CRC32C |
| 存储结构 | 双盘合并为一个存储池（cfs 联合挂载） |

> NAS 端不依赖任何第三方 Python 包，这是刻意的：那台机器上装包很麻烦，而 `np_core.py` 只需要 `os` / `json` / `hashlib` / `subprocess` 就够。

### 构建

**Android App**

```bash
cd nasphoto
./gradlew assembleDebug        # 需要 JDK 17
```

产物在 `nasphoto/app/build/outputs/apk/debug/`。

**NAS 端**

没有构建步骤，是纯脚本，把文件放到 NAS 上即可运行：

```bash
# 核心以 CGI 方式跑（回收站接口）
python3 np_core.py --cgi
```

要接入你自己的 NAS，需要提供归档根目录等参数——见 `nasphoto-core/np_config.py` 顶部的 `NP_*` 环境变量清单。

### 现状

⚠️ **早期开发阶段（0.1.0）。** 目前只在**一台**小米 NAS 和**一台**手机上跑通了端到端流程，没有在其他机型和 NAS 系统上验证过。

- 已实测的部分：扫描 / 上传 / 降级 / 恢复 / 回收站 / 回灌的完整链路，真机跑通。
- 推断但未广泛验证的部分：`snap_backends.py` 里的 ZFS 后端是按 Btrfs 后端的通用语义写的，**没有在 ZFS 机器上实测过**。
- 文档里的结论都标了证据等级：**✅ 实测** / **🔎 推断**。不写没验证过的事。

**上手建议：先拿一小批照片试，确认 NAS 上确实收到原图、本地也能成功恢复之后，再放开门槛。**

### 捐赠

这个项目花了我不少晚上。如果它帮你省下了几个 G 的空间，可以请我喝杯咖啡：

<img src="docs/donate_wechat_qr.png" width="220" alt="微信收款码">

**打赏是纯粹的支持，不带来额外功能、优先支持或任何授权。** 不打赏照样能完整使用。

### 免责声明

- 本项目**非官方**，与小米公司没有任何关联，也未经其审核或认可。
- 「小米」「Xiaomi」「小米智能存储」等商标归小米公司所有，此处仅作指称使用。
- 仅供**学习与个人使用**。请仅在你**自己拥有**的设备上使用。
- **风险自担**：本 App 会替换手机上的本地照片（降级）、并按你的设置删除文件。请务必先在小批量数据上验证备份确实成功，再放开使用。因使用本项目导致的数据丢失，由使用者自行承担。
- 本项目按"现状"提供，不附带任何形式的担保。

### License

[GNU AGPL-3.0](LICENSE)。如果你修改了本项目并通过网络向他人提供服务，你需要公开你的修改后的源码。

---

## English

**Put your phone's photo library "master copy" on your own NAS — keep only a reversible, downscaled version on the phone.**

Phone storage is never enough, yet nobody wants to delete their photos. The usual cloud-gallery deal uploads the originals and keeps thumbnails locally — you hand over your data, and getting the originals back depends on someone else's terms.

NasPhoto takes a different route: **the originals go to your own NAS, the phone keeps a downscaled copy.** You get the space back, you keep ownership, and the originals can be pulled back any time.

### What it does

1. **Scans** for photos that qualify as "safe to offload" (filtered by age, size, resolution, favourite flag)
2. **Uploads** the original to your NAS, then **downscales** the local copy once the backup is confirmed
3. **Restores** originals from the NAS on demand, one at a time or in bulk
4. Shows everything that ever left the phone in a **trash** view, where it can be restored

The one promise: **the local copy is always downscaled, never deleted.** As long as that copy exists on the NAS, the original can come back.

### Features

| Feature | Status | Notes |
|---|---|---|
| Scan for offloadable photos | ✅ | Configurable thresholds: age, minimum size, minimum long edge, skip favourites |
| Upload and offload | ✅ | Backup is verified before the local file is touched; a failure leaves it alone |
| Offloaded list / restore | ✅ | Every file is tracked; restore individually or in bulk |
| Pull photos back from NAS | ✅ | The NAS → phone lane |
| Trash (recently deleted) | ✅ | Restore in place, or purge; aged items are cleaned up automatically |
| Deletion sync | ✅ | Delete a photo in the gallery and it asks whether to remove the NAS backup too — **defaults to no** |
| Deletion circuit breaker | ✅ | Aborts and warns if a single run would remove more than a threshold |
| Live log | ✅ | Per-file results and failure reasons, inside the app |
| Background sync | ✅ | Timer + system job scheduler + content-change triggers, three redundant lanes |
| Foldable layout | ✅ | Wide-screen layout for foldables and tablets |

### Transport

Everything runs over the **WebDAV server that ships with the NAS** — plain HTTP.

SFTP was tried first and abandoned: the SSH libraries available on Android are punishingly slow for many small files. On the same batch, WebDAV was an order of magnitude faster. WebDAV also requires installing nothing extra on either end.

### Architecture: app plus NAS side

The NAS side is deliberately split into a **host-agnostic core** and a thin shell. `np_core.py` contains no hard-coded paths, ports, uids or framework names — the shell injects all of it through `NP_*` environment variables. The test is strict: **swap the host machine, and the core should not need a single byte changed.** Three shell flavours exist today (CGI, standalone process, Docker).

### Repository layout

```
NasPhoto/
├── nasphoto/            Android app (Kotlin + Room, 36 source files)
├── nasphoto-core/       NAS-side core (Python standard library only)
│   ├── np_core.py       Trash business logic, host-agnostic
│   ├── np_config.py     Configuration layer, reads NP_* env vars only
│   ├── np_doctor.py     Environment probe
│   └── snap_backends.py Snapshot backends: Btrfs / ZFS / Null
├── nasphoto-plugin/     Xiaomi NAS plugin panel (CGI + web UI)
├── _nasphoto_snap/      Snapshot guard: takes periodic snapshots and moves
│                        anything that vanishes into the trash contract
└── docs/
```

### Development environment

Every number below was read off the actual hardware (`getprop` / `uname`), not copied from a reference config.

**Phone**

| Item | Value |
|---|---|
| Device tested | Xiaomi 18 Fold (model `2608BPX34C`, codename `lhasa`) |
| OS | Android **17** (API 37) · build `CP2A.260605.016` · security patch 2026-08-01 |
| SoC | `xring_o3_asic` (O3) · `arm64-v8a` |
| Language | Kotlin **2.0.20** |
| Build | AGP **8.6.1** · Gradle **8.9** · KSP **2.0.20-1.0.25** · JDK **17** |
| SDK | compileSdk **35** / minSdk **31** (Android 12) / targetSdk **35** |
| Version | 0.1.0 |
| Key libraries | Room 2.6.1 · WorkManager 2.9.1 · Coroutines 1.8.1 · ExifInterface 1.3.7 |

**NAS**

| Item | Value |
|---|---|
| Device | Xiaomi Smart Storage `rp05` (RTD1619B / 4 GB) |
| OS | Yocto **5.0.3** scarthgap · kernel `6.6.35-yocto-standard` |
| Runtime | Python **3.12.4** (`/usr/bin/python3`) — the core uses the **standard library only** |
| Filesystem | Btrfs + CRC32C |
| Storage layout | Two drives merged into a single pool (cfs union mount) |

### Building

**Android app**

```bash
cd nasphoto
./gradlew assembleDebug        # requires JDK 17
```

The APK lands in `nasphoto/app/build/outputs/apk/debug/`.

**NAS side**

There is no build step — these are plain scripts. Drop them on the NAS and run:

```bash
python3 np_core.py --cgi       # trash API over CGI
```

To point it at your own NAS, supply the archive root and related settings; see the `NP_*` environment variable list at the top of `nasphoto-core/np_config.py`.

### Status

⚠️ **Early development (0.1.0).** The end-to-end flow has only been exercised on **one** Xiaomi NAS and **one** phone. Nothing here has been validated on other hardware or other NAS systems.

- Verified: the full scan → upload → offload → restore → pull-back path, on real hardware.
- Inferred, not broadly tested: the ZFS backend in `snap_backends.py` follows the generic semantics of the Btrfs one but has **never been run on a ZFS machine**.
- Claims are graded: **✅ measured** or **🔎 inferred**. Nothing unverified is stated as fact.

**Start small:** try a handful of photos first. Confirm the originals actually arrived on the NAS and that restoring them works, then widen the thresholds.

### Donate

This project cost me a fair number of late nights. If it bought you back a few gigabytes, you can buy me a coffee:

<img src="docs/donate_wechat_qr.png" width="220" alt="WeChat tip QR code">

**Tips are support, nothing more — no extra features, no priority support, no licence.** Everything works the same without them.

### Disclaimer

- **Unofficial.** Not affiliated with, endorsed by, or reviewed by Xiaomi.
- "Xiaomi", "小米" and "小米智能存储" are trademarks of Xiaomi Inc. Used here for reference only.
- For **learning and personal use**. Use it only on hardware **you own**.
- **Use at your own risk.** This app replaces local photos (downscaling them) and deletes files according to your settings. Verify on a small batch that backups really succeed before trusting it broadly. Data loss is the user's own responsibility.
- Provided "as is", without warranty of any kind.

### License

[GNU AGPL-3.0](LICENSE). If you modify this project and offer it to others over a network, you must publish your modified source.
