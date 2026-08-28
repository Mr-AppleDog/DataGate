<template>
  <div class="p-2">
    <el-card shadow="hover" class="mb-2">
      <template #header><span>受控导出工单（docs/03 §10.2 / M5-01）</span></template>
      <el-button type="primary" icon="Plus" @click="openApply">申请导出</el-button>
      <el-button icon="Refresh" @click="load">刷新</el-button>
    </el-card>
    <el-card shadow="hover">
      <el-table v-loading="loading" :data="list" border>
        <el-table-column label="ID" prop="id" width="80" />
        <el-table-column label="申请号" prop="requestNo" width="200" show-overflow-tooltip />
        <el-table-column label="数据源" prop="dataSourceId" width="90" />
        <el-table-column label="指纹" prop="fingerprint" width="160" show-overflow-tooltip />
        <el-table-column label="脱敏" prop="maskingLevel" width="100" />
        <el-table-column label="状态" prop="status" width="130">
          <template #default="{ row }"><el-tag :type="statusTag(row.status)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="行数" prop="rowCount" width="90" />
        <el-table-column label="下载次数" prop="downloadCount" width="90" />
        <el-table-column label="过期" prop="expiresAt" width="160" />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status==='PENDING_APPROVAL'" size="small" type="success" @click="doApprove(row)">审批</el-button>
            <el-button v-if="row.status==='SUCCEEDED'" size="small" type="primary" @click="doTicket(row)">下载票据</el-button>
            <el-button v-if="row.status==='PENDING_APPROVAL'" size="small" type="warning" @click="doCancel(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="applyDlg" title="申请导出" width="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="数据源ID"><el-input-number v-model="form.dataSourceId" :min="1" /></el-form-item>
        <el-form-item label="SQL"><el-input v-model="form.statement" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="Owner审批人"><el-input-number v-model="form.ownerApproverId" :min="1" /></el-form-item>
        <el-form-item label="DBA审批人"><el-input-number v-model="form.dbaApproverId" :min="1" /></el-form-item>
        <el-form-item label="理由"><el-input v-model="form.reason" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyDlg = false">取消</el-button>
        <el-button type="primary" @click="doApply">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listExport, applyExport, approveExport, cancelExport, downloadTicket, downloadUrl, type ExportJobVO, type ExportApplyBo } from '@/api/db/export';

const loading = ref(false);
const list = ref<ExportJobVO[]>([]);
const applyDlg = ref(false);
const form = ref<ExportApplyBo>({ dataSourceId: 1, statement: '', ownerApproverId: 1, dbaApproverId: 2, reason: '' });

const load = async () => {
  loading.value = true;
  try { const res = await listExport(); list.value = res.rows || []; } finally { loading.value = false; }
};
const openApply = () => { applyDlg.value = true; };
const doApply = async () => {
  await applyExport(form.value);
  ElMessage.success('已提交导出申请');
  applyDlg.value = false; load();
};
const doApprove = async (row: ExportJobVO) => {
  await approveExport(row.id, { jobId: row.id, message: 'approved' });
  ElMessage.success('审批通过'); load();
};
const doCancel = async (row: ExportJobVO) => {
  await cancelExport(row.id, { jobId: row.id });
  ElMessage.success('已撤销'); load();
};
const doTicket = async (row: ExportJobVO) => {
  const res = await downloadTicket(row.id);
  const ticket = (res as any).data || res;
  ElMessageBox.alert(`票据：${ticket}（5min，单次）\n下载链接：${downloadUrl(ticket)}`, '一次性下载票据');
};
const statusTag = (s: string) => ({ SUCCEEDED: 'success', FAILED: 'danger', PENDING_APPROVAL: 'warning', EXPIRED: 'info' }[s] || 'info');
onMounted(load);
</script>
