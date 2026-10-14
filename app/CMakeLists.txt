cmake_minimum_required(VERSION 3.21)
project(hproxy VERSION 0.1.0 LANGUAGES CXX)

# h.proxy GUI: Qt Quick front end talking to h_core over gRPC.
# Requires Qt 6.8+ (Quick, QuickControls2, Protobuf, Grpc).

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 6.8 REQUIRED COMPONENTS Core Gui Quick QuickControls2 Widgets Protobuf Grpc Svg)
qt_standard_project_setup(REQUIRES 6.8)
qt_policy(SET QTP0001 NEW)

set(PROTO ${CMAKE_CURRENT_SOURCE_DIR}/../go/rpc/gen/libcore.proto)
set(BUILD_SHARED_LIBS OFF) # generated protobuf/grpc code links statically into h.exe
qt_add_protobuf(hproxy_proto PROTO_FILES ${PROTO} OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/gen)
qt_add_grpc(hproxy_grpc CLIENT PROTO_FILES ${PROTO} OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/gen)
target_link_libraries(hproxy_grpc PRIVATE hproxy_proto)

qt_add_executable(h WIN32 MACOSX_BUNDLE
    src/main.cpp
    src/core_client.cpp src/core_client.h
    src/store.cpp src/store.h
    src/system.cpp src/system.h
)

# Singletons must be flagged before the module is declared.
set_source_files_properties(qml/Theme.qml qml/App.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)

qt_add_qml_module(h
    URI HProxy
    VERSION 1.0
    QML_FILES
        qml/Main.qml
        qml/Theme.qml
        qml/App.qml
        qml/Landscape.qml
        qml/Portrait.qml
        qml/components/Mark.qml
        qml/components/Toggle.qml
        qml/components/Seg.qml
        qml/components/Rail.qml
        qml/components/StatusCard.qml
        qml/components/NodeRow.qml
        qml/components/Field.qml
        qml/components/RuleBox.qml
        qml/components/ImportDialog.qml
        qml/pages/NodesPage.qml
        qml/pages/GroupsPage.qml
        qml/pages/RoutesPage.qml
        qml/pages/AppsPage.qml
        qml/pages/ConnsPage.qml
        qml/pages/LogsPage.qml
        qml/pages/SettingsPage.qml
    RESOURCES
        res/mark.svg
)

target_link_libraries(h PRIVATE
    Qt6::Core Qt6::Gui Qt6::Quick Qt6::QuickControls2 Qt6::Widgets Qt6::Protobuf Qt6::Grpc Qt6::Svg
    hproxy_proto hproxy_grpc
)
target_compile_definitions(h PRIVATE HP_VERSION="${PROJECT_VERSION}")

if(WIN32)
    set_target_properties(h PROPERTIES OUTPUT_NAME "h")
    target_link_libraries(h PRIVATE wininet)
endif()

install(TARGETS h RUNTIME DESTINATION . BUNDLE DESTINATION .)
