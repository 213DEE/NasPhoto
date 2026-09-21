# NasPhoto 快照守卫（snap_guard）

## 它解决什么问题

归档目录 `NasPhoto归档` 在手机 App 里有完整的删除保护：App 删除会 `MOVE` 进
`_回收站/<日期>/<原相对路径>`，App 的「回收站（最近删除）」页面能一键还原。

但**从电脑删（SMB / WebDAV / 资源管理器）是直接 unlink，没有任何保护**。
而 NAS 的 `dsync` 是「镜像」不是「备份」（rsync 之后还有一步 `rm_diff` 会删掉
bak 里有而 data 里没有的文件）—— 所以电脑上一删，**两块盘同时没**。

本脚本用 btrfs 快照兜住这个缺口：**电脑上怎么删都行，删掉的东西会自动出现在
手机 App 的回收站里**，和手机删除共用一个回收站。

## 原理

```
每 180 秒一轮：
  1. 给 pa0、pa1 的 u133630987 子卷各打一个只读快照 S_new
  2. 读 S_old 与 S_new 的文件集合，求差：
       在 S_old 里、不在 S_new 里  =  这两个快照之间被删掉的文件
  3. 把这些文件从 S_old 复制到  <归档根>/_回收站/<今天>/<原相对路径>
  4. chown 37771 + 目录 775 / 文件 664
  5. 删掉 S_old，state 指向 S_new
```

### 为什么用「旧快照 vs 新快照」而不是「旧快照 vs 实时状态」

实时状态在比对过程中会变，存在竞态。两个快照都是冻结的时间点，比对是原子的。
而且：任何发生在「打 S_new 之前」的删除都会被本轮捕获（S_old 有、S_new 无）；
发生在「打 S_new 之后」的会被下一轮捕获。
⇒ **没有遗漏窗口，最坏延迟 = 一个轮询间隔（3 分钟）。**

### 为什么必须排除 `_回收站`

否则「清空回收站」会被判成"一批文件消失了"，脚本会把它们又搬回回收站，
凭空多一层 `_回收站/<日期>/_回收站/<日期>/...`。

### 为什么要查「回收站里有没有」

手机 App 删除时文件已经在回收站里了，但快照守卫看的是「原位文件消失」——
如果不查一下就会重复搬一次（同名覆盖，或产生冗余）。所以搬之前先扫一遍
`_回收站/*/` 建索引，命中就跳过。

## 用法

```sh
python3 /data/nasphoto_snap/snap_guard.py --status     # 看状态（推荐先看这个）
python3 /data/nasphoto_snap/snap_guard.py --dry-run    # 预演：只报告要搬什么，零副作用
python3 /data/nasphoto_snap/snap_guard.py --once -v    # 跑一轮
python3 /data/nasphoto_snap/snap_guard.py --daemon --interval 180   # 常驻（服务用的就是这个）
python3 /data/nasphoto_snap/snap_guard.py --reset      # 清掉本脚本的快照与 state（不碰回收站）
```

服务控制：

```sh
systemctl status nasphoto-snapguard
systemctl restart nasphoto-snapguard
journalctl -u nasphoto-snapguard -n 50
tail -30 /data/nasphoto_snap/guard.log
```

**想立刻生效**（不想等那 3 分钟）：跑一次 `--once` 即可。

> ⚠️ 守护在跑的时候手动 `--once` 是**可以**的。锁的粒度是「一轮」而不是「整个进程」，
> 所以只会和你撞上的那一轮互斥（提示"另一实例正在跑一轮，本次跳过"），不会被整体挡住。
> 这条是实现时刻意保证的 —— 否则文档推荐的手动命令实际跑不通。

## 可调参数（都在脚本顶部）

| 常量 | 默认 | 说明 |
|---|---|---|
| `MAX_FILES` | 500 | 单轮最多搬多少个，超了就停搬（防磁盘被塞满）|
| `MAX_BYTES` | 20 GiB | 单轮最多搬多少字节 |
| `WARN_RATIO` | 0.5 | 消失比例超过这个值就记 WARN（仍然搬）|
| `LOG_MAX_LINES` | 3000 | 日志超过 4MB 时只保留最后这么多行 |

轮询间隔改服务的 `ExecStart` 里的 `--interval`（秒）。

## 已知限制

1. **改名 / 移动会被判成「删 + 增」** —— 会在回收站里留一份旧名字的副本。
   数据不丢，只是有点冗余。**这是刻意选择的**：宁可多留副本，也不能漏保护。
   （用「同 size 猜改名」的启发式可以消掉冗余，但会引入"该保护的没保护"的风险。）
2. **归档根下的文件被移除时**：脚本只从只读快照读，不动原位，所以没有额外风险。
3. **两盘要都能打快照才比对**：任一盘失败（没挂载 / 空间不足）本轮就跳过，
   保守处理，等下一轮。
4. **btrfs 快照的元数据开销**：每轮创建 + 删除快照会造成少量元数据 churn。
   归档目前在 590MB 量级、间隔 3 分钟，开销可忽略。归档大幅增长后建议把
   间隔调大（比如 600 秒）。可定期看 `btrfs filesystem usage /nas/mnt/pa0`
   里的 `Metadata,DUP` 占用。

## 排错

| 症状 | 可能原因 |
|---|---|
| `服务：inactive` | 守护挂了，`systemctl status nasphoto-snapguard` 看原因 |
| `锁：空闲` | ⚠️ **这是正常的，不代表守护没跑** —— 锁的粒度是「一轮」，守护多数时间在轮询间隔的 sleep 里。判断守护死活请看 `服务：` 那一行 |
| 日志里 `有盘打快照失败` | `pa0`/`pa1` 没挂载，或 btrfs 出错：`btrfs filesystem usage /nas/mnt/paX` |
| 日志里 `有快照读不到` | 快照被外部删了；跑 `--reset` 重建基线 |
| 电脑删了但回收站没有 | 先 `--status` 看守护在不在跑，再 `--once -v` 手动跑一轮看输出 |
| 回收站里出现旧名字的副本 | 正常现象，见「已知限制」第 1 条 |

## 依赖

- `/usr/bin/python3`（3.12.4，只用标准库）
- `/usr/bin/btrfs`
- 子卷 `/nas/mnt/pa0/u133630987`、`/nas/mnt/pa1/u133630987`（ID 257）

开机自愈在 `/data/clash/restore.sh` 里（`systemd + /data 存单元文件` 模式，
与 `backdoor.service` 一致）。
