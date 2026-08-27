<template>
  <div class="p-2">
    <!-- 连接配置 -->
    <el-card shadow="hover" class="mb-2">
      <template #header>
        <span>查询控制台</span>
      </template>
      <el-form :inline="true" label-width="90px">
        <el-form-item label="数据源">
          <el-select
            v-model="form.dataSourceId"
            placeholder="请选择数据源"
            clearable
            filterable
            style="width: 280px"
            @change="handleDataSourceChange"
          >
            <el-option
              v-for="ds in dataSources"
              :key="ds.id"
              :label="`${ds.name}（${ds.type} ${ds.host}:${ds.port}）`"
              :value="ds.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="数据库名">
          <el-input v-model="form.databaseName" placeholder="可选，留空使用默认库" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item label="最大行数">
          <el-input-number v-model="form.maxRows" :min="1" :max="100000" :step="100" controls-position="right" style="width: 160px" />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- SQL 编辑器 -->
    <el-card shadow="hover" class="mb-2">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>SQL 语句</span>
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
        placeholder="请输入 SQL 语句（如 SELECT * FROM ...）"
        style="font-family: Consolas, Monaco, monospace"
      />
    </el-card>

    <!-- 结果 -->
    <el-card shadow="hover">
      <template #header>
        <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap">
          <span style="font-weight: 600">查询结果</span>
          <el-tag v-if="result" type="success">状态：{{ result.status }}</el-tag>
          <el-tag v-if="result" type="info">行数：{{ result.rowCount }}</el-tag>
          <el-tag v-if="result" type="info">耗时：{{ result.durationMs }} ms</el-tag>
          <el-tag v-if="result" type="info">字节：{{ result.resultBytes }}</el-tag>
          <el-tag v-if="result && result.truncated" type="warning">已截断</el-tag>
          <el-tag v-if="result && result.executionNo" type="info">执行号：{{ result.executionNo }}</el-tag>
          <el-tag v-if="result && result.errorCode" type="danger">错误码：{{ result.errorCode }}</el-tag>
        </div>
      </template>
      <el-alert
        v-if="result && result.errorCode"
        :title="`查询失败：${result.errorCode}`"
        type="error"
        :closable="false"
        show-icon
        class="mb-2"
      />
      <el-table v-if="tableColumns.length" :data="tableData" border height="440" size="small" stripe>
        <el-table-column type="index" label="#" width="50" fixed="left" align="center" />
        <el-table-column
          v-for="col in tableColumns"
          :key="col"
          :prop="col"
          :label="col"
          :show-overflow-tooltip="true"
          min-width="140"
        />
      </el-table>
      <el-empty v-else description="暂无查询结果，请在上方输入 SQL 并点击执行" />
    </el-card>
  </div>
</template>

<script setup lang="ts" name="DbConsole">
import { list as listDataSources } from '@/api/db/datasource';
import { query, cancel } from '@/api/db/console';
import type { DataSourceVO } from '@/api/db/datasource';
import type { QueryResultVO } from '@/api/db/console';

const dataSources = ref<DataSourceVO[]>([]);
const loading = ref(false);
const result = ref<QueryResultVO | null>(null);

const form = reactive({
  dataSourceId: undefined as number | undefined,
  databaseName: '',
  statement: 'SELECT 1;',
  maxRows: 1000
});

const canExecute = computed(() => !!form.dataSourceId && form.statement.trim().length > 0);
const canCancel = computed(() => !!result.value?.executionNo);

// 兼容两种返回结构：
// 1) columns 为字符串数组、rows 为 [{value:[cell...]}]
// 2) 实际后端：columns 为 [{name,...}] 对象数组、rows 为 [[cell...]] 二维数组
const tableColumns = computed<string[]>(() => {
  const cols: any[] = result.value?.columns || [];
  return cols.map((c: any, i: number) =>
    typeof c === 'string' ? c : (c?.name ?? c?.columnName ?? 'col' + i)
  );
});
const tableData = computed(() => {
  if (!result.value || !result.value.rows) return [];
  const cols = tableColumns.value;
  return result.value.rows.map((r: any) => {
    const cells: any[] = Array.isArray(r) ? r : (r?.value || []);
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
  try {
    const res: any = await listDataSources();
    dataSources.value = unwrapList(res);
  } catch (e) {
    dataSources.value = [];
  }
};

const handleDataSourceChange = () => {
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
