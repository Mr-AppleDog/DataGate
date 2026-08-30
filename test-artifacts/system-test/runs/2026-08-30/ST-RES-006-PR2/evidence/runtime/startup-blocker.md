# Submitted PR service startup blocker

Target artifact: `ruoyi-admin/target/ruoyi-admin.jar` built from PR head `437dea36f4f74400e998401a8bfaa1b2d98d293d`.

Packaging completed successfully:

```text
mvn -pl ruoyi-admin -am package -DskipTests
BUILD SUCCESS
44/44 reactor modules successful
```

The normal startup reached PostgreSQL and validated all 21 Flyway migrations, then failed before opening port `18081`:

```text
java.io.IOException: Unable to establish loopback connection
Caused by: java.net.SocketException: Invalid argument: connect
RedissonClient: failed to create a child event loop
```

A second, runtime-only attempt used lazy initialization and excluded Redisson plus its lock auto-configuration. It progressed to Undertow startup but the same host runtime could not provide the NIO transport:

```text
starting server: Undertow - 2.3.24.Final
ApplicationContextException: Failed to start bean 'webServerStartStop'
WebServerException: Unable to start embedded Undertow
IllegalArgumentException: XNIO001001: No XNIO provider found
```

Observed terminal state:

- process exited;
- TCP port `18081` never listened;
- no product HTTP request was sent;
- no test data was changed;
- this is an environment blocker, not evidence that the original defect remains.
