cmake_minimum_required(VERSION 3.21)
project(duobaoproxy VERSION 1.0 LANGUAGES CXX)

# DuoBao Proxy GUI: Qt Quick front end talking to DuoBaoProxyCore over gRPC.
# Requires Qt 6.8+ (Quick, QuickControls2, Protobuf, Grpc).

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 6.8 REQUIRED COMPONENTS Core Gui Quick QuickControls2 Widgets Protobuf Grpc Svg)
qt_standard_project_setup(REQUIRES 6.8)
qt_policy(SET QTP0001 NEW)

set(PROTO ${CMAKE_CURRENT_SOURCE_DIR}/../go/rpc/gen/libcore.proto)
set(BUILD_SHARED_LIBS OFF) # generated protobuf/grpc code links statically into DuoBaoProxy.exe
qt_add_protobuf(duobaoproxy_proto PROTO_FILES ${PROTO} OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/gen)
qt_add_grpc(duobaoproxy_grpc CLIENT PROTO_FILES ${PROTO} OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/gen)
target_link_libraries(duobaoproxy_grpc PRIVATE duobaoproxy_proto)

qt_add_executable(DuoBaoProxy WIN32 MACOSX_BUNDLE
    src/main.cpp
    src/core_client.cpp src/core_client.h
    src/store.cpp src/store.h
    src/system.cpp src/system.h
)
if(WIN32)
    target_sources(DuoBaoProxy PRIVATE res/app.rc) # exe icon
endif()

# Singletons must be flagged before the module is declared.
set_source_files_properties(qml/Theme.qml qml/App.qml qml/Tests.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)

qt_add_qml_module(DuoBaoProxy
    URI DuoBaoProxy
    VERSION 1.0
    QML_FILES
        qml/Main.qml
        qml/Theme.qml
        qml/App.qml
        qml/Tests.qml
        qml/LogZh.js
        qml/Landscape.qml
        qml/Portrait.qml
        qml/components/Mark.qml
        qml/components/Toggle.qml
        qml/components/Input.qml
        qml/components/RoundAdd.qml
        qml/components/Seg.qml
        qml/components/Rail.qml
        qml/components/StatusCard.qml
        qml/components/NodeRow.qml
        qml/components/Field.qml
        qml/components/RuleBox.qml
        qml/components/ImportDialog.qml
        qml/components/PillButton.qml
        qml/components/PickPopup.qml
        qml/components/Dropdown.qml
        qml/components/PillMenu.qml
        qml/components/QrDialog.qml
        qml/components/CheckDot.qml
        qml/components/NodeEditor.qml
        qml/pages/NodesPage.qml
        qml/pages/GroupsPage.qml
        qml/pages/RoutesPage.qml
        qml/pages/AppsPage.qml
        qml/pages/ConnsPage.qml
        qml/pages/LogsPage.qml
        qml/pages/SettingsPage.qml
        qml/pages/HelpPage.qml
        qml/pages/TestSettings.qml
    RESOURCES
        res/logo.png
        res/logo-off.png
        res/logo-on.png
        res/logo-tun.png
)

target_link_libraries(DuoBaoProxy PRIVATE
    Qt6::Core Qt6::Gui Qt6::Quick Qt6::QuickControls2 Qt6::Widgets Qt6::Protobuf Qt6::Grpc Qt6::Svg
    duobaoproxy_proto duobaoproxy_grpc
)
target_compile_definitions(DuoBaoProxy PRIVATE DBP_VERSION="${PROJECT_VERSION}")

if(WIN32)
    target_link_libraries(DuoBaoProxy PRIVATE wininet dwmapi ole32 uuid)
endif()

install(TARGETS DuoBaoProxy RUNTIME DESTINATION . BUNDLE DESTINATION .)
