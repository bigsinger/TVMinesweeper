# TVMinesweeper 开发规范

**项目名称：** TVMinesweeper  
**项目类型：** Android TV 版经典扫雷  
**开发语言：** Java  
**最低兼容：** Android 4.3，API 18  
**限制条件：** 不使用 AndroidX，不使用 NDK / JNI  
**界面布局：** 左侧小侧边栏（积分排行榜）+ 右侧大区域（扫雷棋盘）  
**操作方式：** 方向键移动 · OK 单击翻开 · OK 双击插旗 · 音量键插旗 · MENU 选难度

---

## 0. 修订要点

1. **项目名称定为 TVMinesweeper**，包名统一 `com.公司名.tvminesweeper`。
2. **按键方案定型**：OK 单击翻开、OK 双击插旗、音量 ± 插旗、MENU 打开难度菜单。
3. **难度选择改为弹层**：MENU 键唤出难度面板，方向键选择，OK 确认，MENU / 返回键取消。
4. **左右布局**：左侧积分排行榜侧边栏（不参与焦点）+ 右侧扫雷主区域。
5. 继续禁用 AndroidX、禁用 NDK。
6. 新增章节：遥控器按键映射表、双击检测实现、音量键拦截注意事项、菜单弹层规范。

---

## 1. 技术基线

| 项目 | 规范 |
|---|---|
| 语言 | Java，不新增 Kotlin 源文件 |
| minSdkVersion | 18 |
| targetSdkVersion | 28 |
| compileSdkVersion | 28 |
| Gradle 插件 | AGP 4.2.2 |
| Gradle | 6.7.1 |
| JDK | 11 |
| UI 框架 | 原生 Framework + `com.android.support` |
| 编码 | UTF-8 |
| 缩进 | 4 空格 |
| 行宽 | 建议 120 字符 |

所有高于 API 18 的 API 必须做版本判断，使用 `Build.VERSION.SDK_INT`、`@TargetApi`，禁止直接调用导致 Android 4.3 崩溃。

---

## 2. 工程结构

```text
app/src/main/java/com/xxx/tvminesweeper/
├── ui/
│   ├── GameActivity.java           # 主界面，承载左右两栏
│   ├── DifficultyDialog.java       # 难度选择弹层（DialogFragment 或 Dialog）
│   └── RankAdapter.java            # 排行榜条目适配器
├── game/
│   ├── GameEngine.java             # 扫雷核心逻辑，独立于 UI
│   ├── Board.java
│   └── Cell.java
├── tv/
│   ├── KeyHandler.java             # 遥控器按键统一分发
│   ├── DoubleClickDetector.java    # OK 键双击检测
│   └── BoardView.java              # 棋盘自定义 View / ViewGroup
├── data/
│   ├── RankRepository.java         # 排行榜存储
│   └── SettingsRepository.java     # 难度、音效等设置
├── util/
└── TVMinesweeperApplication.java

app/src/main/res/
├── layout/
│   ├── activity_game.xml           # 主界面：左排行榜 + 右扫雷区
│   ├── dialog_difficulty.xml       # 难度选择弹层
│   └── item_rank.xml
├── drawable/
├── values/
├── values-sw720dp/
└── values-v21/
```

包名统一小写：`com.公司名.tvminesweeper`。  
禁止循环依赖，`game` 层不依赖 `ui` 层。  
禁止出现 `libs/*.so`、`jniLibs/` 目录。

---

## 3. 命名规范

| 类型 | 规范 | 示例 |
|---|---|---|
| 包 | 全小写 | `com.xxx.tvminesweeper.game` |
| 类/接口 | UpperCamelCase | `GameEngine`、`DifficultyDialog` |
| 方法 | lowerCamelCase | `openCell()`、`toggleFlag()` |
| 变量 | lowerCamelCase | `board`、`cursorRow` |
| 常量 | UPPER_SNAKE_CASE | `MAX_ROWS`、`OK_DOUBLE_CLICK_MS` |
| 布局 | snake_case | `activity_game.xml`、`dialog_difficulty.xml` |
| 资源 ID | 类型前缀 + snake_case | `btn_new_game`、`lv_rank`、`dialog_difficulty` |
| Drawable | 状态 + 用途 | `bg_cell_focused.xml`、`ic_flag.xml` |

