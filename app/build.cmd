@echo off
rem Configure + build the DuoBao Proxy GUI with MSVC and Qt. Usage: build.cmd [Release|Debug]
setlocal
set CFG=%1
if "%CFG%"=="" set CFG=Release
set QT=C:\Qt\6.8.3\msvc2022_64
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cd /d "%~dp0"
set PROTOC=C:\Qt\Tools\protoc\bin\protoc.exe
set PATH=C:\Qt\Tools\protoc\bin;%PATH%
cmake -S . -B build -G Ninja -DCMAKE_PREFIX_PATH=%QT% -DCMAKE_BUILD_TYPE=%CFG% -DProtobuf_DIR=C:\Qt\Tools\protoc\cmake || exit /b 1
rem Protobuf_DIR points at a stub CONFIG package that only exposes protoc; QtProtobuf brings its own runtime.
cmake --build build || exit /b 1
endlocal
