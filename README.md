# 水卡读取 App

通过手机 NFC 读取水卡（预付费水表卡）数据的 Android 应用。

## 功能

- ✅ NFC 自动检测卡片类型（IsoDep / NfcA）
- ✅ 显示卡片 UID / ATS / 技术类型
- ✅ 自动尝试多种常见水卡 AID 读取
- ✅ 通用 APDU 命令探测读取
- ✅ HEX 原始数据展示
- ✅ 余额自动解析
- ✅ 数据保存到本地文件

## 编译

### 方式一：Android Studio
1. 用 Android Studio 打开此项目
2. 等待 Gradle 同步完成
3. Build → Build APK

### 方式二：命令行编译
```bash
# 安装 Android SDK + Gradle 后：
./gradlew assembleDebug

# APK 输出位置：
# app/build/outputs/apk/debug/app-debug.apk
```

### 方式三：IDEA + Android Plugin
```bash
# 安装 gradle 后：
gradle wrapper --gradle-version=8.7
./gradlew assembleDebug
```

## 安装
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用
1. 打开 App，确保 NFC 已开启
2. 将水卡贴在手机背面 NFC 天线处
3. App 自动读取并显示数据
4. 点击「保存数据」导出 HEX 文件

## 卡片 AID 说明

预付费水卡因厂商不同，AID 也不同。常见的有：
- `D27600008501010200` — NDEF 标准
- `A0000003060000` — 城镇供水公共服务平台
- `A00000020101` — 常见水表应用

如果读不到数据，需要用专业工具抓取卡片和充值机之间的通信，找出真实 AID 后修改 `COMMON_AIDS` 数组。

## 已知问题

- 部分水卡使用私有加密协议，App 无法直接解读 HEX 数据含义
- 余额解析仅为示例，实际格式因厂商而异
- 写入功能暂未实现