禁止拼音、无意义缩写、匈牙利命名法。

---

## 4. 代码风格

- 每个类、公开方法必须有 Javadoc。
- 使用 `@NonNull`、`@Nullable`（来自 `android.support.annotation`）。
- 禁止魔法数字，必须定义常量。
- 异常不得空捕获，日志统一 TAG。
- 主线程禁止 IO、大计算、递归展开全部格子。
- 发布版本关闭 `Log.v`、`Log.d`。
- 使用 `StringBuilder` 处理循环拼接。
- API 18 不支持的 `java.util.Objects`、`String.join`、`java.time` 等需替换或使用 desugaring / ThreeTenABP。

---

## 5. Android 兼容规范

- 使用 `com.android.support`，版本锁定 `28.0.0`；禁止混入任何 `androidx.*` 依赖。
- 多 dex：`multiDexEnabled true`，Application 继承 `android.support.multidex.MultiDexApplication`，或自行在 `attachBaseContext` 中调用 `MultiDex.install(this)`。
- Java 8 特性：启用 `coreLibraryDesugaring`。
- 禁止使用 API 19+ 的 `Objects.requireNonNull`、`Objects.equals`，改用 `TextUtils.equals` 或手写判空。
- 权限最小化：扫雷项目不应申请网络、定位、通讯录等权限。
- TV 启动入口使用 `android.intent.category.LEANBACK_LAUNCHER`，同时保留 `LAUNCHER` 便于调试。
- `AndroidManifest.xml` 中声明 `<uses-feature android:name="android.software.leanback" android:required="false"/>` 与 `android.hardware.touchscreen required=false`。

---

## 6. 界面布局规范

### 6.1 整体分区

```
┌─────────────────────────────────────────────────────┐
│  ┌──────────┐  ┌─────────────────────────────────┐  │
│  │ 积分排行 │  │                                 │  │
│  │  榜侧边  │  │        扫雷主区域               │  │
│  │   栏     │  │   （HUD + 棋盘 + 底部提示）     │  │
│  │  约 1/6  │  │           约 5/6                 │  │
│  └──────────┘  └─────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

- 根布局 `LinearLayout`，`orientation="horizontal"`，`match_parent`。
- 左侧栏：固定宽 `340dp` 或权重 1；右侧：`0dp` + 权重 5。
- 根布局 `padding` 保留 5% overscan 安全边距。
- 禁止 `ConstraintLayout`，使用原生 `LinearLayout` / `RelativeLayout` / `FrameLayout` / `GridLayout` / 自定义 `ViewGroup`。

### 6.2 左侧积分排行榜侧边栏

- 展示历史积分前 N 名（默认 8 条）。
- **不参与遥控器焦点**：`android:focusable="false"`、`android:descendantFocusability="blocksDescendants"`。
- 用 `ListView` + 自定义 `BaseAdapter`，条目展示排名 / 分数 / 难度 / 用时 / 日期。
- 通关后新纪录闪烁高亮一次。

### 6.3 右侧扫雷主区域

- 顶部 HUD：剩余雷数、用时、当前难度 pill（带 MENU 提示）。
- 中部棋盘：`GridLayout` 或自定义 `BoardView`。
- 底部提示条：按键说明 + 状态消息。
- 焦点默认棋盘左上角或上次位置；方向键在棋盘内移动，不越界、不跳出到侧边栏。
- 棋盘格子 `focusable="true"`，`nextFocus*` 指向相邻格子。

---

## 7. 遥控器按键规范

### 7.1 按键映射表

| 按键 | KeyEvent 常量 | 行为 |
|---|---|---|
| 方向键上 | `KEYCODE_DPAD_UP` | 光标上移 |
| 方向键下 | `KEYCODE_DPAD_DOWN` | 光标下移 |
| 方向键左 | `KEYCODE_DPAD_LEFT` | 光标左移 |
| 方向键右 | `KEYCODE_DPAD_RIGHT` | 光标右移 |
| OK 单击 | `KEYCODE_DPAD_CENTER` / `KEYCODE_ENTER` | 翻开当前格子 |
| OK 双击 | 同上，两次按下间隔 ≤ `OK_DOUBLE_CLICK_MS` | 插旗 / 取消旗 |
| 音量 + | `KEYCODE_VOLUME_UP` | 插旗 / 取消旗 |
| 音量 − | `KEYCODE_VOLUME_DOWN` | 插旗 / 取消旗 |
| MENU | `KEYCODE_MENU` | 打开 / 关闭难度弹层 |
| 返回 | `KEYCODE_BACK` | 菜单打开时关闭菜单；否则退出确认 |
| 数字键（可选） | `KEYCODE_1` ~ `KEYCODE_3` | 快速切难度（调试便捷） |

> 长按 OK 不再作为插旗方式，统一改为双击与音量键，避免与单击翻开冲突。

### 7.2 OK 键双击检测

- 常量：`OK_DOUBLE_CLICK_MS = 280`（建议范围 220 ~ 320）。
- 单击延迟执行：第一次按下后延迟 `OK_SINGLE_DELAY = 240` 毫秒再翻开；若在阈值内收到第二次按下，则取消单击、执行插旗。
- 使用 `Handler` + `Runnable` 实现，禁止用 `Thread.sleep`。
- 双击判定期间不接受其他 OK 事件，避免三连击误判。
- `onKeyDown` 中处理，`e.getRepeatCount() > 0` 直接忽略，防长按重复触发。

```java
public final class DoubleClickDetector {
    public interface Listener {
        void onSingleClick();
        void onDoubleClick();
    }

