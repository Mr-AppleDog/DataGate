<template>
  <div class="p-2">
    <!-- 连接配置 -->
    <el-card shadow="hover" class="mb-2">
      <template #header>
        <span>查询控制台</span>
      </template>
      <el-form :inline="true" label-width="90px">
        <el-form-item label="引擎类型">
          <el-select v-model="form.engineType" placeholder="全部" clearable style="width: 150px" @change="handleEngineTypeChange">
            <el-option label="全部" value="" />
            <el-option v-for="t in engineTypes" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源">
          <el-select
            v-model="form.dataSourceId"
            placeholder="请选择数据源"
            clearable
            filterable
            style="width: 280px"
            @change="handleDataSourceChange"
          >
            <el-option v-for="ds in filteredDataSources" :key="ds.id" :label="`${ds.name}（${ds.type} ${ds.host}:${ds.port}）`" :value="ds.id" />
          </el-select>
          <el-tag v-if="selectedDsType" :type="engineTagType(selectedDsType)" effect="dark" class="ml-2">
            {{ engineLabel(selectedDsType) }}
          </el-tag>
        </el-form-item>
        <el-form-item label="数据库名">
          <el-input v-model="form.databaseName" :placeholder="redisDbPlaceholder" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item label="最大行数">
          <el-input-number v-model="form.maxRows" :min="1" :max="100000" :step="100" controls-position="right" style="width: 160px" />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="!dataSourceLoading && dataSources.length === 0"
        title="暂无已启用的数据源。请先到“数据源管理”添加 QUERY 凭据，测试连接成功后点击“启用”。"
        type="warning"
        :closable="false"
        show-icon
      />
    </el-card>

    <!-- 语句编辑器（SQL / Redis 命令按引擎适配） -->
    <el-card shadow="hover" class="mb-2">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>{{ isRedis ? 'Redis 命令' : 'SQL 语句' }}</span>
          <div>
            <el-button type="primary" icon="Search" :loading="loading" :disabled="!canExecute" @click="handleQuery">执行</el-button>
            <el-button type="warning" icon="CircleClose" :disabled="!canCancel" @click="handleCancel">取消</el-button>
          </div>
        </div>
      </template>
      <el-input
        v-model="form.statement"
        type="textarea"
        :autosize="{ minRows: 6, maxRows: 18 }"
        :placeholder="statementPlaceholder"
        style="font-family: Consolas, Monaco, monospace"
      />
    </el-card>

    <!-- 结果 -->
    <el-card shadow="hover">
      <template #header>
        <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap">
          <span style="font-weight: 600">查询结果</span>
          <el-tag v-if="result" :type="resultStatusTagType">状态：{{ result.status }}</el-tag>
          <el-tag v-if="result" type="info">行数：{{ result.rowCount }}</el-tag>
          <el-tag v-if="result" type="info">耗时：{{ result.durationMs }} ms</el-tag>
          <el-tag v-if="result" type="info">字节：{{ result.resultBytes }}</el-tag>
          <el-tag v-if="result && result.truncated" type="warning">已截断</el-tag>
          <el-tag v-if="result && result.executionNo" type="info">执行号：{{ result.executionNo }}</el-tag>
          <el-tag v-if="result && result.errorCode" type="danger">错误码：{{ result.errorCode }}</el-tag>
        </div>
      </template>
      <el-alert v-if="result && result.errorCode" :title="resultErrorMessage" type="error" :closable="false" show-icon class="mb-2" />
      <el-table v-if="tableColumns.length" :data="tableData" border height="440" size="small" stripe>
        <el-table-column type="index" label="#" width="50" fixed="left" align="center" />
        <el-table-column v-for="col in tableColumns" :key="col" :prop="col" :label="col" :show-overflow-tooltip="true" min-width="140" />
      </el-table>
      <el-empty v-else description="暂无查询结果，请在上方输入 SQL 并点击执行" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="DbConsole">
import { listAvailable as listDataSources } from '@/api/db/datasource';
import { query, cancel } from '@/api/db/console';
import type { DataSourceVO } from '@/api/db/datasource';
import type { QueryResultVO } from '@/api/db/console';

const dataSources = ref<DataSourceVO[]>([]);
const dataSourceLoading = ref(false);
const loading = ref(false);
const result = ref<QueryResultVO | null>(null);

/** 引擎类型选项（PG/Redis 打通用户可见引擎选择） */
const engineTypes = [
  { label: 'MySQL', value: 'MYSQL' },
  { label: 'PostgreSQL', value: 'POSTGRESQL' },
  { label: 'Redis', value: 'REDIS' },
  { label: 'Tair', value: 'TAIR' }
];

const form = reactive({
  dataSourceId: undefined as number | undefined,
  engineType: '' as string,
  databaseName: '',
  statement: 'SELECT 1;',
  maxRows: 1000
});

const canExecute = computed(() => !!form.dataSourceId && form.statement.trim().length > 0);
const canCancel = computed(() => !!result.value?.executionNo);

const resultStatusTagType = computed<'success' | 'warning' | 'danger' | 'info'>(() => {
  switch (result.value?.status) {
    case 'SUCCEEDED':
      return 'success';
    case 'RUNNING':
      return 'warning';
    case 'REJECTED':
    case 'FAILED':
      return 'danger';
    default:
      return 'info';
  }
});

