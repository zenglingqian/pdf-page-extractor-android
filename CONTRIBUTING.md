# 贡献指南

感谢你对 PDF 页面提取（Android 版）的关注！欢迎通过以下方式贡献：

## 提交 Issue

- **Bug 报告**：请附上应用版本、手机型号与 Android 版本、复现步骤、错误提示。若应用崩溃，重启后会弹出崩溃日志，请截图一并提供。
- **功能建议**：请说明使用场景与期望行为。

## 提交 Pull Request

1. Fork 本仓库并创建特性分支：`git checkout -b feature/xxx`
2. 确保构建通过：`./gradlew :app:assembleRelease`（需要 Android SDK + JDK 17）
3. 运行单元测试：`./gradlew :app:testDebugUnitTest`
4. 遵循现有代码风格（4 空格缩进，中文注释）
5. 在 PR 描述中说明改动内容与测试结果

## 代码结构说明

- `app/src/main/java/com/pdfx/extractor/PdfEngine.kt`：PDF 解析与提取核心（纯 Kotlin 手写解析器），修改后务必用多种 PDF 回归测试
- `PreviewActivity.kt`：预览选页，依赖系统 `PdfRenderer`，注意同一时刻只能打开一页
- `XlsxIO.kt`：xlsx 模板读写，注意 XML 转义与共享字符串处理
- `MainActivity.kt`：主界面与交互入口

## 许可证

提交代码即表示你同意以项目的 MIT 许可证发布你的贡献。
