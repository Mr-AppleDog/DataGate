<template>
  <div class="p-2">
    <el-tabs v-model="tab">
      <el-tab-pane label="告警事件" name="events">
        <el-card shadow="hover" class="mb-2">
          <el-form :inline="true">
            <el-form-item label="状态">
              <el-select v-model="evQuery.status" clearable placeholder="全部" style="width:160px" @change="loadEvents">
                <el-option v-for="s in eventStatuses" :key="s" :label="s" :value="s" />
              </el-select>
            </el-form-item>
            <el-form-item><el-button type="primary" icon="Refresh" @click="loadEvents">刷新</el-button></el-form-item>
          </el-form>
        </el-card>
        <el-card shadow="hover">
          <el-table v-loading="evLoading" :data="events" border>
            <el-table-column label="ID" prop="id" width="80" />
            <el-table-column label="规则ID" prop="ruleId" width="80" />
            <el-table-column label="级别" prop="severity" width="80" />
            <el-table-column label="状态" prop="status" width="110" />
            <el-table-column label="触发次数" prop="triggerCount" width="90" />
            <el-table-column label="当前值" prop="currentValue" width="110" />
            <el-table-column label="阈值" prop="threshold" width="110" />
            <el-table-column label="摘要" prop="evidenceSummary" min-width="240" show-overflow-tooltip />
            <el-table-column label="最近触发" prop="lastFiredAt" width="160" />
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status==='FIRING'" size="small" type="primary" @click="doAck(row)">确认</el-button>
                <el-button size="small" type="warning" @click="openSilence(row)">静默</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="告警规则" name="rules">
        <el-card shadow="hover">
          <el-table :data="rules" border>
            <el-table-column label="ID" prop="id" width="80" />
            <el-table-column label="名称" prop="name" min-width="200" show-overflow-tooltip />
            <el-table-column label="级别" prop="severity" width="100" />
            <el-table-column label="指标" prop="metric" width="180" />
            <el-table-column label="阈值" prop="threshold" width="120" />
            <el-table-column label="状态" prop="status" width="100" />
          </el-table>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="通知通道" name="channels">
        <el-card shadow="hover">
          <el-table :data="channels" border>
            <el-table-column label="ID" prop="id" width="80" />
            <el-table-column label="类型" prop="type" width="100" />
            <el-table-column label="名称" prop="name" min-width="180" />
            <el-table-column label="状态" prop="status" width="100" />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" @click="doTest(row)">测试</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="silenceDlg" title="静默告警" width="420px">
      <el-form label-width="100px">
        <el-form-item label="静默至">
          <el-date-picker v-model="silenceForm.until" type="datetime" value-format="x" style="width:200px" />
        </el-form-item>
        <el-form-item label="原因"><el-input v-model="silenceForm.reason" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="silenceDlg=false">取消</el-button>
        <el-button type="primary" @click="doSilence">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts" name="AlertManagement">
import { ref, reactive, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import {
  listEvents, acknowledgeEvent, silenceEvent, listRules, listChannels, testChannel,
  type AlertEventVO, type AlertRuleVO, type NotificationChannelVO
} from '@/api/db/alert';

const tab = ref('events');
const eventStatuses = ['PENDING', 'FIRING', 'ACKNOWLEDGED', 'RESOLVED', 'SILENCED'];
const evLoading = ref(false);
const events = ref<AlertEventVO[]>([]);
const evQuery = reactive({ status: '' });
const rules = ref<AlertRuleVO[]>([]);
const channels = ref<NotificationChannelVO[]>([]);

const loadEvents = async () => {
  evLoading.value = true;
  try {
    const { data } = await listEvents({ status: evQuery.status || undefined, limit: 50 });
    events.value = data;
  } finally { evLoading.value = false; }
};
const loadRules = async () => { const { data } = await listRules(); rules.value = data; };
const loadChannels = async () => { const { data } = await listChannels(); channels.value = data; };
onMounted(() => { loadEvents(); loadRules(); loadChannels(); });

const doAck = async (row: AlertEventVO) => {
  try { await acknowledgeEvent(row.id, (row as any).version); ElMessage.success('已确认'); loadEvents(); }
  catch (e) { ElMessage.error('确认失败'); }
};

const silenceDlg = ref(false);
const silenceForm = reactive({ id: 0, until: 0, reason: '', version: 0 });
const openSilence = (row: AlertEventVO) => {
  silenceForm.id = row.id;
  silenceForm.until = Date.now() + 3600000;
  silenceForm.reason = '';
  silenceForm.version = (row as any).version;
  silenceDlg.value = true;
};
const doSilence = async () => {
  try { await silenceEvent(silenceForm.id, { until: silenceForm.until, reason: silenceForm.reason, version: silenceForm.version }); ElMessage.success('已静默'); silenceDlg.value = false; loadEvents(); }
  catch (e) { ElMessage.error('静默失败'); }
};

const doTest = async (row: NotificationChannelVO) => {
  try { const { data } = await testChannel(row.id); ElMessage.success('测试结果: ' + (data.success ? '成功' : '失败')); }
  catch (e) { ElMessage.error('测试失败'); }
};
</script>
