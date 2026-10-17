<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { runIntent, showError } from '../api.js'
import { usePage } from '../composables/usePage.js'
import { dateTime, slotTime, statusName, statusType } from '../format.js'
const { items, busy, hasMore, load } = usePage('/me/waitlists')
const acting = ref(false)
async function cancel(row) {
  try { await ElMessageBox.confirm('取消后将退出本次候补队列，确认取消？', '取消候补', { confirmButtonText: '取消候补', cancelButtonText: '返回' }) } catch { return }
  acting.value = true
  try { await runIntent(`/me/waitlists/${row.waitlistNo}/cancel`); ElMessage.success('候补已处理'); await load() } catch (error) { showError(error) } finally { acting.value = false }
}
onMounted(load)
</script>
<template><div class="page-heading"><div><h1>我的候补</h1><p>空位释放后自动按顺序撮合；转正后会出现在“我的预约”并收到消息。</p></div><el-button :loading="busy" @click="load()">刷新</el-button></div><section class="panel" v-loading="busy"><el-table :data="items" empty-text="暂无候补记录"><el-table-column prop="resourceName" label="设备" min-width="160" /><el-table-column label="申请时段" min-width="190"><template #default="{ row }">{{ row.bookDate }}<div>{{ slotTime(row.startSlot) }} — {{ slotTime(row.endSlot) }}</div></template></el-table-column><el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusName[row.status] }}</el-tag></template></el-table-column><el-table-column label="队列位置" width="100"><template #default="{ row }">{{ row.queuePosition ? `第${row.queuePosition}位` : '—' }}</template></el-table-column><el-table-column label="转正截止" min-width="150"><template #default="{ row }">{{ dateTime(row.expireAt) }}</template></el-table-column><el-table-column label="操作" width="110"><template #default="{ row }"><el-button v-if="row.status === 'WAITING'" link type="danger" :disabled="acting" @click="cancel(row)">取消候补</el-button><RouterLink v-if="row.status === 'MATCHED'" to="/bookings">查看预约</RouterLink></template></el-table-column></el-table><div class="pagination"><el-button v-if="hasMore" :loading="busy" @click="load(true)">加载更多</el-button></div></section></template>
