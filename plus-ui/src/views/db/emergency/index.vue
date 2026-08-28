<template>
  <div class="p-2">
    <el-card shadow="hover" class="mb-2">
      <template #header><span>紧急访问（双人审批·2h临时授权）（docs/03 §10.4 / M5-04）</span></template>
      <el-button type="danger" icon="Alert" @click="openApply">申请紧急访问</el-button>
      <el-button icon="Refresh" @click="load">刷新</el-button>
    </el-card>
    <el-card shadow="hover">
      <el-table v-loading="loading" :data="list" border>
        <el-table-column label="ID" prop="id" width="80" />
        <el-table-column label="事件编号" prop="eventNo" width="180" show-overflow-tooltip />
        <el-table-column label="目标资源" prop="targetResourceId" width="100" />
        <el-table-column label="动作" prop="targetAction" width="100" />
        <el-table-column label="状态" prop="status" width="150">
          <template #default="{ row }"><el-tag :type="statusTag(row.status)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="有效期至" prop="validUntil" width="160" />
        <el-table-column label="复盘截止" prop="postMortemDueAt" width="160" />
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status==='PENDING_APPROVAL'" size="small" type="success" @click="doApprove(row)">审批</el-button>
            <el-button v-if="row.status==='ACTIVE'" size="small" type="danger" @click="doRevoke(row)">撤销授权</el-button>
            <el-button v-if="row.status==='ACTIVE'||row.status==='EXPIRED'||row.status==='REVOKED'" size="small" @click="openPostMortem(row)">复盘</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="applyDlg" title="申请紧急访问" width="520px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="事件编号"><el-input v-model="form.eventNo" /></el-form-item>
        <el-form-item label="目标资源ID"><el-input-number v-model="form.targetResourceId" :min="1" /></el-form-item>
        <el-form-item label="动作"><el-input v-model="form.targetAction" placeholder="QUERY/EXPORT..." /></el-form-item>
        <el-form-item label="审批人1"><el-input-number v-model="form.approver1Id" :min="1" /></el-form-item>
        <el-form-item label="审批人2"><el-input-number v-model="form.approver2Id" :min="1" /></el-form-item>
        <el-form-item label="有效小时(≤2)"><el-input-number v-model="form.validHours" :min="1" :max="2" /></el-form-item>
        <el-form-item label="理由"><el-input v-model="form.reason" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="applyDlg=false">取消</el-button><el-button type="danger" @click="doApply">提交</el-button></template>
    </el-dialog>

    <el-dialog v-model="pmDlg" title="事后复盘（开通后24h内）" width="520px">
      <el-input v-model="pmContent" type="textarea" :rows="5" placeholder="复盘内容（必填）" />
      <template #footer><el-button @click="pmDlg=false">取消</el-button><el-button type="primary" @click="doPostMortem">提交复盘</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { listEmergency, applyEmergency, approveEmergency, revokeEmergency, postMortemEmergency, type EmergencyAccessVO, type EmergencyApplyBo } from '@/api/db/emergency';

const loading = ref(false);
const list = ref<EmergencyAccessVO[]>([]);
const applyDlg = ref(false);
const pmDlg = ref(false);
const pmId = ref(0);
const pmContent = ref('');
const form = ref<EmergencyApplyBo>({ eventNo: '', targetResourceId: 1, targetAction: 'QUERY', approver1Id: 1, approver2Id: 2, validHours: 2, reason: '' });

const load = async () => { loading.value = true; try { const res = await listEmergency(); list.value = res.rows || []; } finally { loading.value = false; } };
const openApply = () => { applyDlg.value = true; };
const doApply = async () => { await applyEmergency(form.value); ElMessage.success('已提交紧急访问申请'); applyDlg.value = false; load(); };
const doApprove = async (r: EmergencyAccessVO) => { await approveEmergency(r.id, { accessId: r.id }); ElMessage.success('审批通过'); load(); };
const doRevoke = async (r: EmergencyAccessVO) => { await revokeEmergency(r.id, { accessId: r.id }); ElMessage.success('已即时撤销'); load(); };
const openPostMortem = (r: EmergencyAccessVO) => { pmId.value = r.id; pmContent.value = r.postMortemContent || ''; pmDlg.value = true; };
const doPostMortem = async () => { await postMortemEmergency(pmId.value, { accessId: pmId.value, postMortemContent: pmContent.value }); ElMessage.success('复盘已提交'); pmDlg.value = false; load(); };
const statusTag = (s: string) => ({ ACTIVE: 'danger', EXPIRED: 'info', REVOKED: 'warning', POST_MORTEM_DONE: 'success', PENDING_APPROVAL: 'warning' }[s] || 'info');
onMounted(load);
</script>
