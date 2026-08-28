<template>
  <div class="p-2">
    <el-card shadow="hover" class="mb-2">
      <template #header><span>变更工单（DML/DDL/Redis）（docs/03 §10.3 / M5-02/03）</span></template>
      <el-button type="primary" icon="Plus" @click="openCreate">新建工单</el-button>
      <el-button icon="Refresh" @click="load">刷新</el-button>
    </el-card>
    <el-card shadow="hover">
      <el-table v-loading="loading" :data="list" border>
        <el-table-column label="ID" prop="id" width="80" />
        <el-table-column label="申请号" prop="requestNo" width="200" show-overflow-tooltip />
        <el-table-column label="类型" prop="changeType" width="90" />
        <el-table-column label="数据源" prop="dataSourceId" width="90" />
        <el-table-column label="风险" prop="precheckResult" width="120" show-overflow-tooltip />
        <el-table-column label="状态" prop="status" width="140">
          <template #default="{ row }"><el-tag :type="statusTag(row.status)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="执行窗口" prop="executionWindowStart" width="160" />
        <el-table-column label="操作" width="360" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status==='DRAFT'" size="small" @click="doPrecheck(row)">预检查</el-button>
            <el-button v-if="row.status==='PRECHECKED'" size="small" type="primary" @click="doSubmit(row)">提交</el-button>
            <el-button v-if="row.status==='PENDING_APPROVAL'" size="small" type="success" @click="doApprove(row)">审批</el-button>
            <el-button v-if="row.status==='APPROVED'" size="small" type="warning" @click="openSchedule(row)">调度</el-button>
            <el-button v-if="row.status==='SCHEDULED'" size="small" type="danger" @click="doExecute(row)">执行</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createDlg" title="新建变更工单" width="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="数据源ID"><el-input-number v-model="form.dataSourceId" :min="1" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.changeType" style="width:200px"><el-option label="DML" value="DML" /><el-option label="DDL" value="DDL" /><el-option label="REDIS" value="REDIS" /></el-select>
        </el-form-item>
        <el-form-item label="SQL/命令JSON"><el-input v-model="form.statement" type="textarea" :rows="4" /></el-form-item>
        <el-form-item label="业务负责人"><el-input-number v-model="form.bizApproverId" :min="1" /></el-form-item>
        <el-form-item label="DBA"><el-input-number v-model="form.dbaApproverId" :min="1" /></el-form-item>
        <el-form-item label="回滚方案"><el-input v-model="form.rollbackPlan" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createDlg=false">取消</el-button><el-button type="primary" @click="doCreate">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="schedDlg" title="设置执行窗口" width="460px">
      <el-form label-width="110px">
        <el-form-item label="开始"><el-date-picker v-model="sched.start" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item label="结束"><el-date-picker v-model="sched.end" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="schedDlg=false">取消</el-button><el-button type="primary" @click="doSchedule">确认</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { listChange, createChange, precheckChange, submitChange, approveChange, scheduleChange, executeChange, type ChangeOrderVO, type ChangeApplyBo } from '@/api/db/change';

const loading = ref(false);
const list = ref<ChangeOrderVO[]>([]);
const createDlg = ref(false);
const schedDlg = ref(false);
const form = ref<ChangeApplyBo>({ dataSourceId: 1, changeType: 'DML', statement: '', bizApproverId: 1, dbaApproverId: 2 });
const sched = ref<{ id: number; start: string; end: string }>({ id: 0, start: '', end: '' });

const load = async () => { loading.value = true; try { const res = await listChange(); list.value = res.rows || []; } finally { loading.value = false; } };
const openCreate = () => { createDlg.value = true; };
const doCreate = async () => { await createChange(form.value); ElMessage.success('已创建'); createDlg.value = false; load(); };
const doPrecheck = async (r: ChangeOrderVO) => { await precheckChange(r.id); ElMessage.success('预检查完成'); load(); };
const doSubmit = async (r: ChangeOrderVO) => { await submitChange(r.id); ElMessage.success('已提交审批'); load(); };
const doApprove = async (r: ChangeOrderVO) => { await approveChange(r.id, { orderId: r.id }); ElMessage.success('审批通过'); load(); };
const openSchedule = (r: ChangeOrderVO) => { sched.value = { id: r.id, start: '', end: '' }; schedDlg.value = true; };
const doSchedule = async () => { await scheduleChange(sched.value.id, { orderId: sched.value.id, executionWindowStart: sched.value.start, executionWindowEnd: sched.value.end }); ElMessage.success('已调度'); schedDlg.value = false; load(); };
const doExecute = async (r: ChangeOrderVO) => { const res = await executeChange(r.id); ElMessage.success('执行：' + (res as any).data?.status); load(); };
const statusTag = (s: string) => ({ SUCCEEDED: 'success', FAILED: 'danger', PENDING_APPROVAL: 'warning', SCHEDULED: 'info', APPROVED: 'primary' }[s] || 'info');
onMounted(load);
</script>
