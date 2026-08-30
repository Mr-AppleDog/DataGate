# Focused regression evidence

- Worktree: `D:/codex-worktree/pr2-regression/DataGate`
- Commit: `437dea36f4f74400e998401a8bfaa1b2d98d293d`
- Command: `mvn -pl ruoyi-common/ruoyi-common-satoken,ruoyi-modules/ruoyi-db-executor -am test -DskipTests=false`
- Result: `BUILD SUCCESS` in 1 minute 40 seconds.
- `SaTokenExceptionHandlerTest`: 1 test, 0 failures, 0 errors, 0 skipped.
- `ruoyi-db-executor`: 37 tests, 0 failures, 0 errors, 0 skipped.
- Related reactor modules: 16/16 successful.

The contract test verifies both the servlet HTTP status and the stable response body code are `401` for an unauthenticated request.
