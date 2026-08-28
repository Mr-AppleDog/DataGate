<template>
  <div class="p-2">
    <el-card shadow="hover" class="mb-2">
      <template #header><span>慢查询治理工作台（docs/07 §10）</span></template>
      <el-form :inline="true">
        <el-form-item label="治理状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 180px" @change="load">
            <el-option v-for="s in statuses" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Refresh" @click="load">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <el-card shadow="hover">
      <el-table v-loading="loading" :data="list" border style="width: 100%">
        <el-table-column label="ID" prop="id" width="80" />
        <el-table-column label="数据源" prop="dataSourceId" width="90" />
        <el-table-column label="引擎" prop="engine" width="100" />
        <el-table-column label="指纹" prop="fingerprint" width="160" show-overflow-tooltip />
        <el-table-column label="归一化SQL" prop="normalizedStatement" min-width="280" show-overflow-tooltip />
        <el-table-column label="治理状态" prop="governanceStatus" width="130">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.governanceStatus)">{{ row.governanceStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="负责人" prop="assigneeId" width="90" />
        <el-table-column label="最近出现" prop="lastSeenAt" width="160" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.governanceStatus === 'DISCOVERED'" size="small" type="primary" @click="openClaim(row)">认领</el-button>
            <el-button v-if="canTransition(row.governanceStatus)" size="small" type="warning" @click="openTransition(row)">流转</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="claimDlg" title="认领指纹" width="420px">
      <el-form label-width="100px">
        <el-form-item label="负责人ID"><el-input-number v-model="claimForm.assigneeId" :min="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="claimDlg = false">取消</el-button>
        <el-button type="primary" @click="doClaim">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="transDlg" title="状态流转" width="460px">
      <el-form label-width="100px">
        <el-form-item label="当前状态"><el-tag>{{ transForm.from }}</el-tag></el-form-item>
        <el-form-item label="目标状态">
          <el-select v-model="transForm.toStatus" style="width: 200px">
            <el-option v-for="s in nextStates(transForm.from)" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="评论"><el-input v-model="transForm.comment" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transDlg = false">取消</el-button>
        <el-button type="primary" @click="doTransition">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="SlowQueryGovernance">
import { ref, reactive, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { listFingerprints, claim, transition, type SlowFingerprintVO } from '@/api/db/slowQuery';

const statuses = ['DISCOVERED', 'CLAIMED', 'IN_PROGRESS', 'PENDING_VERIFY', 'RESOLVED', 'IGNORED'];
const loading = ref(false);
const list = ref<SlowFingerprintVO[]>([]);
const query = reactive({ status: '', dataSourceId: undefined as number | undefined });

const load = async () => {
  loading.value = true;
  try {
    const { data } = await listFingerprints({ governanceStatus: query.status || undefined, limit: 50 });
    list.value = data;
  } catch (e) {
    ElMessage.error('加载失败');
  } finally {
    loading.value = false;
  }
};
onMounted(load);

const statusTag = (s: string) =>
  ({ DISCOVERED: 'info', CLAIMED: 'warning', IN_PROGRESS: 'warning', PENDING_VERIFY: 'warning', RESOLVED: 'success', IGNORED: 'info' }[s] || 'info');
const canTransition = (s: string) => s !== 'RESOLVED' && s !== 'IGNORED';
const nextStates = (from: string): string[] =>
  ({
    DISCOVERED: ['CLAIMED', 'IGNORED'],
    CLAIMED: ['IN_PROGRESS', 'IGNORED', 'DISCOVERED'],
    IN_PROGRESS: ['PENDING_VERIFY', 'IGNORED', 'CLAIMED'],
    PENDING_VERIFY: ['RESOLVED', 'IN_PROGRESS', 'IGNORED'],
    IGNORED: ['DISCOVERED']
  }[from] || []);

const claimDlg = ref(false);
const claimForm = reactive({ id: 0, assigneeId: 1 });
const openClaim = (row: SlowFingerprintVO) => {
  claimForm.id = row.id;
  claimForm.assigneeId = row.assigneeId || 1;
  claimDlg.value = true;
};
const doClaim = async () => {
  try {
    await claim(claimForm.id, claimForm.assigneeId);
    ElMessage.success('认领成功');
    claimDlg.value = false;
    load();
  } catch (e) {
    ElMessage.error('认领失败');
  }
};

const transDlg = ref(false);
const transForm = reactive({ id: 0, from: '', toStatus: '', version: 0, comment: '' });
const openTransition = (row: SlowFingerprintVO) => {
  transForm.id = row.id;
  transForm.from = row.governanceStatus;
  transForm.version = row.version;
  transForm.toStatus = '';
  transForm.comment = '';
  transDlg.value = true;
};
const doTransition = async () => {
  if (!transForm.toStatus) {
    ElMessage.warning('请选择目标状态');
    return;
  }
  try {
    await transition(transForm.id, transForm.toStatus, transForm.version, transForm.comment);
    ElMessage.success('流转成功');
    transDlg.value = false;
    load();
  } catch (e) {
    ElMessage.error('流转失败，可能版本已变化');
  }
};
</script>
