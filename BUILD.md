# 构建 ColorGPT

需要 PowerShell 7、JDK 17、Android SDK Platform 36 和 Build Tools 36。

从 [Shizuku Maven 仓库](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/) 下载 13.1.5 版本的 api、provider、aidl、shared AAR。分别解压到依赖目录下的同名子目录，保留各目录的 classes.jar。从 [Google Maven](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar) 下载 AndroidX Annotation 1.3.0，保存为依赖目录根部的 annotation.jar。

```powershell
./Build-Shizuku.ps1 -JdkHome <jdk17> -AndroidJar <android.jar> -BuildTools <build-tools36> -ShizukuLibraries <deps> -OutputDirectory <out>
```

公开分发请保管好长期签名密钥。可通过 -SigningKey 和 -SigningAlias 指定密钥库与别名，通过 COLORGPT_SIGNING_STORE_PASS 和 COLORGPT_SIGNING_KEY_PASS 环境变量提供密码。默认构建会使用或生成本地开发密钥。Android 覆盖更新要求签名一致。随本次交付的 APK 沿用 0.4.0 的签名，可用于覆盖升级。

源码复用了 ColorGoogle 38ead061d06d5f24b5ff20b92f58d5e4632a0298 的 DirectLogdReader，并适配 ColorOS 热词、电源键与 ChatGPT Voice。源码包不含签名私钥、设备 APK、反编译文件或设备日志。
