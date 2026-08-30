# API case result

- Planned request: unauthenticated `GET /db/datasource/list?name=smoke-mysql&pageSize=10&pageNum=1`
- Expected: HTTP `401`, stable authentication response, no protected datasource name.
- Status: `BLOCKED`.
- Reason: the submitted PR service could not listen on the planned local port because the host JDK selector/XNIO provider failed during startup.
- Request count: `0`.

The passing handler contract test is recorded separately and is not promoted to an API or end-to-end pass.

