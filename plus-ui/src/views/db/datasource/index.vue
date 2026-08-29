<template>
  <div class="p-2">
    <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
      <div v-show="showSearch" class="search">
        <el-form ref="queryFormRef" :model="queryParams" :inline="true" label-width="72px">
          <el-form-item label="名称" prop="name">
            <el-input v-model="queryParams.name" placeholder="请输入数据源名称" clearable @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="环境" prop="environmentId">
            <el-select v-model="queryParams.environmentId" placeholder="全部环境" clearable style="width: 150px">
              <el-option v-for="item in environments" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="类型" prop="type">
            <el-select v-model="queryParams.type" placeholder="全部类型" clearable style="width: 160px">
              <el-option v-for="item in dataSourceTypes" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
            <el-button icon="Refresh" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>
      </div>
    </transition>

    <el-alert class="mb-2" type="info" :closable="false" show-icon>
      <template #title>
        数据源只保存结构化连接信息；数据库密码必须在“凭据”中按查询、变更、监控用途分别托管，任何读取接口都不会回显密码。启用流程：保存数据源 → 添加
        QUERY 凭据（可另配 MONITOR）→ 测试连接 → 启用（首次启用会自动同步元数据）。
      </template>
    </el-alert>

    <el-card shadow="never">
      <template #header>
        <el-row :gutter="10" class="mb8">
          <el-col :span="1.5">
            <el-button v-hasPermi="['db:datasource:add']" type="primary" plain icon="Plus" @click="handleAdd">新增数据源</el-button>
          </el-col>
          <el-col :span="1.5">
            <el-button icon="Refresh" plain @click="getList">刷新</el-button>
          </el-col>
          <right-toolbar v-model:show-search="showSearch" @query-table="getList" />
        </el-row>
      </template>

      <el-table v-loading="loading" :data="dataSourceList" border>
        <el-table-column label="名称" prop="name" min-width="150" show-overflow-tooltip />
        <el-table-column label="环境" width="100" align="center">
          <template #default="scope">
            <el-tag :type="environmentTagType(scope.row.environmentId)" effect="plain">
              {{ environmentName(scope.row.environmentId) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="引擎" prop="type" width="115" align="center">
          <template #default="scope">
            <el-tag effect="plain">{{ typeLabel(scope.row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="连接地址" min-width="200" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.host }}:{{ scope.row.port }}</template>
        </el-table-column>
        <el-table-column label="默认数据库" prop="defaultDatabase" min-width="130" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.defaultDatabase || '—' }}</template>
        </el-table-column>
        <el-table-column label="TLS" prop="tlsMode" width="105" align="center" />
        <el-table-column label="状态" width="105" align="center">
          <template #default="scope">
            <el-tooltip v-if="scope.row.lastErrorCode" :content="scope.row.lastErrorCode" placement="top">
              <el-tag :type="statusTagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="statusTagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近验证" min-width="170" align="center">
          <template #default="scope">{{ formatDateTime(scope.row.lastVerifiedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="390" align="center" class-name="small-padding fixed-width">
          <template #default="scope">
            <el-tooltip content="编辑非秘密配置" placement="top">
              <el-button v-hasPermi="['db:datasource:edit']" link type="primary" icon="Edit" @click="handleUpdate(scope.row)" />
            </el-tooltip>
            <el-tooltip content="管理凭据（密码只写）" placement="top">
              <el-button v-hasPermi="['db:credential:list']" link type="primary" icon="Key" @click="openCredentials(scope.row)" />
            </el-tooltip>
            <el-tooltip content="测试连接" placement="top">
              <el-button
                v-hasPermi="['db:datasource:verify']"
                type="primary"
                icon="Connection"
                size="small"
                plain
                :loading="isActionLoading(scope.row.id, 'verify')"
                @click="handleVerify(scope.row)"
              >
                测试连接
              </el-button>
            </el-tooltip>
            <el-tooltip v-if="scope.row.status !== 'ACTIVE' && scope.row.status !== 'ARCHIVED'" :content="enableHint(scope.row.status)" placement="top">
              <el-button
                v-hasPermi="['db:datasource:enable']"
                type="success"
                icon="VideoPlay"
                size="small"
                plain
                :disabled="!canEnable(scope.row.status)"
                :loading="isActionLoading(scope.row.id, 'status')"
                @click="handleEnable(scope.row)"
              >
                启用
              </el-button>
            </el-tooltip>
            <el-tooltip v-if="scope.row.status === 'ACTIVE'" content="停用" placement="top">
              <el-button
                v-hasPermi="['db:datasource:disable']"
                link
                type="danger"
                icon="VideoPause"
                :loading="isActionLoading(scope.row.id, 'status')"
                @click="handleDisable(scope.row)"
              />
            </el-tooltip>
            <el-tooltip content="立即重新同步库、表和字段目录" placement="top">
              <el-button
                v-hasPermi="['db:datasource:sync']"
                type="primary"
                icon="Refresh"
                size="small"
                plain
                :disabled="!canSync(scope.row.status)"
                :loading="isActionLoading(scope.row.id, 'sync')"
                @click="handleSync(scope.row)"
              >
                同步
              </el-button>
            </el-tooltip>
            <el-tooltip content="同步记录" placement="top">
              <el-button v-hasPermi="['db:datasource:query']" link type="primary" icon="List" @click="openSyncJobs(scope.row)" />
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <pagination v-show="total > 0" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" :total="total" @pagination="getList" />
    </el-card>

    <el-dialog v-model="dataSourceDialog.visible" :title="dataSourceDialog.title" width="780px" append-to-body destroy-on-close>
      <el-form ref="dataSourceFormRef" :model="form" :rules="rules" label-width="110px">
        <el-row :gutter="18">
          <el-col :span="12">
            <el-form-item label="环境" prop="environmentId">
              <el-select v-model="form.environmentId" placeholder="请选择环境" class="w-full">
                <el-option v-for="item in environments" :key="item.id" :label="`${item.name}（${riskLabel(item.riskLevel)}）`" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="数据源类型" prop="type">
              <el-select v-model="form.type" placeholder="请选择类型" class="w-full" @change="handleTypeChange">
                <el-option v-for="item in dataSourceTypes" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="名称" prop="name">
              <el-input v-model="form.name" maxlength="128" show-word-limit placeholder="例如：订单生产库" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="TLS 模式" prop="tlsMode">
              <el-select v-model="form.tlsMode" class="w-full">
                <el-option v-for="item in tlsModes" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="16">
            <el-form-item label="主机地址" prop="host">
              <el-input v-model="form.host" maxlength="255" placeholder="填写主机名或 IP，不允许 JDBC URL" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="端口" prop="port">
              <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" class="w-full" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="默认数据库" prop="defaultDatabase">
              <el-input v-model="form.defaultDatabase" maxlength="128" placeholder="可选" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="Owner 类型" prop="ownerType">
              <el-select v-model="form.ownerType" clearable placeholder="可选" class="w-full">
                <el-option label="用户" value="USER" />
                <el-option label="部门" value="DEPT" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="Owner ID" prop="ownerId">
              <el-input v-model="form.ownerId" inputmode="numeric" maxlength="20" placeholder="可选" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注" prop="remark">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="用途、负责人或接入说明" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-collapse>
          <el-collapse-item title="高级连接参数（仅允许非秘密白名单参数）" name="options">
            <el-alert type="warning" :closable="false" show-icon class="mb-3">
              <template #title>禁止填写 password、secret、token、accessKey 等秘密类参数；连接串由服务端构造。</template>
            </el-alert>
            <div v-for="(item, index) in optionRows" :key="index" class="option-row">
              <el-input v-model="item.key" maxlength="64" placeholder="参数名" />
              <el-input v-model="item.value" maxlength="512" placeholder="参数值" />
              <el-button type="danger" link icon="Delete" @click="removeOption(index)" />
            </div>
            <el-button type="primary" link icon="Plus" @click="addOption">添加参数</el-button>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button v-hasPermi="['db:datasource:verify']" icon="Connection" @click="openTemporaryConnectionTest">测试当前配置</el-button>
          <el-button :loading="submitLoading" type="primary" @click="submitDataSource">保存</el-button>
          <el-button @click="closeDataSourceDialog">取消</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="temporaryTestDialog.visible" title="测试当前连接配置" width="520px" append-to-body destroy-on-close>
      <el-alert type="info" :closable="false" show-icon class="mb-3">
        <template #title>测试账号和密码仅在本次请求内存中使用，测试完成立即清空，不会保存为托管凭据。</template>
      </el-alert>
      <el-form ref="temporaryTestFormRef" :model="temporaryTestForm" :rules="temporaryTestRules" label-width="100px">
        <el-form-item label="测试用户名" prop="username">
          <el-input v-model="temporaryTestForm.username" maxlength="128" autocomplete="off" placeholder="请输入数据库用户名" />
        </el-form-item>
        <el-form-item label="测试密码" prop="password">
          <el-input
            v-model="temporaryTestForm.password"
            type="password"
            maxlength="4096"
            show-password
            autocomplete="new-password"
            placeholder="请输入数据库密码"
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="temporaryTestForm.confirmPassword"
            type="password"
            maxlength="4096"
            show-password
            autocomplete="new-password"
            placeholder="请再次输入数据库密码"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="temporaryTestLoading" type="primary" icon="Connection" @click="submitTemporaryConnectionTest">开始测试</el-button>
        <el-button @click="closeTemporaryTestDialog">取消</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="credentialDrawer.visible" :title="`凭据管理 · ${credentialDrawer.dataSourceName}`" size="650px" destroy-on-close>
      <el-alert type="warning" :closable="false" show-icon class="mb-3">
        <template #title>密码只在提交时短暂使用，提交完成后表单立即清空；列表只显示用途、用户名和健康状态。</template>
      </el-alert>
      <div class="mb-3">
        <el-button
          v-hasPermi="['db:credential:add']"
          type="primary"
          icon="Plus"
          :disabled="availablePurposes.length === 0"
          @click="openCredentialDialog"
        >
          添加用途凭据
        </el-button>
        <el-button
          v-if="credentialDrawer.dataSourceId"
          v-hasPermi="['db:datasource:verify']"
          icon="Connection"
          :loading="isActionLoading(credentialDrawer.dataSourceId, 'verify')"
          @click="handleVerifyCredentialDrawer"
        >
          测试连接
        </el-button>
      </div>
      <el-table v-loading="credentialLoading" :data="credentials" border>
        <el-table-column label="用途" width="110" align="center">
          <template #default="scope">
            <el-tag effect="plain">{{ purposeLabel(scope.row.purpose) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用户名" prop="username" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="100" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'danger'">{{ statusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="165" align="center">
          <template #default="scope">{{ formatDateTime(scope.row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center">
          <template #default="scope">
            <el-tooltip v-if="scope.row.status === 'ACTIVE'" content="禁用凭据" placement="top">
              <el-button v-hasPermi="['db:credential:disable']" link type="danger" icon="VideoPause" @click="handleDisableCredential(scope.row)" />
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="credentialDialog.visible" title="添加用途凭据" width="520px" append-to-body destroy-on-close @closed="resetCredentialForm">
      <el-form ref="credentialFormRef" :model="credentialForm" :rules="credentialRules" label-width="95px" autocomplete="off">
        <el-alert type="info" :closable="false" show-icon class="mb-3" title="同一数据源的 QUERY、CHANGE、MONITOR 凭据必须使用不同的最小权限账号。" />
        <el-form-item label="用途" prop="purpose">
          <el-select v-model="credentialForm.purpose" placeholder="请选择用途" class="w-full">
            <el-option v-for="item in availablePurposes" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="credentialForm.username" maxlength="255" autocomplete="off" placeholder="数据库专用账号" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="credentialForm.password"
            type="password"
            maxlength="1024"
            autocomplete="off"
            name="datagate-database-secret"
            placeholder="只写一次，保存后不可查看"
          />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="credentialForm.confirmPassword"
            type="password"
            maxlength="1024"
            autocomplete="off"
            name="datagate-database-secret-confirm"
            placeholder="请再次输入"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button :loading="credentialSubmitLoading" type="primary" @click="submitCredential">安全保存</el-button>
          <el-button @click="credentialDialog.visible = false">取消</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog v-model="syncDialog.visible" :title="`元数据同步记录 · ${syncDialog.dataSourceName}`" width="900px" append-to-body>
      <el-table v-loading="syncJobLoading" :data="syncJobs" border>
        <el-table-column label="触发方式" prop="triggerType" width="100" align="center" />
        <el-table-column label="状态" width="95" align="center">
          <template #default="scope">
            <el-tag :type="syncStatusTagType(scope.row.status)">{{ scope.row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发现" prop="foundCount" width="80" align="right" />
        <el-table-column label="更新" prop="updatedCount" width="80" align="right" />
        <el-table-column label="下线" prop="droppedCount" width="80" align="right" />
        <el-table-column label="开始时间" min-width="170" align="center">
          <template #default="scope">{{ formatDateTime(scope.row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" min-width="170" align="center">
          <template #default="scope">{{ formatDateTime(scope.row.finishedAt) }}</template>
        </el-table-column>
        <el-table-column label="错误码" prop="errorCode" min-width="150">
          <template #default="scope">{{ scope.row.errorCode || '—' }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup name="DataSourceManagement" lang="ts">
import {
  add,
  addCredential,
  disable,
  disableCredential,
  enable,
  getInfo,
  list,
  listCredentials,
  listEnvironments,
  listSyncJobs,
  sync,
  testConnection,
  update,
  verify
} from '@/api/db/datasource';
import type {
  ConnectionTestResult,
  CredentialForm,
  CredentialVO,
  DataSourceConnectionTestForm,
  DataSourceForm,
  DataSourceId,
  DataSourceQuery,
  DataSourceVO,
  EnvironmentVO,
  MetadataSyncJobVO
} from '@/api/db/datasource';

interface ConnectionOptionRow {
  key: string;
  value: string;
}

interface CredentialFormModel extends CredentialForm {
  confirmPassword: string;
}

const { proxy } = getCurrentInstance() as ComponentInternalInstance;

const dataSourceTypes = [
  { label: 'MySQL', value: 'MYSQL', port: 3306 },
  { label: 'PostgreSQL', value: 'POSTGRESQL', port: 5432 },
  { label: 'Redis', value: 'REDIS', port: 6379 },
  { label: 'Tair', value: 'TAIR', port: 6379 }
] as const;
const tlsModes = [
  { label: '禁用（仅限受控例外）', value: 'DISABLE' },
  { label: '优先 TLS', value: 'PREFER' },
  { label: '要求 TLS', value: 'REQUIRE' },
  { label: '验证 CA', value: 'VERIFY_CA' },
  { label: '完整验证', value: 'FULL' }
];
const credentialPurposes = [
  { label: '查询账号', value: 'QUERY' },
  { label: '变更账号', value: 'CHANGE' },
  { label: '监控账号', value: 'MONITOR' }
] as const;

const loading = ref(false);
const submitLoading = ref(false);
const showSearch = ref(true);
const total = ref(0);
const dataSourceList = ref<DataSourceVO[]>([]);
const environments = ref<EnvironmentVO[]>([]);
const actionLoading = ref<Record<string, boolean>>({});
const optionRows = ref<ConnectionOptionRow[]>([]);

const queryFormRef = ref<ElFormInstance>();
const dataSourceFormRef = ref<ElFormInstance>();
const credentialFormRef = ref<ElFormInstance>();
const temporaryTestFormRef = ref<ElFormInstance>();

const initialForm: DataSourceForm = {
  environmentId: undefined,
  type: 'MYSQL',
  name: '',
  host: '',
  port: 3306,
  defaultDatabase: '',
  tlsMode: 'PREFER',
  ownerType: undefined,
  ownerId: undefined,
  remark: ''
};
const form = ref<DataSourceForm>({ ...initialForm });
const queryParams = reactive<DataSourceQuery>({
  pageNum: 1,
  pageSize: 10,
  name: undefined,
  environmentId: undefined,
  type: undefined
});

const dataSourceDialog = reactive({ visible: false, title: '' });
const temporaryTestDialog = reactive({ visible: false });
const credentialDrawer = reactive<{ visible: boolean; dataSourceId?: DataSourceId; dataSourceName: string }>({
  visible: false,
  dataSourceId: undefined,
  dataSourceName: ''
});
const credentialDialog = reactive({ visible: false });
const syncDialog = reactive({ visible: false, dataSourceName: '' });

const credentials = ref<CredentialVO[]>([]);
const credentialLoading = ref(false);
const credentialSubmitLoading = ref(false);
const temporaryTestLoading = ref(false);
const credentialForm = reactive<CredentialFormModel>({
  dataSourceId: '',
  purpose: '',
  username: '',
  password: '',
  confirmPassword: ''
});
const temporaryTestForm = reactive({
  username: '',
  password: '',
  confirmPassword: ''
});

const syncJobs = ref<MetadataSyncJobVO[]>([]);
const syncJobLoading = ref(false);

const validateOwnerId = (_rule: unknown, value: DataSourceId | undefined, callback: (error?: Error) => void) => {
  if (value !== undefined && value !== '' && !/^\d+$/.test(String(value))) {
    callback(new Error('Owner ID 必须为数字'));
    return;
  }
  callback();
};
const validateConfirmPassword = (_rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (value !== credentialForm.password) {
    callback(new Error('两次输入的密码不一致'));
    return;
  }
  callback();
};
const validateTemporaryTestConfirmPassword = (_rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (value !== temporaryTestForm.password) {
    callback(new Error('两次输入的密码不一致'));
    return;
  }
  callback();
};

const rules = reactive<ElFormRules>({
  environmentId: [{ required: true, message: '请选择环境', trigger: 'change' }],
  type: [{ required: true, message: '请选择数据源类型', trigger: 'change' }],
  name: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
  port: [{ required: true, message: '请输入端口', trigger: 'blur' }],
  tlsMode: [{ required: true, message: '请选择 TLS 模式', trigger: 'change' }],
  ownerId: [{ validator: validateOwnerId, trigger: 'blur' }]
});
const credentialRules = reactive<ElFormRules>({
  purpose: [{ required: true, message: '请选择凭据用途', trigger: 'change' }],
  username: [{ required: true, message: '请输入数据库用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入数据库密码', trigger: 'blur' }],
  confirmPassword: [
    { required: true, message: '请再次输入数据库密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
});
const temporaryTestRules = reactive<ElFormRules>({
  username: [{ required: true, message: '请输入测试用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入测试密码', trigger: 'blur' }],
  confirmPassword: [
    { required: true, message: '请再次输入测试密码', trigger: 'blur' },
    { validator: validateTemporaryTestConfirmPassword, trigger: 'blur' }
  ]
});

const availablePurposes = computed(() => {
  const existing = new Set(credentials.value.map((item) => item.purpose));
  return credentialPurposes.filter((item) => !existing.has(item.value));
});

const unwrapData = <T,>(response: unknown): T => {
  const value = response as { data?: T };
  return (value?.data ?? response) as T;
};

const unwrapList = <T,>(response: unknown): T[] => {
  if (Array.isArray(response)) return response as T[];
  const value = response as { rows?: T[]; data?: T[] | { rows?: T[] } };
  if (Array.isArray(value?.rows)) return value.rows;
  if (Array.isArray(value?.data)) return value.data;
  if (value?.data && Array.isArray(value.data.rows)) return value.data.rows;
  return [];
};

const loadEnvironments = async () => {
  const response = await listEnvironments();
  environments.value = unwrapList<EnvironmentVO>(response);
};

const getList = async () => {
  loading.value = true;
  try {
    const response = await list(queryParams);
    dataSourceList.value = unwrapList<DataSourceVO>(response);
    total.value = (response as unknown as { total?: number }).total ?? dataSourceList.value.length;
  } finally {
    loading.value = false;
  }
};

const handleQuery = () => {
  queryParams.pageNum = 1;
  getList();
};

const resetQuery = () => {
  queryFormRef.value?.resetFields();
  handleQuery();
};

const resetDataSourceForm = () => {
  form.value = { ...initialForm };
  optionRows.value = [];
  dataSourceFormRef.value?.resetFields();
};

const handleAdd = () => {
  resetDataSourceForm();
  dataSourceDialog.title = '新增数据源';
  dataSourceDialog.visible = true;
};

const parseConnectionOptions = (raw?: string): ConnectionOptionRow[] => {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    return Object.entries(parsed).map(([key, value]) => ({ key, value: value == null ? '' : String(value) }));
  } catch {
    return [];
  }
};

const handleUpdate = async (row: DataSourceVO) => {
  resetDataSourceForm();
  const response = await getInfo(row.id);
  const detail = unwrapData<DataSourceVO>(response);
  form.value = {
    id: detail.id,
    environmentId: detail.environmentId,
    type: detail.type,
    name: detail.name,
    host: detail.host,
    port: detail.port,
    defaultDatabase: detail.defaultDatabase || '',
    tlsMode: detail.tlsMode,
    ownerType: detail.ownerType || undefined,
    ownerId: detail.ownerId,
    remark: detail.remark || '',
    version: detail.version
  };
  optionRows.value = parseConnectionOptions(detail.connectionOptions);
  dataSourceDialog.title = '编辑数据源';
  dataSourceDialog.visible = true;
};

const handleTypeChange = (type: string) => {
  if (form.value.id) return;
  const selected = dataSourceTypes.find((item) => item.value === type);
  if (selected) form.value.port = selected.port;
};

const addOption = () => optionRows.value.push({ key: '', value: '' });
const removeOption = (index: number) => optionRows.value.splice(index, 1);

const buildConnectionOptions = (): Record<string, string> | undefined => {
  const result: Record<string, string> = {};
  const forbiddenKey = /(password|passwd|pwd|secret|token|credential|access[-_]?key|auth)/i;
  for (const item of optionRows.value) {
    const key = item.key.trim();
    if (!key && !item.value.trim()) continue;
    if (!key) throw new Error('连接参数名不能为空');
    if (forbiddenKey.test(key)) throw new Error(`连接参数“${key}”属于禁止的秘密类参数`);
    if (Object.prototype.hasOwnProperty.call(result, key)) throw new Error(`连接参数“${key}”重复`);
    result[key] = item.value;
  }
  return Object.keys(result).length ? result : undefined;
};

const submitDataSource = () => {
  dataSourceFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    let connectionOptions: Record<string, string> | undefined;
    try {
      connectionOptions = buildConnectionOptions();
    } catch (error) {
      proxy?.$modal.msgError((error as Error).message);
      return;
    }
    submitLoading.value = true;
    try {
      const payload: DataSourceForm = {
        ...form.value,
        ownerType: form.value.ownerType || undefined,
        ownerId: form.value.ownerId || undefined,
        connectionOptions
      };
      if (payload.id) {
        await update(payload);
        proxy?.$modal.msgSuccess('数据源配置已更新');
      } else {
        await add(payload);
        proxy?.$modal.msgSuccess('数据源草稿已创建，请继续配置用途凭据并测试连接');
      }
      dataSourceDialog.visible = false;
      await getList();
    } finally {
      submitLoading.value = false;
    }
  });
};

const closeDataSourceDialog = () => {
  dataSourceDialog.visible = false;
  resetDataSourceForm();
};

const clearTemporaryTestSecret = () => {
  temporaryTestForm.password = '';
  temporaryTestForm.confirmPassword = '';
};

const openTemporaryConnectionTest = () => {
  dataSourceFormRef.value?.validate((valid: boolean) => {
    if (!valid) return;
    clearTemporaryTestSecret();
    temporaryTestDialog.visible = true;
  });
};

const closeTemporaryTestDialog = () => {
  temporaryTestDialog.visible = false;
  temporaryTestForm.username = '';
  clearTemporaryTestSecret();
  temporaryTestFormRef.value?.resetFields();
};

const submitTemporaryConnectionTest = () => {
  temporaryTestFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) return;
    temporaryTestLoading.value = true;
    try {
      const payload: DataSourceConnectionTestForm = {
        type: form.value.type as string,
        host: form.value.host as string,
        port: form.value.port as number,
        defaultDatabase: form.value.defaultDatabase || undefined,
        connectionOptions: buildConnectionOptions(),
        tlsMode: form.value.tlsMode as string,
        username: temporaryTestForm.username,
        password: temporaryTestForm.password
      };
      const response = await testConnection(payload);
      const result = unwrapData<ConnectionTestResult>(response);
      if (result.success) {
        const version = result.serverVersion ? `，版本 ${result.serverVersion}` : '';
        proxy?.$modal.msgSuccess(`连接测试成功${version}`);
        temporaryTestDialog.visible = false;
      } else {
        proxy?.$modal.msgError(result.errorSummary || result.errorCode || '连接测试失败');
      }
    } catch (error) {
      if (error instanceof Error && error.message) proxy?.$modal.msgError(error.message);
    } finally {
      clearTemporaryTestSecret();
      temporaryTestLoading.value = false;
    }
  });
};

const actionKey = (id: DataSourceId, action: string) => `${id}:${action}`;
const setActionLoading = (id: DataSourceId, action: string, value: boolean) => {
  actionLoading.value[actionKey(id, action)] = value;
};
const isActionLoading = (id: DataSourceId, action: string) => !!actionLoading.value[actionKey(id, action)];

const handleVerify = async (row: Pick<DataSourceVO, 'id' | 'name'>) => {
  setActionLoading(row.id, 'verify', true);
  try {
    const response = await verify(row.id);
    const result = unwrapData<ConnectionTestResult>(response);
    if (result.success) {
      const version = result.serverVersion ? `，版本 ${result.serverVersion}` : '';
      proxy?.$modal.msgSuccess(`连接测试成功${version}`);
    } else {
      proxy?.$modal.msgError(result.errorSummary || result.errorCode || '连接测试失败');
    }
    await getList();
  } finally {
    setActionLoading(row.id, 'verify', false);
  }
};

const handleVerifyCredentialDrawer = async () => {
  if (!credentialDrawer.dataSourceId) return;
  await handleVerify({ id: credentialDrawer.dataSourceId, name: credentialDrawer.dataSourceName });
};

const handleEnable = async (row: DataSourceVO) => {
  await proxy?.$modal.confirm(`确认启用数据源“${row.name}”吗？首次启用会先同步库、表和字段目录，同步成功后才进入受控查询链路。`);
  setActionLoading(row.id, 'status', true);
  try {
    await enable(row.id);
    proxy?.$modal.msgSuccess('数据源已启用');
    await getList();
  } finally {
    setActionLoading(row.id, 'status', false);
  }
};

const handleDisable = async (row: DataSourceVO) => {
  await proxy?.$modal.confirm(`确认停用数据源“${row.name}”吗？停用会立即阻止新的查询、导出和变更执行。`);
  setActionLoading(row.id, 'status', true);
  try {
    await disable(row.id);
    proxy?.$modal.msgSuccess('数据源已停用');
    await getList();
  } finally {
    setActionLoading(row.id, 'status', false);
  }
};

const handleSync = async (row: DataSourceVO) => {
  setActionLoading(row.id, 'sync', true);
  try {
    const response = await sync(row.id);
    const job = unwrapData<MetadataSyncJobVO>(response);
    proxy?.$modal.msgSuccess(`同步完成：发现 ${job.foundCount ?? 0}，更新 ${job.updatedCount ?? 0}，下线 ${job.droppedCount ?? 0}`);
  } finally {
    setActionLoading(row.id, 'sync', false);
  }
};

const openSyncJobs = async (row: DataSourceVO) => {
  syncDialog.visible = true;
  syncDialog.dataSourceName = row.name;
  syncJobLoading.value = true;
  try {
    const response = await listSyncJobs(row.id);
    syncJobs.value = unwrapList<MetadataSyncJobVO>(response);
  } finally {
    syncJobLoading.value = false;
  }
};

const loadCredentials = async () => {
  if (!credentialDrawer.dataSourceId) return;
  credentialLoading.value = true;
  try {
    const response = await listCredentials(credentialDrawer.dataSourceId);
    credentials.value = unwrapList<CredentialVO>(response);
  } finally {
    credentialLoading.value = false;
  }
};

const openCredentials = async (row: DataSourceVO) => {
  credentialDrawer.dataSourceId = row.id;
  credentialDrawer.dataSourceName = row.name;
  credentialDrawer.visible = true;
  await loadCredentials();
};

const resetCredentialForm = () => {
  credentialForm.dataSourceId = credentialDrawer.dataSourceId ?? '';
  credentialForm.purpose = '';
  credentialForm.username = '';
  credentialForm.password = '';
  credentialForm.confirmPassword = '';
  credentialFormRef.value?.resetFields();
};

const openCredentialDialog = () => {
  resetCredentialForm();
  credentialDialog.visible = true;
};

const submitCredential = () => {
  credentialFormRef.value?.validate(async (valid: boolean) => {
    if (!valid || !credentialDrawer.dataSourceId || !credentialForm.purpose) return;
    credentialSubmitLoading.value = true;
    const requestBody: CredentialForm = {
      dataSourceId: credentialDrawer.dataSourceId,
      purpose: credentialForm.purpose,
      username: credentialForm.username,
      password: credentialForm.password
    };
    try {
      await addCredential(requestBody);
      proxy?.$modal.msgSuccess('凭据已加密保存，密码不会回显');
      credentialDialog.visible = false;
      await loadCredentials();
    } finally {
      requestBody.password = '';
      credentialForm.password = '';
      credentialForm.confirmPassword = '';
      credentialSubmitLoading.value = false;
    }
  });
};

const handleDisableCredential = async (credential: CredentialVO) => {
  await proxy?.$modal.confirm(
    `确认禁用 ${purposeLabel(credential.purpose)}“${credential.username}”吗？禁用后该用途立即不可用；当前版本尚未提供凭据轮换页面。`
  );
  await disableCredential(credential.id);
  proxy?.$modal.msgSuccess('凭据已禁用');
  await loadCredentials();
};

const environmentName = (id: DataSourceId) => environments.value.find((item) => String(item.id) === String(id))?.name || String(id);
const environmentTagType = (id: DataSourceId) => {
  const risk = environments.value.find((item) => String(item.id) === String(id))?.riskLevel;
  return risk === 'CRITICAL' ? 'danger' : risk === 'HIGH' ? 'warning' : risk === 'MEDIUM' ? 'primary' : 'success';
};
const riskLabel = (risk: string) => ({ LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险', CRITICAL: '关键生产' })[risk] || risk;
const typeLabel = (type: string) => dataSourceTypes.find((item) => item.value === type)?.label || type;
const purposeLabel = (purpose: string) => credentialPurposes.find((item) => item.value === purpose)?.label || purpose;
const statusLabel = (status: string) =>
  ({
    DRAFT: '草稿',
    VERIFYING: '已验证',
    ACTIVE: '运行中',
    DISABLED: '已停用',
    ERROR: '验证失败',
    ARCHIVED: '已归档',
    INVALID: '无效',
    PENDING: '待验证',
    SUCCESS: '成功',
    FAILED: '失败',
    RUNNING: '运行中'
  })[status] || status;
const statusTagType = (status: string) => {
  if (status === 'ACTIVE' || status === 'SUCCESS') return 'success';
  if (status === 'VERIFYING' || status === 'RUNNING') return 'primary';
  if (status === 'DRAFT' || status === 'DISABLED') return 'info';
  return 'danger';
};
const syncStatusTagType = (status: string) => (status === 'SUCCESS' ? 'success' : status === 'RUNNING' ? 'primary' : 'danger');
const canEnable = (status: string) => status === 'VERIFYING' || status === 'DISABLED';
const enableHint = (status: string) => {
  if (canEnable(status)) return '连接已验证，可以启用';
  if (status === 'DRAFT') return '请先添加 QUERY 凭据并测试连接';
  if (status === 'ERROR') return '上次连接测试失败，请修复配置后重新测试';
  return '当前状态不允许启用';
};
const canSync = (status: string) => status === 'VERIFYING' || status === 'ACTIVE';
const formatDateTime = (value?: string) => (value ? new Date(value).toLocaleString() : '—');

onMounted(async () => {
  await loadEnvironments();
  await getList();
});
</script>

<style scoped>
.option-row {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) minmax(240px, 2fr) 36px;
  gap: 10px;
  margin-bottom: 10px;
}
</style>
