# 第三方软件声明 / Third-party notices

RProbeDesk 的项目源码采用 GNU Affero General Public License v3。下列组件由各自的著作权人按其许可提供。原文随包位置和对应源码见下文。[项目许可证](LICENSE.txt)

RProbeDesk is distributed under the GNU Affero General Public License v3. The components below retain their respective copyrights and terms. This page identifies the license texts in the installed software and the corresponding upstream sources. [Project license](LICENSE.txt)

## 两端共用 / Both hosts

| 组件 / Component | 许可、作者及源码 / Terms, credits and source |
| --- | --- |
| Kotlin standard library 2.1.21 and kotlinx.coroutines 1.10.2 | Apache License 2.0, JetBrains and contributors. [Kotlin](https://github.com/JetBrains/kotlin/tree/v2.1.21) · [Coroutines](https://github.com/Kotlin/kotlinx.coroutines/tree/1.10.2) |
| Compose Multiplatform 1.8.2, Material and AndroidX dependencies | Apache License 2.0, JetBrains and the Android Open Source Project. The Android host includes Activity Compose 1.10.1. See the Gradle dependency reports below for platform-specific modules. [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/tree/v1.8.2) · [AndroidX](https://android.googlesource.com/platform/frameworks/support/) |
| OkHttp 4.12.0 / Okio | Apache License 2.0, Square and contributors. [OkHttp](https://github.com/square/okhttp/tree/parent-4.12.0) · [Okio](https://github.com/square/okio) · [License](app/src/commonMain/composeResources/files/licenses/Apache-2.0.txt) |
| JetBrains Mono | SIL Open Font License 1.1, The JetBrains Mono Project Authors. [Font and source](https://github.com/JetBrains/JetBrainsMono) · [Bundled OFL text](app/src/commonMain/composeResources/files/licenses/jetbrains_mono-OFL.txt) |

中文与英文许可引用指向同一份上游原文。可在源码根目录运行以下命令查看两个实际运行时的依赖树。For the resolved dependency graph, run the reports from the repository root.

```powershell
.\gradlew.bat :app:dependencies --configuration desktopRuntimeClasspath
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath
```

## Windows MSI

- **Eclipse Temurin OpenJDK 17.0.21.1+2**。MSI 包含精简的 Windows x64 Java 运行时。已安装目录中的 `runtime/legal/<module>/` 保存各模块的许可证、例外条款和第三方声明。The bundled runtime retains the OpenJDK GPLv2 terms, the Classpath Exception where designated, the Assembly Exception and module-specific notices. This build records source revision `41b754a57540`. [OpenJDK source](https://github.com/adoptium/jdk17u/tree/41b754a57540) · [Temurin build scripts](https://github.com/adoptium/temurin-build/tree/8d9fd6fa96e5066571a13823b07c2934e5958d8b)
- **Java Native Access 5.6.0**。Windows uses JNA for DPAPI under its Apache-2.0 option. [Source and terms](https://github.com/java-native-access/jna/tree/5.6.0) · [License](app/src/commonMain/composeResources/files/licenses/Apache-2.0.txt)
- **LWJGL 3.3.6 / Native File Dialog Extended**。Windows APK 文件选择器使用 LWJGL（BSD-3-Clause）和 Native File Dialog Extended（Zlib）。The Windows APK picker includes both libraries. [LWJGL license](app/src/desktopMain/resources/licenses/LWJGL-LICENSE.txt) · [Native File Dialog Extended license](app/src/desktopMain/resources/licenses/NFD-LICENSE.txt)
- **Skiko 0.9.4.2**。Apache License 2.0，JetBrains 及贡献者，包含改编自 Android Open Source Project 的代码。Skiko provides the desktop graphics bindings and includes code adapted from AOSP. [Skiko source](https://github.com/JetBrains/skiko/tree/v0.9.4.2) · [Bundled NOTICE](app/src/commonMain/composeResources/files/licenses/skiko-NOTICE.txt) · [Apache License 2.0](app/src/commonMain/composeResources/files/licenses/Apache-2.0.txt)
- **Skia m132-a00c390e98-1**。Skia 采用 BSD 三条款许可。原生渲染包中的字体、图像解码、压缩和图形组件保留各自声明。Skia uses the three-clause BSD license. This software is based in part on the work of the Independent JPEG Group and includes work of the FreeType Team. The collected texts identify their source files and include ICU, HarfBuzz, FreeType, libjpeg-turbo, libpng, libwebp, zlib and other upstream components. [Skia license](app/src/commonMain/composeResources/files/licenses/skia-LICENSE.txt) · [Bundled dependency notices](app/src/commonMain/composeResources/files/licenses/skia-dependency-notices.txt) · [Matching native build](https://github.com/JetBrains/skia-pack/releases/tag/m132-a00c390e98-1)

Windows 安装目录中的 `app/app-desktop-*.jar` 可作为 ZIP 打开，上述字体与渲染器声明位于 `composeResources/dev.rprobedesk.resources/files/licenses/`。`app/RProbeDesk.cfg` 列出应用类路径。Windows 上的 adb 和 scrcpy 由使用者另行安装。

Open the installed `app/app-desktop-*.jar` as a ZIP and read `composeResources/dev.rprobedesk.resources/files/licenses/` for the font and graphics notices. The installed `app/RProbeDesk.cfg` lists the application classpath. Windows users obtain the adb and scrcpy tools separately. [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) · [scrcpy](https://github.com/Genymobile/scrcpy)

## Android APK

将 APK 作为 ZIP 打开。字体等共用声明位于 `assets/composeResources/dev.rprobedesk.resources/files/licenses/`，设备传输与镜像组件的声明位于 `assets/licenses/`。Open the APK as a ZIP. These two directories hold the shared resource notices and the Android transport and mirror licenses respectively. [Android component credits](app/src/androidMain/assets/licenses/COMPONENTS.txt)

| 组件 / Component | 许可、作者及源码 / Terms, credits and source |
| --- | --- |
| LibADB Android 3.1.1 | Muntashir Al-Islam and credited contributors. This distribution uses its Apache-2.0 option with additional BSD-3-Clause and MIT notices. [Source](https://github.com/MuntashirAkon/libadb-android/tree/3.1.1) · [License choice](app/src/androidMain/assets/licenses/libadb-COPYING.txt) · [BSD](app/src/androidMain/assets/licenses/libadb-BSD-3-Clause.txt) · [MIT](app/src/androidMain/assets/licenses/libadb-MIT.txt) |
| Conscrypt Android 2.5.3 and BoringSSL | Apache-2.0 and component-specific terms. The Conscrypt AAR identifies BoringSSL revision `49f0329110a1d93a5febc2bceceedc655d995420`. The APK retains Conscrypt, Harmony, Netty and BoringSSL notices. [Conscrypt source](https://github.com/google/conscrypt/tree/2.5.3) · [Conscrypt NOTICE](app/src/androidMain/assets/licenses/conscrypt-NOTICE.txt) · [BoringSSL license](app/src/androidMain/assets/licenses/boringssl-LICENSE.txt) |
| Bouncy Castle bcprov-jdk15to18 1.81 | The Legion of the Bouncy Castle Inc., Bouncy Castle license. [Source](https://github.com/bcgit/bc-java/tree/r1rv81) · [Bundled license](app/src/androidMain/assets/licenses/bouncycastle-LICENSE.html) |
| SPAKE2 Android 2.2.1 and spake2-c | Muntashir Al-Islam and credited contributors. GNU Lesser General Public License v3, with component-specific notices in the sources. The sources used for the pairing library are supplied below. [Java/JNI license](app/src/androidMain/assets/licenses/spake2-LICENSE.txt) · [C library license](app/src/androidMain/assets/licenses/spake2-c-LICENSE.txt) · [Accompanying GPL text](app/src/androidMain/assets/licenses/GPL-3.0.txt) |
| scrcpy-server 3.3.4 | Genymobile and contributors, Apache-2.0. The APK includes the unmodified server at `assets/scrcpy-server-v3.3.4`, SHA-256 `8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e`. [Server source](https://github.com/Genymobile/scrcpy/tree/v3.3.4/server) · [Upstream build instructions](https://github.com/Genymobile/scrcpy/blob/v3.3.4/doc/build.md) · [Bundled license](app/src/androidMain/assets/licenses/scrcpy-LICENSE.txt) |

### 重建与替换 SPAKE2 / Rebuilding and replacing SPAKE2

1. 在新的工作目录解开 Java/JNI 源码，再将 C 源码解入其中的子模块目录。仓库内是 `.tar.gz`；APK 在 `assets/licenses/sources/` 内提供相同 tar 内容，文件名为 `spake2-java-2.2.1.tar`、`spake2-c-0d15933.tar`。Extract the Java/JNI archive into a new working directory, then unpack the C archive into its submodule path. From an APK, use the two uncompressed `.tar` filenames in place of the `.tar.gz` names below. [Java/JNI archive](app/src/androidMain/assets/licenses/sources/spake2-java-2.2.1.tar.gz) · [C archive](app/src/androidMain/assets/licenses/sources/spake2-c-0d15933.tar.gz)

   ```powershell
   tar -xf spake2-java-2.2.1.tar.gz
   tar -xf spake2-c-0d15933.tar.gz --strip-components 1 -C spake2-java-2.2.1/android/src/main/cpp/spake2-c
   ```

2. 修改所需源码。在 Android SDK Manager 中准备 SDK Platform 35、Build Tools 35.0.0、NDK 和 CMake，使用 JDK 17。从解开的 Java 工程运行以下命令。Make your changes, install the listed Android SDK and native build tools, select JDK 17 and run the upstream wrapper. The C submodule revision is `0d15933e5ba3e662cb01245a7ac0dc9fca3eac31`. [SPAKE2 2.2.1 source tree](https://github.com/MuntashirAkon/spake2-java/tree/2.2.1)

   ```powershell
   cd spake2-java-2.2.1
   .\gradlew.bat :android:assembleRelease
   ```

3. 将生成的 `android/build/outputs/aar/android-release.aar` 复制到自己检出的 RProbeDesk 的 `local-libs/spake2-android.aar`。在该副本的 `app/build.gradle.kts` 中，顶层加入下面的排除规则，在已有 `androidMain` 的 `dependencies` 块中加入本地 AAR。Copy the rebuilt AAR to the named location in your RProbeDesk checkout, exclude the published dependency and add the local file as shown. [Application build file](app/build.gradle.kts)

   ```kotlin
   // Top level in app/build.gradle.kts
   configurations.configureEach {
       exclude(group = "com.github.MuntashirAkon.spake2-java", module = "spake2-android")
   }
   // Inside androidMain's existing dependencies block
   implementation(files(rootProject.file("local-libs/spake2-android.aar")))
   ```

4. 回到 RProbeDesk 工程根目录运行 `.\gradlew.bat :app:assembleDebug`，在自己的测试配置上安装、验证带修改库的 APK。已有正式版与本地调试版的签名不同，建议选用独立测试配置以保留现有数据。From the RProbeDesk project root, build with the same command and install the resulting debug APK on a test device or emulator. A separate test installation preserves data from any existing release signed with a different key. [中文构建说明](README.md) · [English build instructions](README_EN.md)
