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
  PngSynth.java                  字节 API：PNG 编码字节进 → PNG 编码字节出（无文件 I/O）
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
./build.sh jar      # 构建 dist/resynth.jar + dist/resynth-sources.jar
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

### 字节 API（PNG 进 / PNG 出）

不经过文件系统：图像和掩码都是 **PNG 编码的 `byte[]`**，返回修复后的 **PNG `byte[]`**。

```java
import resynth.PngSynth;
import resynth.Params;

byte[] healedPng = PngSynth.heal(imagePng, maskPng);                       // 默认参数
byte[] healedPng = PngSynth.heal(imagePng, maskPng, params);               // 自定义参数
byte[] healedPng = PngSynth.healRect(imagePng, x, y, w, h);                // 矩形选区
byte[] healedPng = PngSynth.heal(imagePng, maskPng, params, progress, ctx, cancel);
```

- 输入必须是 PNG（校验 PNG 魔数），JPEG 等其它格式会被拒绝。
- 掩码：**可见（alpha != 0）且非黑（亮度 > 0）**的像素为待修复区域，其余为语料。
- 输出 PNG 的 alpha 通道与输入一致；alpha 不参与合成（不会被改写）。
- 图像/掩码尺寸不一致、输入不是 PNG → `IllegalArgumentException`。
- 引擎错误（如掩码全选导致无可用语料）→ `PngSynth.SynthException`，`errorCode()` 返回 `Engine.ERROR_*`。

### 打包成 jar（ImageTrans / B4J）

```sh
./build.sh jar
```

产物：

- `dist/resynth.jar`：运行库，**Java 8 字节码、零第三方依赖**，JDK 8+ 的 B4J / ImageTrans 都能加载。
- `dist/resynth-sources.jar`：对应源码（GPL-3.0）。

把 `resynth.jar` 放进 B4J 的 Additional Libraries 目录，插件工程里引用：

```
#AdditionalJar: resynth
```

B4J 调用示例（`ImageToPNGBytes` / `BytesToImage` 是 ImageTrans 插件里已有的辅助方法；
图像和掩码都必须先编码成 PNG 字节）：

```b4j
Dim imagePng() As Byte = ImageToPNGBytes(origin)
Dim maskPng() As Byte = ImageToPNGBytes(mask)

Dim jo As JavaObject
jo.InitializeStatic("resynth.PngSynth")
Dim healedPng() As Byte = jo.RunMethod("heal", Array(imagePng, maskPng, 30, 500))

Dim result As B4XBitmap = BytesToImage(healedPng)
```

注意：

- `heal` 是同步调用（引擎内部用 8 线程），要放在后台线程 / `ResumableSub` 里执行，避免卡 UI。
- 返回的 PNG 与输入同尺寸；输入有 alpha 则输出也有 alpha。
- 引擎错误会以 Java 异常抛到 B4J（`Try/Catch LastException` 可处理）。

## 测试

```
12 passed, 0 failed
```

覆盖：目标区域被完全重写、区域外像素零改动、alpha 不被合成、空目标 / 空语料 / 过大 patch
的错误码、度量表对称且单调、`GRand` 与标准 MT19937 一致（用 numpy `RandomState` 交叉验证），
以及 PNG 字节 API 的往返、alpha 保留、矩形模式、非 PNG 拒绝与尺寸校验。

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
