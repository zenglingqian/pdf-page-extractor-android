# PDF 页面提取（Android 版）

一个轻量级的安卓应用，用于从 PDF 文件中按页码或页码范围批量提取页面，生成新的 PDF 文件。支持预览选页、从 Excel 批量导入页面范围。

所有处理均在本地完成，PDF 文件不会上传到任何服务器；应用无需任何网络权限。

## ✨ 功能特性

- **按页码提取**：输入起始页和结束页，提取指定范围的页面
  - 结束页留空 = 只提取该一页（例如：起始页 `3`、结束页留空 → 只提取第 3 页）
  - 支持多组范围，例如：第 3 页、第 5 页、第 9~18 页
- **预览选页**：网格展示每页缩略图，点按即可选中/取消，确认后自动压缩为连续范围填入输入框
- **Excel 批量粘贴**：在 Excel 中选中两列数据（第一列起始页、第二列结束页）复制，在「起始页」输入框中长按粘贴，即可批量填入
- **Excel 模板导入**：内置「下载导入模板」功能，生成带示例和填写说明的 `.xlsx` 模板；编辑保存后通过「导入 Excel」自动读取并填入
- **纯本地处理**：不联网、不上传，隐私安全
- **零第三方 PDF 依赖**：内置纯 Kotlin 手写 PDF 解析引擎，无需 PDFBox 等库，体积小、兼容性好

## 📥 下载与安装

前往 [Releases](../../releases) 页面下载最新的 `PdfPageExtractor-vX.Y.Z.apk`，在手机上安装即可。

> 要求：Android 5.0（API 21）及以上。首次安装需允许「安装未知来源应用」。

## 🚀 使用方法

1. **选择 PDF 文件**：点击「选择 PDF 文件…」，应用会显示总页数。
2. **设置页面范围**（任选其一或组合）：
   - **预览选页**：点击「预览选页…」，点按缩略图选中需要的页面，点「确认选择」；
   - **手动输入**：点击「＋ 添加一组」，填写起始页/结束页；
   - **Excel 粘贴**：在 Excel 中复制两列数据，在「起始页」输入框中长按粘贴；
   - **模板导入**：点击「下载 Excel 导入模板」→ 编辑 → 点击「导入 Excel」上传。
3. **执行提取**：点击「提取页面并保存…」，选择保存位置，完成。

**范围输入示例**（对应"提取第 3 页、第 5 页、第 9~18 页"）：

| # | 起始页 | 结束页 |
|---|--------|--------|
| 1 | 3      | （留空）|
| 2 | 5      | （留空）|
| 3 | 9      | 18     |

## 🛠 从源码构建

需要 [Android Studio](https://developer.android.com/studio)（或 Android SDK + JDK 17）：

```bash
./gradlew :app:assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。

> 仓库内附带开发用签名 `release.keystore`（密码见 `app/build.gradle.kts`），仅用于本地构建；正式发布请替换为你自己的签名。

## 📁 项目结构

```
pdf-page-extractor-android/
├── app/
│   ├── build.gradle.kts            # 应用配置与签名
│   └── src/main/
│       ├── java/com/pdfx/extractor/
│       │   ├── PdfEngine.kt        # PDF 解析与提取核心（纯 Kotlin 手写解析器）
│       │   ├── MainActivity.kt     # 主界面：选文件、范围设置、提取
│       │   ├── PreviewActivity.kt  # 预览选页（缩略图网格）
│       │   ├── XlsxIO.kt           # xlsx 模板读写
│       │   ├── BatchTextParser.kt  # 批量粘贴文本解析
│       │   ├── PasteAwareEditText.kt # 支持批量粘贴的输入框
│       │   └── CrashHandler.kt     # 崩溃捕获与日志
│       └── res/                    # 布局、字符串、图标
└── dist/                           # 构建产物（apk）
```

## ✅ 测试

单元测试位于 `app/src/test/`，覆盖：

- 批量粘贴文本解析（多行/多列/无效行过滤）
- xlsx 模板写入/读取往返
- PDF 引擎：页数统计、多范围提取、去重、越界报错
- 真实 PDF 样本：头部垃圾字节、嵌套引用、xref 流、10 页文件

运行：

```bash
./gradlew :app:testDebugUnitTest
```

## ⚠️ 已知限制

- 不支持加密（带密码）的 PDF
- 提取结果保留原始页面内容与质量，不做重新渲染
- 预览缩略图依赖系统 `PdfRenderer`，个别特殊 PDF 可能无法渲染（不影响手动输入页码提取）

## 📄 许可证

本项目采用 [MIT 许可证](LICENSE)。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！提交前请确保 `./gradlew :app:assembleRelease` 构建通过，并运行单元测试。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 🐛 反馈问题

遇到问题请在 [Issues](../../issues) 中提交，并尽量附上：

1. 应用版本（崩溃日志弹窗顶部显示）
2. 手机型号与 Android 版本
3. 操作步骤与错误提示截图
4. 如有崩溃，重启应用后会弹出崩溃日志，请截图一并提供
