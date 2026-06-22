# 空调定时助手

基于无障碍服务（AccessibilityService）的vivo手机自动开空调App。半夜定时自动打开vivo智能遥控，开启空调并设置定时关闭。

## 功能

- 设置每天定时开启空调的时间
- 自动打开vivo智能遥控App
- 自动点击指定空调遥控器卡片
- 自动点击电源按钮开机
- 自动设置定时关闭
- 支持立即测试功能

## 项目结构

```
AirconTimer/
├── app/src/main/java/com/example/aircontimer/
│   ├── MainActivity.kt              # 主界面
│   ├── service/
│   │   ├── AutoClickService.kt      # 无障碍服务（核心自动点击逻辑）
│   │   └── KeepAliveService.kt      # 前台服务保活
│   ├── receiver/
│   │   ├── AlarmReceiver.kt         # 定时广播接收器
│   │   └── BootReceiver.kt          # 开机自启
│   └── util/
│       ├── AlarmScheduler.kt        # 定时任务调度
│       ├── PreferenceManager.kt     # 配置存储
│       └── NotificationHelper.kt    # 通知管理
├── app/src/main/res/
│   ├── layout/activity_main.xml     # 主界面布局
│   ├── xml/accessibility_service_config.xml  # 无障碍服务配置
│   └── drawable/                    # 背景、图标资源
└── build.gradle.kts                 # 构建配置
```

## 使用步骤

### 1. 安装App

```bash
# 用Android Studio打开项目，编译安装
# 或直接用gradlew
./gradlew installDebug
```

### 2. 首次设置（重要）

打开App后，按顺序完成：

1. **开启无障碍服务** → 跳转到系统设置，找到"空调定时助手"并开启
2. **关闭电池优化** → 确保App能在后台运行
3. **确认空调名称** → 输入与vivo智能遥控中一致的名称（如"华凌空调遥控器"）
4. **设置开启时间** → 选择每天自动开启的时间（默认凌晨2:00）
5. **选择定时关闭时长** → 30分钟/1小时/1.5小时/2小时/3小时

### 3. 开启自动任务

点击"开启自动任务"按钮，App会：
- 启动前台服务保活
- 设置精确闹钟定时触发

### 4. 测试

点击"立即测试"，3秒后会立即执行一次完整的自动点击流程，验证配置是否正确。

## 核心流程

```
定时触发 (AlarmManager)
    ↓
启动无障碍服务 (AutoClickService)
    ↓
打开vivo智能遥控App
    ↓
点击空调遥控器卡片（一级界面）
    ↓
点击电源按钮（二级界面）
    ↓
点击"定时"按钮
    ↓
设置定时关闭时间
    ↓
确认完成
```

## 适配说明

### vivo智能遥控包名

代码中已预设几个可能的包名：
- `com.vivo.remote`
- `com.vivo.smartremote`
- `com.android.remote`
- `com.iqoo.remote`

如果自动打开失败，请用ADB确认实际包名：
```bash
adb shell pm list packages | grep -i remote
```

### 界面适配

自动点击逻辑基于你提供的截图：
- 一级界面：通过文字"华凌空调遥控器"定位卡片
- 二级界面：通过"电源"、"空调开关"、"定时"等文字定位按钮

如果vivo智能遥控更新后UI变化，可能需要调整 `AutoClickService.kt` 中的查找逻辑。

## 权限说明

| 权限 | 用途 |
|------|------|
| `SCHEDULE_EXACT_ALARM` | Android 12+ 精确闹钟权限 |
| `WAKE_LOCK` | 唤醒设备执行操作 |
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `RECEIVE_BOOT_COMPLETED` | 开机重新设置定时 |
| `BIND_ACCESSIBILITY_SERVICE` | 无障碍服务自动点击 |

## 注意事项

1. **无障碍服务必须保持开启**，否则无法自动点击
2. **不要清理后台**，建议将App加入系统白名单
3. **vivo智能遥控不能被卸载或禁用**
4. **定时界面可能需要根据实际UI微调**（目前基于通用逻辑实现）
5. 如果vivo系统更新导致智能遥控UI变化，可能需要更新代码

## 可能的问题

### Q: 无法自动打开vivo智能遥控
A: 用ADB确认包名，修改 `AutoClickService.kt` 中的 `POSSIBLE_PACKAGES`

### Q: 点击位置不对
A: 开启开发者选项中的"显示布局边界"，确认按钮文字是否与代码中一致

### Q: 定时任务没有触发
A: 检查是否关闭了电池优化，是否开启了精确闹钟权限

### Q: 定时设置界面无法操作
A: 定时设置界面（三级界面）的UI结构需要实际抓取，当前代码为通用实现，可能需要根据实际界面调整

## 技术栈

- Kotlin
- Android AccessibilityService
- AlarmManager
- ForegroundService
- SharedPreferences
