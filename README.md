# ShareBox

文件管理器 - 支持本地/FTP/SMB文件浏览，内置FTP服务器。

## 功能

- **本地文件** - 浏览内部存储、SD卡、USB OTG（exFAT）
- **FTP客户端** - 连接中兴U30移动WiFi或其他FTP服务器，上传/下载
- **SMB客户端** - 访问Windows共享文件夹、NAS
- **FTP服务端** - 让电脑通过FTP访问手机文件

## 编译方法

### 方式一：在 Termux 中编译

```bash
# 1. 安装依赖
pkg install openjdk-17 git

# 2. 下载 Android SDK
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mv cmdline-tools latest
rm commandlinetools-linux-11076708_latest.zip

# 3. 安装 SDK 组件
yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$HOME/android-sdk "platforms;android-35" "build-tools;35.0.0" "platform-tools"

# 4. 克隆项目
cd ~
git clone <your-repo-url> ShareBox
cd ShareBox

# 5. 配置 SDK 路径
echo "sdk.dir=$HOME/android-sdk" > local.properties

# 6. 编译
chmod +x gradlew
./gradlew assembleDebug --no-daemon

# 7. APK 位置
ls app/build/outputs/apk/debug/app-debug.apk
```

### 方式二：用 Android Studio

直接打开项目，Sync Gradle，Build APK。

## 技术栈

- Kotlin + Jetpack Compose (Material 3)
- Apache Commons Net (FTP客户端)
- smbj (SMB客户端)
- Apache FtpServer (FTP服务端)
- minSdk 29 (Android 10+), targetSdk 35 (Android 15)

## 权限说明

- `MANAGE_EXTERNAL_STORAGE` - 需要在设置中手动授予「所有文件访问权限」
- `FOREGROUND_SERVICE_DATA_SYNC` - FTP服务前台运行
- `INTERNET` - FTP/SMB网络通信
