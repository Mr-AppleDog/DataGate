<template>
  <div class="p-2">
    <el-alert
      v-if="isDataGateDba"
      class="mb-2"
      title="DataGate DBA 已拥有全局数据库访问权限，无需再申请查询权限"
      description="导出、DML、DDL 和 Redis 写操作仍按安全规则走独立工单与审计。"
      type="success"
      show-icon
      :closable="false"
    />
    <el-card shadow="hover">
      <template #header>
        <el-row :gutter="10">
          <el-col :span="1.5">
            <el-button v-if="!isDataGateDba" type="primary" plain icon="Plus" @click="handleApply">申请权限</el-button>
          </el-col>
          <el-col :span="1.5">
            <el-button icon="Refresh" @click="getList">刷新</el-button>
          </el-col>
        </el-row>
      </template>

      <el-table v-loading="loading" :data="applicationList" border>
        <el-table-column label="申请ID" prop="id" width="80" align="center" />
        <el-table-column label="申请人" prop="applicantId" width="90" align="center" />
        <el-table-column label="审批人" prop="approverId" width="90" align="center" />
        <el-table-column label="资源ID" prop="resourceId" width="90" align="center" />
        <el-table-column label="动作" prop="action" width="90" align="center" />
        <el-table-column label="效果" prop="effect" width="90" align="center" />
        <el-table-column label="状态" width="110" align="center">
          <template #default="scope">
            <el-tag :type="statusTagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="授权ID" width="100" align="center">
          <template #default="scope">
            <span>{{ scope.row.grantId == null || scope.row.grantId === 0 ? '-' : scope.row.grantId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="理由" prop="reason" :show-overflow-tooltip="true" min-width="160" />
        <el-table-column label="创建时间" prop="createTime" width="170" align="center" />
        <el-table-column label="操作" fixed="right" width="210" align="center">
          <template #default="scope">
            <el-button v-if="canApprove(scope.row)" link type="primary" icon="Check" @click="handleApprove(scope.row)">审批</el-button>
            <el-button v-if="canApprove(scope.row)" link type="danger" icon="Close" @click="handleReject(scope.row)">拒绝</el-button>
            <el-button v-if="canCancel(scope.row)" link type="warning" icon="CircleClose" @click="handleCancel(scope.row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>

      <pagination
        v-show="total > 0"
        v-model:page="queryParams.pageNum"
        v-model:limit="queryParams.pageSize"
        :total="total"
        @pagination="getList"
      />
    </el-card>

    <!-- 申请权限弹窗 -->
    <el-dialog v-model="dialog.visible" :title="dialog.title" width="640px" append-to-body>
      <el-form ref="applyFormRef" :model="applyForm" :rules="rules" label-width="100px">
        <el-form-item label="数据源" prop="dataSourceId">
          <el-select
            v-model="applyForm.dataSourceId"
            placeholder="请选择数据源"
            filterable
            clearable
            style="width: 100%"
            @change="handleDsChange"
          >
            <el-option v-for="ds in dataSources" :key="ds.id" :label="`${ds.name}（${ds.type}）`" :value="ds.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="资源" prop="resourceId">
          <el-tree-select
            v-model="applyForm.resourceId"
            :data="resourceOptions"
            :props="{ label: 'physicalName', value: 'id', children: 'children' }"
            value-key="id"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            placeholder="请选择资源"
            clearable
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="动作" prop="action">
          <el-select v-model="applyForm.action" style="width: 100%">
            <el-option label="查询 QUERY" value="QUERY" />
          </el-select>
        </el-form-item>
        <el-form-item label="审批人ID" prop="approverId">
          <el-input-number v-model="applyForm.approverId" :min="1" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="失效时间" prop="expiresTs">
          <el-date-picker
            v-model="applyForm.expiresTs"
            type="datetime"
            value-format="x"
            placeholder="请选择失效时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="申请理由" prop="reason">
          <el-input
            v-model="applyForm.reason"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 6 }"
            placeholder="请说明申请原因"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitApply">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="DbWorkflow">
import { list as listDataSources } from '@/api/db/datasource';
import { list as listResources } from '@/api/db/resource';
import { list as listApplications, apply, approve, reject, cancel } from '@/api/db/workflow';
import type { DataSourceVO } from '@/api/db/datasource';
import type { ResourceVO } from '@/api/db/resource';
import type { WorkflowAppVO } from '@/api/db/workflow';
import { useUserStore } from '@/store/modules/user';

const userStore = useUserStore();
const currentUserId = computed(() => Number(userStore.userId));
const isDataGateDba = computed(() => userStore.roles.includes('dba'));

const loading = ref(false);
const submitting = ref(false);
const total = ref(0);
const applicationList = ref<WorkflowAppVO[]>([]);
const dataSources = ref<DataSourceVO[]>([]);
const resourceOptions = ref<ResourceVO[]>([]);

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10
});

const dialog = reactive({
  visible: false,
  title: '申请查询权限'
});

const applyFormRef = ref();
const initApplyForm = () => ({
  dataSourceId: undefined as number | undefined,
  resourceId: undefined as number | undefined,
  action: 'QUERY',
  approverId: undefined as number | undefined,
  expiresTs: undefined as number | undefined,
  reason: ''
});
const applyForm = reactive(initApplyForm());