    private static final long DOUBLE_MS = 280L;
    private static final long SINGLE_DELAY_MS = 240L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private boolean pending = false;

    private final Runnable singleTask = new Runnable() {
        @Override public void run() {
            pending = false;
            listener.onSingleClick();
        }
    };

    public DoubleClickDetector(Listener listener) {
        this.listener = listener;
    }

    public void onPress() {
        if (pending) {
            handler.removeCallbacks(singleTask);
            pending = false;
            listener.onDoubleClick();
            return;
        }
        pending = true;
        handler.postDelayed(singleTask, SINGLE_DELAY_MS);
    }

    public void cancel() {
        handler.removeCallbacks(singleTask);
        pending = false;
    }
}
```

### 7.3 音量键插旗

- 在 Activity 中重写 `onKeyDown`，对 `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN` 返回 `true`，拦截系统音量调节。
- **必须验证真机遥控器**：部分电视 / 盒子的音量键由红外直接控制电视，App 收不到该事件。此时降级方案：
  - 在设置页提示用户「若音量键无法插旗，请使用 OK 双击」。
  - 保留 OK 双击插旗为唯一必备手段，音量键为增强操作。
- 音量键**不**改变系统音量，不弹出音量条。
- 长按音量键同样触发一次插旗，避免重复触发：`e.getRepeatCount() > 0` 时忽略。

### 7.4 MENU 键与难度弹层

- `KEYCODE_MENU` 打开 `DifficultyDialog`。
- 弹层内：
  - 上下键切换选项（初级 / 中级 / 高级）。
  - OK 确认，立即重开新局。
  - MENU 或返回键取消。
  - 弹层打开期间，主棋盘按键全部屏蔽。
- 弹层样式：半透明遮罩 + 居中卡片，选中项高亮并向右偏移。
- 弹层打开时暂停计时（若已有对局）。
- 弹层关闭后焦点回到棋盘，位置保持或回到左上角（规范取「保持」）。

### 7.5 焦点规范

- 方向键：光标在棋盘内移动，边界不越出。
- 焦点态必须明显：外发光、描边、放大至少一种，颜色对比强烈。
- 焦点丢失时回到上次位置；无历史则回到左上角。
- 侧边栏、HUD、底部提示条不参与焦点。

### 7.6 按键事件统一入口

- 所有按键处理集中在 `KeyHandler`，Activity 与 Dialog 通过它分发。
- 禁止在各处零散重写 `onKeyDown`，避免逻辑冲突。
- 所有按键处理返回 `true` 表示已消费，防止系统二次响应。

---

## 8. 游戏逻辑规范

- `GameEngine` 独立于 UI，可单元测试。
- 首次点击不得踩雷，首次点击后生成雷区，首次点击周围 8 格安全。
- 展开空白格使用显式栈（`ArrayDeque`），禁止深递归。
- 胜利条件：所有非雷格已打开。
- 失败条件：打开雷格。
- 支持难度：初级 9×9/10、中级 16×16/40、高级 30×16/99、自定义（预留）。
- 计时、剩余雷数、排行榜使用 `SharedPreferences` 存储。
- 雷区生成使用可注入 `Random`，便于测试。
- 禁止把棋盘状态、规则计算放入 JNI 或 native 层。

---

## 9. 资源与 UI 规范

- TV 横屏优先，适配 720p、1080p。
- 关键内容保留 5% overscan 安全边距。
- 字号：棋盘数字 ≥ 18sp，HUD 数值 ≥ 28sp，按钮 ≥ 16sp，排行榜文字 ≥ 14sp。
- 颜色对比度满足可读性，焦点色与普通色区分明显。
- 所有字符串放入 `strings.xml`，默认中文，提供 `values-en`。
- 图片使用 WebP 或 PNG，避免过大。
- 布局层级尽量扁平。
- 排行榜背景与扫雷区背景应有区分但风格统一。

---

## 10. 构建与依赖示例

```gradle
android {
    compileSdkVersion 28

    defaultConfig {
        minSdkVersion 18
        targetSdkVersion 28
        versionCode 1
        versionName "1.0.0"
        multiDexEnabled true
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
        coreLibraryDesugaringEnabled true
    }

    // 禁止 NDK
    // externalNativeBuild { ... }  不得出现
    // ndk { abiFilters ... }       不得出现
}

