# Gradle 布局

本目录只放**可以**集中存放的构建辅助文件。工程入口仍必须在仓库根目录。

```
仓库根/
├── settings.gradle.kts   # 工程名、模块 include、仓库（不可移动）
├── build.gradle.kts      # 根插件别名 apply false（不可移动）
├── gradle.properties     # JVM / AndroidX / Kotlin 全局属性（不可移动）
├── gradlew / gradlew.bat # Wrapper 启动脚本（不可移动）
└── gradle/
    ├── README.md         # 本说明
    ├── libs.versions.toml
    └── wrapper/
        ├── gradle-wrapper.jar
        └── gradle-wrapper.properties
```

唯一应用模块是 `:app`（`app/build.gradle.kts`）。自制 DSH 插件在 `../plugins/`，不参与本 Gradle 图。