const resultErrorMessage = computed(() => {
  const code = result.value?.errorCode;
  if (code === 'RESOURCE_STATE_CONFLICT') {
    return '查询被拒绝：数据源尚未启用或状态刚刚发生变化。请到“数据源管理”完成凭据配置、连接测试并启用后重试。';
  }
  if (code === 'AUTH_RESOURCE_UNDISCOVERABLE') {
    return '查询被拒绝：表尚未同步到资源目录。请到“数据源管理”点击“同步”后重试。';
  }
  if (code === 'AUTH_RESOURCE_DENIED') {
    return '查询被拒绝：当前账号没有该资源的 QUERY 权限。请到“访问控制 → 查询权限”提交申请，审批通过后重试。';
  }
  return `查询失败：${code || '未知错误'}`;
});

/** 按引擎类型过滤的数据源列表 */
const filteredDataSources = computed(() => {
  if (!form.engineType) return dataSources.value;
  return dataSources.value.filter((ds) => ds.type === form.engineType);
});

/** 当前选中数据源的引擎类型 */
const selectedDsType = computed(() => {
  if (!form.dataSourceId) return '';
  const ds = dataSources.value.find((d) => d.id === form.dataSourceId);
  return ds?.type || '';
});

/** 是否 Redis/Tair（RESP 协议，命令而非 SQL） */
const isRedis = computed(() => selectedDsType.value === 'REDIS' || selectedDsType.value === 'TAIR');

/** 语句输入占位符按引擎适配 */
const statementPlaceholder = computed(() =>
  isRedis.value ? '请输入 Redis 命令（如 GET user:1 / SCAN 0 MATCH user:* COUNT 100 / HGETALL h:1）' : '请输入 SQL 语句（如 SELECT * FROM ...）'
);

/** Redis 逻辑 DB 输入占位（集群固定 0） */
const redisDbPlaceholder = computed(() => (isRedis.value ? '逻辑 DB（0-15，集群固定 0）' : '可选，留空使用默认库'));

/** 引擎标签颜色 */
const engineTagType = (type: string): '' | 'success' | 'warning' | 'danger' | 'info' => {
  switch (type) {
    case 'MYSQL':
      return 'info';
    case 'POSTGRESQL':
      return 'success';
    case 'REDIS':
      return 'danger';
    case 'TAIR':
      return 'warning';
    default:
      return '';
  }
};

const engineLabel = (type: string): string => {
  const t = engineTypes.find((e) => e.value === type);
  return t?.label || type;
};

// 兼容两种返回结构：
// 1) columns 为字符串数组、rows 为 [{value:[cell...]}]
// 2) 实际后端：columns 为 [{name,...}] 对象数组、rows 为 [[cell...]] 二维数组
const tableColumns = computed<string[]>(() => {
  const cols: any[] = result.value?.columns || [];
  return cols.map((c: any, i: number) => (typeof c === 'string' ? c : (c?.name ?? c?.columnName ?? 'col' + i)));
});
const tableData = computed(() => {
  if (!result.value || !result.value.rows) return [];
  const cols = tableColumns.value;
  return result.value.rows.map((r: any) => {
    const cells: any[] = Array.isArray(r) ? r : r?.value || [];
    const obj: Record<string, any> = {};
    cells.forEach((cell: any, i: number) => {
      const colName = cols[i] || 'col' + i;
      obj[colName] = cell?.value;
    });
    return obj;
  });
});

/** 兼容 R/TableDataInfo/裸数组 的通用解包 */
const unwrapList = (body: any): any[] => {
  if (Array.isArray(body)) return body;
  if (Array.isArray(body?.rows)) return body.rows;
  if (Array.isArray(body?.data)) return body.data;
  if (Array.isArray(body?.data?.rows)) return body.data.rows;
  return [];
};

/** 加载数据源列表 */
const loadDataSources = async () => {
  dataSourceLoading.value = true;
  try {
    const res: any = await listDataSources();
    dataSources.value = unwrapList(res);
    if (form.dataSourceId && !dataSources.value.some((item) => item.id === form.dataSourceId)) {
      form.dataSourceId = undefined;
    }
  } catch (e) {
    dataSources.value = [];
  } finally {
    dataSourceLoading.value = false;
  }
};

const handleDataSourceChange = () => {
  result.value = null;
  // 切换数据源时按引擎调整默认语句；仅当当前语句为默认占位时替换，不覆盖用户自定义输入
  const sqlDefault = 'SELECT 1;';
  const redisDefault = 'GET user:1';
  if (isRedis.value && form.statement === sqlDefault) {
    form.statement = redisDefault;
  } else if (!isRedis.value && form.statement === redisDefault) {
    form.statement = sqlDefault;
  }
};

/** 引擎类型切换：清空已选数据源与结果，引导重新选择 */
const handleEngineTypeChange = () => {
  form.dataSourceId = undefined;
  result.value = null;
};

/** 执行查询 */
const handleQuery = async () => {
  if (!canExecute.value) return;
  loading.value = true;
  result.value = null;
  try {
    const res: any = await query({
      dataSourceId: form.dataSourceId as number,
      databaseName: form.databaseName || undefined,
      statement: form.statement,
      maxRows: form.maxRows
    });
    // 后端可能用 R<QueryResultVO> 包装，也可能直接返回结果对象
    result.value = (res?.data ?? res) as QueryResultVO;
  } catch (e) {
    // 错误提示由请求拦截器统一处理
  } finally {
    loading.value = false;
  }
};

/** 取消查询 */
const handleCancel = async () => {
  if (!result.value?.executionNo) return;
  const execNo = result.value.executionNo;
  try {
    await cancel(execNo);
    ElMessage.success('取消请求已发送');
  } catch (e) {
    // ignore
  }
};

onMounted(() => {
  loadDataSources();
});
</script>
