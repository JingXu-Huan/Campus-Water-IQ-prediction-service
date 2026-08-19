# RAG 评测集说明

`evaluation-set.jsonl` 一行一个用例，字段含义：

- `route`：期望路由，取值为 `rag`、`realtime_tool`、`hybrid`、`repair_draft`、`repair_confirm` 或 `refuse`。
- `expected_sources`：RAG 命中时应出现在 Top-K 的文档。
- `must_include`：回答应覆盖的关键表达。
- `must_not_include`：回答不应声称的表达。

评测时分别统计路由正确率、Top-K 文档命中率、关键要点覆盖率与拒答正确率。对实时和写操作问题，不能把知识库命中当作业务操作成功。
