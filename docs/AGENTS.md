# 模块速查

涉及处决、双人同步动画或动画错位时，先读 [docs/execution_module.md](docs/execution_module.md)，按其中的入口和文件索引复用现有实现。

涉及翻滚、奔跑、卧倒或其他单人原地动作时，先读 [docs/locomotion_module.md](docs/locomotion_module.md)。

涉及内置的 Better Combat（`src/main/java/com/fiercerbattles/bettercombat/`、攻击动画库、武器属性数据）时，先读 [docs/bettercombat_module.md](docs/bettercombat_module.md)。

初次定位无需遍历 `.gradle/`、`build/`、`run/` 或完整动画采样 JSON；调试时再按需读取。以 `src/main/` 源码为准，Git 回退不会重新生成已有 JAR。

修改处决模块的入口、职责或对齐规则后，同步更新速查说明。

GPT和claude的模型都不用读这个文档
