# h.proxy GUI

Qt Quick 前端，通过 gRPC 驱动 `h_core`。

## 依赖

- Qt 6.8 或更新，组件：Quick、QuickControls2、Widgets、Protobuf、Grpc、Svg、QuickEffects
- CMake 3.21+、Ninja
- Windows：Visual Studio 2022 Build Tools（MSVC v143）
- macOS：Xcode 命令行工具
- Linux：gcc 12+ 或 clang 15+

安装 Qt 最省事的方式：

```powershell
pip install aqtinstall
aqt install-qt windows desktop 6.8.3 win64_msvc2022_64 -m qtgrpc qtshadertools --outputdir C:\Qt
```

## 构建

```powershell
# 1. 核心
cd go
bash build_core.sh            # 输出 dist/windows-amd64/h_core.exe

# 2. 界面
cd ..\app
cmake -S . -B build -G Ninja -DCMAKE_PREFIX_PATH=C:\Qt\6.8.3\msvc2022_64 -DCMAKE_BUILD_TYPE=Release
cmake --build build

# 3. 组装运行目录
mkdir dist
copy build\h.exe dist\
copy ..\go\dist\windows-amd64\h_core.exe dist\
C:\Qt\6.8.3\msvc2022_64\bin\windeployqt.exe --qmldir qml --release dist\h.exe
```

xray 的 geosite / geoip 规则需要把 `geosite.dat`、`geoip.dat` 放到 `dist\` 里，GUI 启动核心时会把程序目录作为 `asset_dir` 传给核心。

## 目录

- `src/core_client.*`：启动 h_core 子进程，gRPC 调用，流量轮询
- `src/store.*`：节点、分组、设置、界面状态的 JSON 持久化
- `src/system.*`：系统代理、开机自启、托盘、剪贴板
- `qml/App.qml`：连接 / 测速 / 导入流程的状态机（单例）
- `qml/Theme.qml`：颜色与字体 token（单例），深浅主题
- `qml/Landscape.qml`、`qml/Portrait.qml`：两套布局，在设置里切换，默认横屏
- `qml/pages/`：节点、分组、路由、应用、连接、日志、设置
- `qml/components/`：可复用控件与兔子标志
