<template>
  <div class="p-2">
    <el-card shadow="hover" class="mb-2">
      <template #header><span>列脱敏标签管理（docs/04 §3.7 / M5-05b）</span></template>
      <el-form :inline="true">
        <el-form-item label="表资源ID"><el-input-number v-model="tableId" :min="1" /></el-form-item>
        <el-button type="primary" icon="Search" @click="load">查询列</el-button>
      </el-form>
    </el-card>
    <el-card shadow="hover">
      <el-table v-loading="loading" :data="list" border>
        <el-table-column label="列资源ID" prop="resourceId" width="120" />
        <el-table-column label="列名" prop="columnName" width="180" />
        <el-table-column label="路径" prop="canonicalPath" min-width="280" show-overflow-tooltip />
        <el-table-column label="敏感等级" prop="sensitivityLevel" width="130">
          <template #default="{ row }"><el-tag :type="levelTag(row.sensitivityLevel)">{{ row.sensitivityLevel }}</el-tag></template>
        </el-table-column>
        <el-table-column label="脱敏类型" prop="maskingType" width="130" />
        <el-table-column label="来源" prop="classificationSource" width="100" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }"><el-button size="small" type="primary" @click="openLabel(row)">标注</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="labelDlg" title="人工标注列敏感标签（MANUAL）" width="460px">
      <el-form label-width="110px">
        <el-form-item label="敏感等级">
          <el-select v-model="labelForm.sensitivityLevel" style="width:200px">
            <el-option label="PUBLIC" value="PUBLIC" /><el-option label="INTERNAL" value="INTERNAL" />
            <el-option label="SENSITIVE" value="SENSITIVE" /><el-option label="RESTRICTED" value="RESTRICTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="脱敏类型">
          <el-select v-model="labelForm.maskingType" style="width:200px">
            <el-option label="NONE" value="NONE" /><el-option label="PHONE" value="PHONE" /><el-option label="ID_CARD" value="ID_CARD" />
            <el-option label="BANK_CARD" value="BANK_CARD" /><el-option label="EMAIL" value="EMAIL" /><el-option label="ADDRESS" value="ADDRESS" />
            <el-option label="CUSTOM" value="CUSTOM" />
          </el-select>
        </el-form-item>
        <el-form-item label="自定义配置"><el-input v-model="labelForm.maskingConfig" placeholder='{"keepPrefix":2,"keepSuffix":2}' /></el-form-item>
      </el-form>
      <template #footer><el-button @click="labelDlg=false">取消</el-button><el-button type="primary" @click="doLabel">确认</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import { listColumnProfileByTable, setManualLabel, type ColumnProfileVO } from '@/api/db/columnProfile';

const loading = ref(false);
const list = ref<ColumnProfileVO[]>([]);
const tableId = ref(1);
const labelDlg = ref(false);
const labelForm = ref<{ resourceId: number; sensitivityLevel: string; maskingType: string; maskingConfig: string }>({ resourceId: 0, sensitivityLevel: 'SENSITIVE', maskingType: 'PHONE', maskingConfig: '' });

const load = async () => {
  loading.value = true;
  try { list.value = await listColumnProfileByTable(tableId.value); } finally { loading.value = false; }
};
const openLabel = (row: ColumnProfileVO) => {
  labelForm.value = { resourceId: row.resourceId, sensitivityLevel: row.sensitivityLevel || 'SENSITIVE', maskingType: row.maskingType || 'PHONE', maskingConfig: row.maskingConfig || '' };
  labelDlg.value = true;
};
const doLabel = async () => {
  await setManualLabel(labelForm.value.resourceId, { sensitivityLevel: labelForm.value.sensitivityLevel, maskingType: labelForm.value.maskingType, maskingConfig: labelForm.value.maskingConfig || undefined });
  ElMessage.success('已人工标注（MANUAL，重同步不覆盖）');
  labelDlg.value = false; load();
};
const levelTag = (l: string) => ({ RESTRICTED: 'danger', SENSITIVE: 'warning', INTERNAL: 'info', PUBLIC: 'success' }[l] || 'info');
</script>