dependencies {
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:1.1.5'
    implementation 'com.android.support:appcompat-v7:28.0.0'
    implementation 'com.android.support:support-v4:28.0.0'
    implementation 'com.android.support:multidex:1.0.3'
    implementation 'com.android.support:support-annotations:28.0.0'
}
```

依赖版本必须锁定，升级前需验证 API 18 兼容性。  
禁止出现 `androidx.*`、`com.google.android.material`、`androidx.leanback` 等依赖。

---

## 11. 测试与质量

- `GameEngine` 必须有 JUnit 单元测试。
- 覆盖：雷区生成、首次安全、展开、插旗、胜利、失败。
- `DoubleClickDetector` 需单元测试：单击、双击、超时、取消。
- UI 焦点与按键可用 Robolectric 或 UiAutomator 测试。
- 真机验证清单：
  - OK 单击翻开、双击插旗是否稳定。
  - 音量键能否被 App 拦截。
  - MENU 键弹层开关、上下选择、OK 确认。
  - 方向键在棋盘边界不越界。
  - 侧边栏不抢焦点。
- 提交前执行 `./gradlew lint`，Lint 不得有 Error。

---

## 12. Git 与发布

- 分支：`main`、`develop`、`feature/*`、`hotfix/*`。
- 提交信息：`feat:`、`fix:`、`docs:`、`refactor:`、`test:`、`chore:`。
- 发布前必须签名、开启 ProGuard。
- 混淆规则保留 `View` 构造函数、`GameEngine`、`DoubleClickDetector`、`Dialog` 相关类。
- 版本号遵循 `主版本.次版本.修订号`。

---

## 13. 安全与性能

- 不申请无关权限。
- 不收集用户隐私。
- 不在主线程做文件读写。
- 棋盘对象复用，避免频繁 GC。
- 低内存设备下降级动画，保证遥控器响应。
- 不使用 native 库，APK 内不得包含 `lib/` 目录下的 `.so` 文件。

---

## 14. 附录：遥控器按键真机适配检查表

| 检查项 | 说明 |
|---|---|
| OK 键 keyCode | 部分遥控器 OK 是 `DPAD_CENTER`，部分是 `ENTER`，需都监听 |
| MENU 键 keyCode | 部分设备是 `KEYCODE_MENU`，部分映射为 `KEYCODE_SETTINGS`，需兼容 |
| 音量键 | 部分电视盒红外直控，App 收不到，需降级提示 |
| 长按 OK | `getRepeatCount() > 0`，必须忽略 |
| 返回键 | 菜单打开时先关菜单，否则再退出 |
| 焦点循环 | 棋盘边界不绕回，避免误操作 |
| overscan | 电视可能有 5% 裁切，内容留在安全区 |

---

**文档版本：** v2.0  
**最后更新：** TVMinesweeper 项目立项  
**适用阶段：** 设计、开发、测试、验收