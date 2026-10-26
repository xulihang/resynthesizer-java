# Resynthesizer — Java 移植

把 [bootchk/resynthesizer](https://github.com/bootchk/resynthesizer) 中平台无关的 C 引擎（`lib/`）
翻译成**纯 Java**。零第三方依赖，只用 JDK 完成图像 I/O。

OpenCV 不是必需的。算法本身只是字节查表与求和，JDK 完全够用；将来若要与
OpenCV 管线对接，可以再加一层可选的 `Mat ↔ byte[]` 适配。

> 原项目版权归 Paul Francis Harrison、Laurent Despeyroux、David Rodríguez García、
> Lloyd Konneker 等人所有，采用 **GPL-3.0**。本移植同样以 GPL-3.0 发布，详见 [COPYING](COPYING)。

## 目录

```
src/main/java/resynth/
  Main.java                      CLI：读 PNG → 按矩形选区 heal → 写 PNG
  MainMask.java                  CLI：读 图像 + 任意灰度掩码 → heal，并输出红色轮廓对比图
  ImageSynth.java                SimpleAPI：一张图 + 掩码（对应 imageSynth.c / adaptSimple.h）
  Engine.java                    引擎编排与准备（对应 engine.c）
  Synthesizer.java               热点：补丁匹配 computeBestFit + synthesize（对应 synthesize.h）
  Refiner.java                   多线程多轮 pass（对应 refinerThreaded.h / passes.h）
  OrderTarget.java               目标点排序模式 0..8（对应 orderTarget.h / brushfire.h）
  Map.java  PointVector.java     内部数据结构（对应 Map / pointVector）
  FormatIndices.java             像素布局索引（对应 TFormatIndices）
  Metrics.java                   度量查表（对应 matchWeighting.h）
  Progress.java  CancelFlag.java 进度回调与取消
  GRand.java                     GLib GRand（MT19937）的移植
  Params.java                    参数（对应 TImageSynthParameters）
src/test/java/resynth/           测试（JUnit 5 注解 + 自带的反射 runner）
examples/                        示例输入图与掩码
build.sh                         编译 / 测试 / 基准脚本
```

## 构建与运行

需要 JDK 17+。`build.sh` 使用 `JAVA_HOME`，否则用脚本里写死的路径（请按需修改）。

```sh
./build.sh          # 编译到 out/
./build.sh test     # 编译并跑测试
```

### 按矩形选区修复

```sh
java -cp out resynth.Main <in.png> <out.png> <x> <y> <w> <h> [patchSize] [maxProbeCount]
```

### 按掩码修复（推荐）

掩码中**非零像素**被选中为待修复区域，其余为语料。会额外输出
`<out>-before.png`、`<out>-after.png`（红色轮廓标出区域）和 `<out>-compare.png`（左右对比）。

```sh
java -cp out resynth.MainMask examples/image.jpg examples/thresh.png out/image-heal.png 30 500
#                                        图像          掩码            输出               patch 探针数 [neighborWeight]
```

默认参数与原插件一致：`patchSize=30`、`maxProbeCount=200`、`sensitivityToOutliers=0.117`、
`mapWeight=0.5`、`matchContextType=1`。

## 测试

```
6 passed, 0 failed
```

覆盖：目标区域被完全重写、区域外像素零改动、alpha 不被合成、空目标 / 空语料 / 过大 patch
的错误码、度量表对称且单调、`GRand` 与标准 MT19937 一致（用 numpy `RandomState` 交叉验证）。

## 基准（12 核，8 线程）

| 用例 | 目标尺寸 | patch / 探针 | 耗时 |
|---|---|---|---|
| brick | 100×50 | 16 / 500 | ~0.60 s |
| donkey_original | 135×100 | 16 / 500 | ~0.57 s |
| brick | 400×400 | 30 / 200 | ~1.8 s |
| image.jpg（示例） | 24k 像素 | 30 / 500 | ~0.93 s |
| cropped.png（示例） | 30k 像素 | 30 / 500 | ~0.56 s |

- 单线程 donkey 用例：约 **1 亿次度量查表求和**，**1.25 s**，约 **80M 查表/秒（单核）**。
- 线程扩展：1 线程 1.27 s → 2 线程 0.95 s → 4 线程 0.72 s → 8 线程 0.57 s（约 2.2×，
  之后受内存带宽 / 缓存限制）。
- 未与 C 版本做同机对比（开发环境无 C 工具链）。

## 与 C 版的差异

1. **随机数**：C 版在 8 个线程间共享一个未加锁的 `GRand`，属于数据竞争，只有单线程构建可复现
   （原项目的 `Test/testResynth.py` 也注明这一点）。本移植为每个 (pass, 线程) 用
   `Params.randomSeed` 派生独立 PRNG：
   - 单线程（`Refiner.THREAD_LIMIT = 1`）输出完全可复现；
   - 多线程下结果依赖线程调度，每次略有差异，与 C 多线程版行为一致。
2. **进度百分比**可能超过 100%，沿用 C 版 `estimatePixelsToSynth` 的估算方式。
3. **排序比较器**：C 的 `to_sort_result` 在相等时也返回非零；Java 的 `TimSort` 要求比较器一致，
   改用 `compare()`，对最终顺序无实质影响。
4. **`neighborWeight`（Java 独有扩展）**：按离补丁中心的距离对补丁元素做高斯加权，默认 `0`（关闭），
   此时与原版完全一致。在示例图上 `6` 能让接缝更平滑、纹理更丰富，代价是约 50% 耗时。

## 尚未移植

- GIMP 绑定（`src/resynthesizer/*`）——不需要，任何 UI 或 OpenCV 管线都可直接调用 `ImageSynth`。
- 地图（map）多色匹配的对外接口：`Engine.engine` 已支持带 map 位段的 `FormatIndices`，
  但 `ImageSynth` 只暴露 RGB / RGBA / Gray / GrayA。
- `imageSynth2`（两个掩码，插件 fill-pattern 用）。
- 各插件的参数封装（heal / uncrop / map-style 等）；引擎的 `matchContextType` 0..8 已全部实现。