const rules = {
  dataSourceId: [{ required: true, message: '请选择数据源', trigger: 'change' }],
  resourceId: [{ required: true, message: '请选择资源', trigger: 'change' }],
  action: [{ required: true, message: '请选择动作', trigger: 'change' }],
  approverId: [{ required: true, message: '请输入审批人ID', trigger: 'blur' }],
  expiresTs: [{ required: true, message: '请选择失效时间', trigger: 'change' }],
  reason: [{ required: true, message: '请输入申请理由', trigger: 'blur' }]
};

/** 终态状态集合（不再可审批/撤销） */
const TERMINAL_STATUS = ['APPROVED', 'REJECTED', 'CANCELED', 'CANCELLED', 'GRANTED', 'FINISHED', 'COMPLETED', 'TERMINATED', 'CLOSED'];

const isPending = (status?: string) => !TERMINAL_STATUS.includes((status || '').toUpperCase());

const canApprove = (row: WorkflowAppVO) => isPending(row.status) && row.approverId === currentUserId.value;
const canCancel = (row: WorkflowAppVO) => isPending(row.status) && row.applicantId === currentUserId.value;

const statusTagType = (status?: string): any => {
  switch ((status || '').toUpperCase()) {
    case 'APPROVED':
    case 'GRANTED':
      return 'success';
    case 'REJECTED':
      return 'danger';
    case 'CANCELED':
    case 'CANCELLED':
      return 'info';
    case 'PENDING':
    case 'APPLIED':
      return 'warning';
    default:
      return 'primary';
  }
};

const statusLabel = (status?: string) => status || '-';

/** 兼容 R/TableDataInfo/裸数组 的通用解包 */
const unwrapList = (body: any): any[] => {
  if (Array.isArray(body)) return body;
  if (Array.isArray(body?.rows)) return body.rows;
  if (Array.isArray(body?.data)) return body.data;
  if (Array.isArray(body?.data?.rows)) return body.data.rows;
  return [];
};

/** 加载数据源 */
const loadDataSources = async () => {
  try {
    const res: any = await listDataSources();
    dataSources.value = unwrapList(res);
  } catch (e) {
    dataSources.value = [];
  }
};

/** 加载资源树根节点 */
const loadRootResources = async (dataSourceId: number) => {
  try {
    const res: any = await listResources({ dataSourceId });
    resourceOptions.value = unwrapList(res);
  } catch (e) {
    resourceOptions.value = [];
  }
};

const handleDsChange = () => {
  applyForm.resourceId = undefined;
  resourceOptions.value = [];
  if (applyForm.dataSourceId) {
    loadRootResources(applyForm.dataSourceId);
  }
};

/** 查询申请列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res: any = await listApplications({ pageNum: queryParams.pageNum, pageSize: queryParams.pageSize });
    const body = res || {};
    applicationList.value = body.rows ?? body.data?.rows ?? [];
    total.value = body.total ?? body.data?.total ?? 0;
  } catch (e) {
    applicationList.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
};

const handleApply = () => {
  if (isDataGateDba.value) {
    ElMessage.info('DataGate DBA 已拥有全局查询权限，无需申请');
    return;
  }
  Object.assign(applyForm, initApplyForm());
  resourceOptions.value = [];
  dialog.visible = true;
};

const submitApply = () => {
  applyFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    if (!applyForm.dataSourceId || !applyForm.resourceId || !applyForm.approverId || !applyForm.expiresTs) return;
    submitting.value = true;
    try {
      await apply({
        approverId: applyForm.approverId,
        subjectType: 'USER',
        subjectId: currentUserId.value,
        resourceId: applyForm.resourceId,
        action: applyForm.action,
        effect: 'ALLOW',
        expiresAt: new Date(Number(applyForm.expiresTs)).toISOString(),
        reason: applyForm.reason
      });
      ElMessage.success('申请已提交');
      dialog.visible = false;
      await getList();
    } catch (e) {
      // 错误提示由请求拦截器统一处理
    } finally {
      submitting.value = false;
    }
  });
};

const promptMessage = (title: string, placeholder: string) =>
  ElMessageBox.prompt(placeholder, title, {
    inputType: 'textarea',
    inputPlaceholder: '请输入意见（可选）',
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  });

const handleApprove = async (row: WorkflowAppVO) => {
  try {
    const { value } = await promptMessage('审批通过', '审批意见');
    await approve(row.id, value || '');
    ElMessage.success('审批通过');
    await getList();
  } catch (e) {
    // 用户取消或请求失败
  }
};

const handleReject = async (row: WorkflowAppVO) => {
  try {
    const { value } = await promptMessage('拒绝申请', '请输入拒绝理由');
    await reject(row.id, value || '');
    ElMessage.success('已拒绝');
    await getList();
  } catch (e) {
    // ignore
  }
};

const handleCancel = async (row: WorkflowAppVO) => {
  try {
    const { value } = await promptMessage('撤销申请', '请输入撤销说明');
    await cancel(row.id, value || '');
    ElMessage.success('已撤销');
    await getList();
  } catch (e) {
    // ignore
  }
};

onMounted(() => {
  loadDataSources();
  getList();
});
</script>
