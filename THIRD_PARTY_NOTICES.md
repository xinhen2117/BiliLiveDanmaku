# 第三方许可与归属

项目自有代码采用根目录 [MIT License](LICENSE)，版权署名为 `Copyright (c) 2026 xinhen2117`。下列第三方材料不受这一署名重新许可，仍保留其原始许可证、版权与 NOTICE。

本清单是对当前构建声明、指定版本的官方 POM、上游许可及部分实际 JAR/AAR 的定向核对，不是完整 SBOM、源码来源鉴定、法律意见或未来 APK 分发合规保证。依赖的传递版本可能因 Gradle 解析而变化，发布前应重新检查实际产物。

## 仓库中捆绑的 Gradle Wrapper

- `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 来自 [Gradle v8.5.0](https://github.com/gradle/gradle/tree/v8.5.0)，采用 **Apache-2.0**，不是项目自有 MIT 代码。
- 保留了两份脚本的原始版权/许可头，JAR 未修改，且内含 `META-INF/LICENSE`。
- 可直接阅读的许可副本：[Gradle-Wrapper-Apache-2.0.txt](licenses/Gradle-Wrapper-Apache-2.0.txt)，逐字节来自[官方 Wrapper 许可](https://github.com/gradle/gradle/blob/v8.5.0/platforms/core-runtime/wrapper/src/executable/resources/META-INF/LICENSE)。
- Wrapper JAR SHA-256：`d3b261c2820e9e3d8d639ed084900f11f4a86050a8f83342ade7b6bc9b0d2bdd`。该小型 JAR 中未发现独立 NOTICE 或非 Gradle 库类；不要把完整 Gradle 发行包中其他组件的 NOTICE 误称为本 Wrapper 的依赖。重新分发完整 Gradle 发行包时，仍须保留发行包自身的许可与通知。

## 运行时依赖：已检查的版本与例外

| 组件 / 声明版本 | 上游许可与来源 |
| --- | --- |
| Compose BOM 2024.01.00；UI、graphics、tooling-preview、material-icons-extended 1.6.0；Material3 1.1.2 | Apache-2.0；[官方 BOM/POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2024.01.00/compose-bom-2024.01.00.pom)、[AndroidX 源码](https://android.googlesource.com/platform/frameworks/support/) |
| AndroidX core-ktx 1.12.0；lifecycle runtime/viewmodel/runtime-compose 2.7.0；activity-compose 1.8.2；navigation-compose 2.7.6；datastore-preferences 1.0.0 | Apache-2.0 主体；DataStore 内置 protobuf 的 BSD-3-Clause 例外见下文；[官方 Google Maven](https://maven.google.com/)、[AndroidX 源码](https://android.googlesource.com/platform/frameworks/support/) |
| OkHttp 4.12.0 | Apache-2.0 主体；[版本 POM](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.pom)。**内置公共后缀列表数据另受 MPL-2.0 约束，见下文** |
| Okio 3.6.0（OkHttp 依赖） | Apache-2.0；[版本 POM](https://repo.maven.apache.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.pom) |
| ZXing Core 3.5.3 | Apache-2.0；[父 POM](https://repo.maven.apache.org/maven2/com/google/zxing/zxing-parent/3.5.3/zxing-parent-3.5.3.pom)、[上游 LICENSE](https://github.com/zxing/zxing/blob/zxing-3.5.3/LICENSE)、[NOTICE](https://github.com/zxing/zxing/blob/zxing-3.5.3/NOTICE)。Core 的 PDF417 部分源于 Barcode4J，保留对应归属 |
| kotlinx.coroutines 1.7.3（android/core） | Apache-2.0；[版本 POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.7.3/kotlinx-coroutines-android-1.7.3.pom)、[NOTICE](https://github.com/Kotlin/kotlinx.coroutines/blob/1.7.3/license/NOTICE.txt) |
| kotlinx.serialization 1.6.2（json/core） | Apache-2.0；[版本 POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/1.6.2/kotlinx-serialization-json-1.6.2.pom)、[NOTICE](https://github.com/Kotlin/kotlinx.serialization/blob/v1.6.2/license/NOTICE.txt) |
| Kotlin stdlib 1.9.22 | Apache-2.0 主体，部分 JVM 数学代码采用 Boost-1.0；[版本 POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.22/kotlin-stdlib-1.9.22.pom)、[上游许可清单](https://github.com/JetBrains/kotlin/blob/v1.9.22/license/README.md)。不能将所有 Kotlin 子组件笼统视作同一许可证 |
| Brotli decoder 0.1.2 | MIT，版权属于 Brotli Authors；[父 POM](https://repo.maven.apache.org/maven2/org/brotli/parent/0.1.2/parent-0.1.2.pom)、[对应源码许可](https://github.com/google/brotli/blob/4363f2d74b8af198f78b08e10cd0b3ead48af590/LICENSE)。Maven sources JAR 的 15 个 Java 文件与该提交一致，不使用本项目的版权署名替换它 |

DataStore preferences-core 1.0.0 的 JAR 还包含重命名到 `androidx.datastore.preferences.protobuf` 的 protobuf 3.10.0 实现，采用 BSD-3-Clause；保留其 [Google 版权及完整许可](app/src/main/assets/open_source_licenses/Protobuf-3.10.0-BSD.txt)，[上游版本来源](https://github.com/protocolbuffers/protobuf/blob/v3.10.0/LICENSE)。不能把这部分仅按 AndroidX 的 Apache 声明处理。

### OkHttp 中的 MPL-2.0 公共后缀数据

OkHttp JAR 的 `okhttp3/internal/publicsuffix/NOTICE` 明确说明 `publicsuffixes.gz` 编译自 [Public Suffix List](https://publicsuffix.org/list/public_suffix_list.dat)，采用 MPL-2.0。该通知及数据保留原许可，不被根目录 MIT 声明覆盖。

来源：[OkHttp 4.12.0 的通知](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/resources/okhttp3/internal/publicsuffix/NOTICE)、[上游公共后缀源数据仓库](https://github.com/publicsuffix/list)。重新分发或修改这些数据时，应按 MPL 保留通知，并保证相应版本的源代码形式可获得；不能把动态更新的列表链接当作已核实某个 APK 所含数据的精确版本。

## 随应用保留的许可材料

[app/src/main/assets/open_source_licenses/](app/src/main/assets/open_source_licenses/) 包含项目 MIT 副本、通用 Apache-2.0、Brotli 原始 MIT、protobuf BSD-3-Clause、适用的 Boost-1.0、MPL-2.0，以及 ZXing、coroutines、serialization、OkHttp 公共后缀数据的上游 NOTICE。文件原文和来源见该目录的 `SOURCES.md`。

部分依赖 JAR 并未附带完整许可/NOTICE，因此不能只依靠 Maven POM 或打包规则。本项目补充上述已识别文本，并把常见 `META-INF/LICENSE*`、`NOTICE*`、`AL2.0`、`LGPL2.1` 从删除改为合并重复资源。这只是保留已存在的文本，不意味着项目引入了 LGPL 依赖，也不是为缺少许可的材料授予新许可。

CI 在编译 debug APK 后逐字节检查上述 assets 是否进入 APK，并确认项目 MIT 副本与根目录一致。这项检查只验证这些文本未被打包丢失，不替代依赖或许可证义务审查。

ZXing 的上游整体 NOTICE 原样保留，其中也列有其他模块的归属；这不表示本应用依赖了 ZXing 的所有模块。Kotlin Compiler 的整体 NOTICE 不作为 stdlib 的运行时 NOTICE 混入应用。

## 仅测试 / 调试 / 构建用途

这些坐标保留其原始条款，不能因根目录 MIT 而重新许可。测试 APK 与构建工具的传递依赖也须按实际分发范围另行检查。

- JUnit 4.13.2：EPL-1.0，[官方 POM](https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom)。Hamcrest 1.3 为 BSD 风格许可，见[官方父 POM](https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-parent/1.3/hamcrest-parent-1.3.pom)。
- Mockito 5.8.0：MIT，[官方 POM](https://repo.maven.apache.org/maven2/org/mockito/mockito-core/5.8.0/mockito-core-5.8.0.pom)；其 Byte Buddy / Objenesis 等传递组件不受项目 MIT 重新许可。
- org.json 20231013：Public Domain，[该版本 LICENSE](https://github.com/stleary/JSON-java/blob/20231013/LICENSE)、[POM](https://repo.maven.apache.org/maven2/org/json/json/20231013/json-20231013.pom)。这是测试依赖；不要把历史 JSON 版本的限制误套到这一版本。
- coroutines-test 1.7.3、MockWebServer 4.12.0、AndroidX Test JUnit 1.1.5、Espresso 3.5.1、Compose UI test/tooling 1.6.0：对应版本 POM 声明 Apache-2.0；JUnit 等底层测试库仍保留自己的条款。
- Android Gradle Plugin 8.2.2、Compose Compiler 1.5.8、Kotlin Gradle/serialization 插件 1.9.22：对应 POM 声明 Apache-2.0 主体，构建工具发行包还可能包含其他许可的组件；请保留工具自身的完整许可/NOTICE。Kotlin 的[编译器 NOTICE](https://github.com/JetBrains/kotlin/blob/v1.9.22/license/NOTICE.txt)明确属于编译器发行包。

## 发布或升级前

重新导出实际解析的依赖图，检查传递组件、上游 NOTICE 和产物中保留的文本；若修改了第三方代码，应标明修改并满足相应许可证。保留必要的源代码获取途径，尤其是公共后缀数据。此清单不替代这些发布检查，也不授予 Bilibili 的名称、标识、服务或数据的额外权利。
