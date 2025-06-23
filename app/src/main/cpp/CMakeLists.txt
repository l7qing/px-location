cmake_minimum_required(VERSION 3.4.1)

# 设置库名称和源文件
add_library(
        location_injector
        SHARED
        LocationInjector.cpp
)

# 查找并链接Android日志库
find_library(
        log-lib
        log
)

# 链接库
target_link_libraries(
        location_injector
        ${log-lib}
) 