# 跳一跳助手（JumpHelper）

Android 无障碍服务 + 悬浮窗实现的跳一跳辅助。
点两下（棋子中心、目标块中心）→ 算屏幕距离 → 换算长按时长 → 注入手势。
无需 root，无需截屏权限。

工程位置：/storage/emulated/0/MT2/mcp/JumpHelper/

---

## 一、目录结构

```
JumpHelper/
├── settings.gradle
├── build.gradle                    # 根构建脚本（AGP 版本在这里）
├── gradle.properties
├── README.md
└── app/
    ├── build.gradle                # compileSdk / minSdk 在这里
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/jumphelper/
        │   ├── MainActivity.java              # 引导页 / 三步授权
        │   ├── JumpAccessibilityService.java  # 核心：面板 + 取点 + 注入
        │   └── Prefs.java                     # 系数存储
        └── res/
            ├── xml/accessibility_service_config.xml
            └── values/strings.xml
```

没有 `gradle/wrapper` 和 `gradlew`：用 IDE 自带的 Gradle 就行，
手写 wrapper 反而容易因为版本不匹配卡住。

---

## 二、怎么导入 CodeAssist

### 情况 A：IDE 支持“打开已有工程 / Open Project”

直接选 `JumpHelper` 这个目录（有 settings.gradle 的那层）。

### 情况 B：IDE 只认自己的工程目录

1. 用 MT 文件管理器把整个 `JumpHelper` 文件夹复制到：
   - AndroidIDE：`/storage/emulated/0/AndroidIDEProjects/`
   - 其他：它的 Projects 目录
2. 在 IDE 里从工程列表里打开它

### 情况 C：导入后一片红字 / 不认识 Gradle 工程

说明你装的是**老版 CodeAssist**（com.tyron.code，不用 Gradle 的自研构建）。
这种情况：新建一个空工程，把 `app/src/main/` 下的全部内容原样拷进去，
Manifest / res / java 三个目录直接用本工程的覆盖，包名保持 com.example.jumphelper。

---

## 三、构建前可能要改的两处

| 位置 | 默认值 | 什么时候要改 |
|---|---|---|
| `build.gradle`（根） | AGP 8.1.4 | Sync 报 “AGP X requires Gradle Y” → 改成空工程模板里的版本号 |
| `app/build.gradle` | compileSdk 34 / targetSdk 34 | IDE 只装了 android-33 → 两个都改成 33 |

`minSdk 24` **不要降**：`dispatchGesture` 和 `canPerformGestures` 都是 API 24 起。

---

## 四、构建与安装

1. Sync Project with Gradle Files（首次要联网下依赖，3–15 分钟）
2. 构建：
   ```
   ./gradlew assembleDebug
   ```
   或直接点 IDE 的 Run / Build
3. 产物：`app/build/outputs/apk/debug/app-debug.apk`
   （IDE 文件树里长按它 → Install，或从 MT 手动安装）

首次构建会自动生成 debug 签名，不用自己配 keystore。

---

## 五、安装后的三步授权

1. 打开 App → 「打开无障碍设置」→ 找到「跳一跳助手」→ 开启
2. 回 App → 「授予悬浮窗权限」→ 允许
3. 回 App → 「显示悬浮面板」→ 切到微信，面板应浮在游戏上方

面板可拖动（拖左边文字区）、可调系数。

---

## 六、校准

默认系数 `1.36 × 屏幕宽/1080`，只是经验起点，必须微调：

| 落点 | 操作 |
|---|---|
| 跳短了（落在块前面） | 点「+」，一次 +0.02 |
| 跳远了（冲过块） | 点「-」 |
| 正好 | 记住这个数字 |
| 想重置 | 长按中间那个系数数字 |

一般 3 次就能稳。

---

## 七、故障排查

| 症状 | 原因 / 处理 |
|---|---|
| Sync 卡在 Could not download | 网络问题。在 settings.gradle 的两个 repositories 里加阿里云镜像 |
| `Unresolved reference: GestureDescription` | compileSdk < 24，或 xml 里漏了 canPerformGestures |
| 面板显示了但按钮点不动 | 拖动监听吃事件。本工程已用“文字区当手柄”避开 |
| `BadTokenException` | 没给悬浮窗权限 |
| 完全没反应，面板停在「就绪」 | 开了 TalkBack；或某些 ROM 限制无障碍注入，需换 Shizuku 方案 |
| 提示「注入失败」 | 无障碍服务没连上，去设置里关掉重开 |
| 手势执行了但游戏不动 | 注入点落在悬浮窗上。本工程已在注入前移除取点层、给面板加 NOT_TOUCHABLE |
| 装完提示「应用未安装」 | 包名冲突或签名不同，先卸载旧版 |

---

## 八、关键实现点（想自己改的时候看这里）

1. **长按怎么模拟**：`StrokeDescription` 传一个只有 `moveTo` 的点路径 + 时长，
   等于原地按下不动、再松手，正好对上游戏的蓄力机制。
2. **坐标两套**：注入用 `event.getRawX/getRawY`（屏幕绝对坐标）；
   画圆标注用 `event.getX/getY`（视图坐标）。两者分开存。
3. **注入前必须**：`removeView(取点层)` + 给面板加 `FLAG_NOT_TOUCHABLE`，
   否则注入的手势会被自己的悬浮窗吃掉。
4. **状态机**：`startPoint` 一个字段搞定：为空存起点，非空算距离并触发。

---

## 九、声明

仅限个人娱乐、自用调试。微信用户协议一般不允许外挂，
别拿去刷排行榜或做任何商业用途。
