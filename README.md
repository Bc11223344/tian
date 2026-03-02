# 天道拦截器

一个基于VPN和iptables的网络拦截工具，用于拦截指定的域名、IP和端口。

## 功能特性
- 支持拦截指定域名（包括通配符）
- 支持拦截指定IP地址
- 支持拦截指定端口
- 提供导入/导出规则功能
- 基于VPN服务实现DNS拦截
- 基于iptables实现IP和端口拦截

## 技术栈
- Java
- Android SDK
- dnsjava库
- VPN Service
- iptables

## 构建说明

### 使用GitHub Actions构建
1. Fork本仓库到你的GitHub账户
2. 推送代码到main分支
3. 在GitHub仓库的Actions页面查看构建进度
4. 构建完成后，在Artifacts中下载APK文件

### 本地构建
1. 安装Android Studio
2. 打开项目
3. 等待Gradle同步完成
4. 点击"Build" > "Build Bundle(s) / APK(s)" > "Build APK(s)"

## 权限说明
- `INTERNET`：访问网络
- `ACCESS_NETWORK_STATE`：获取网络状态
- `ACCESS_WIFI_STATE`：获取WiFi状态
- `WRITE_EXTERNAL_STORAGE`：写入外部存储（用于导出规则）
- `READ_EXTERNAL_STORAGE`：读取外部存储（用于导入规则）
- `BIND_VPN_SERVICE`：绑定VPN服务

## 使用说明
1. 开启拦截开关
2. 授予VPN权限
3. 导入或编辑拦截规则
4. 开始使用拦截功能

## 注意事项
- 需要Root权限才能使用iptables拦截
- 拦截规则会影响网络连接，请谨慎使用
- 首次使用需要授予VPN权限