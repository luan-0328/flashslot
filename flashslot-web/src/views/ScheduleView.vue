<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Location } from '@element-plus/icons-vue'
import { allResources, http, runIntent, showError } from '../api.js'
import { session } from '../session.js'
import { slotTime, shanghaiDate, statusName } from '../format.js'
const router = useRouter()
const devices = ref([]), selected = ref(null), date = ref(shanghaiDate()), schedule = ref(null), loading = ref(false), busy = ref(false), search = ref('')
const start = ref(null), end = ref(null), purpose = ref('STUDY')
const visibleDevices = computed(() => devices.value.filter(d => `${d.name}${d.location}`.includes(search.value)))
const device = computed(() => devices.value.find(d => d.id === selected.value))
const dates = ref(Array.from({ length: 7 }, (_, i) => shanghaiDate(i)))
const chosen = computed(() => schedule.value?.slots.filter(s => start.value !== null && s.slotIndex >= start.value && s.slotIndex < end.value) || [])
const waitlist = computed(() => chosen.value.some(s => ['BOOKED', 'HANDOFF'].includes(s.state)))
const stateNames = { FREE: '可预约', BOOKED: '已预约', HANDOFF: '候补交接', CLOSED: '未开放', PAST: '已开始' }
let generation = 0, midnightTimer, disposed = false
function armMidnight() {
  clearTimeout(midnightTimer)
  if (disposed) return
  const nextMidnight = new Date(`${shanghaiDate(1)}T00:00:00+08:00`).getTime()
  midnightTimer = setTimeout(() => { refreshDates(); armMidnight() }, Math.max(1, nextMidnight - Date.now()))
}
function refreshDates() {
  const today = shanghaiDate()
  if (dates.value[0] === today) return
  dates.value = Array.from({ length: 7 }, (_, i) => shanghaiDate(i))
  if (!dates.value.includes(date.value)) date.value = today
  else load()
}
function onVisibilityChange() {
  if (document.visibilityState === 'visible') { refreshDates(); armMidnight() }
}
onUnmounted(() => {
  disposed = true; generation++
  clearTimeout(midnightTimer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
async function load() {
  const ticket = ++generation
  start.value = end.value = null; schedule.value = null
  if (disposed || !selected.value) { loading.value = false; return }
  loading.value = true
  try { const result = await http.get(`/resources/${selected.value}/schedule`, { params: { date: date.value } }); if (ticket === generation) schedule.value = result }
  catch (error) { if (ticket === generation) { schedule.value = null; showError(error) } }
  finally { if (ticket === generation) loading.value = false }
}
function choose(slot) {
  if (!slot.canBook && !slot.canWaitlist) return
  if (start.value === null || end.value - start.value > 1 || slot.slotIndex < start.value || slot.slotIndex - start.value >= 8) {
    start.value = slot.slotIndex; end.value = slot.slotIndex + 1
  } else {
    const range = schedule.value.slots.slice(start.value, slot.slotIndex + 1)
    if (range.some(s => !s.canBook && !s.canWaitlist)) { ElMessage.warning('请选择连续的可申请时段'); return }
    end.value = slot.slotIndex + 1
  }
}
async function submit() {
  if (!session.token) { router.push({ path: '/login', query: { redirect: '/' } }); return }
  busy.value = true
  try {
    const isWaitlist = waitlist.value
    await runIntent(isWaitlist ? '/waitlists' : '/bookings', { resourceId: selected.value, bookDate: date.value, startSlot: start.value, endSlot: end.value, purpose: purpose.value })
    ElMessage.success(isWaitlist ? '已加入候补，可在我的候补查看进度' : '预约成功，请按时签到')
    await load()
  } catch (error) { showError(error) } finally { busy.value = false }
}
onMounted(async () => {
  refreshDates(); armMidnight()
  document.addEventListener('visibilitychange', onVisibilityChange)
  try {
    const result = await allResources()
    if (!disposed) { devices.value = result; selected.value = result[0]?.id || null }
  } catch (error) { if (!disposed) showError(error) }
})
watch([selected, date], load, { flush: 'sync' })
</script>
<template>
  <div class="page-heading"><div><span class="section-kicker">设备共享中心</span><h1>把下一段时间，安排好。</h1><p>选一台设备，再选择连续的30分钟时段。</p></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新排期</el-button></div>
  <div class="booking-layout">
    <section class="panel device-list"><div class="panel-heading"><h2>选择设备</h2><span class="muted">{{ devices.length }} 台</span></div><el-input v-model="search" placeholder="搜索设备或位置" clearable aria-label="搜索设备" /><div class="device-options"><button v-for="item in visibleDevices" :key="item.id" class="device-option" :class="{ selected: selected === item.id }" @click="selected = item.id"><span class="device-symbol"><span></span></span><span><strong>{{ item.name }}</strong><small><el-icon><Location /></el-icon>{{ item.location }}</small></span><span class="device-status" :class="item.status.toLowerCase()">{{ statusName[item.status] }}</span></button><el-empty v-if="!visibleDevices.length" description="暂无设备，请联系管理员添加" :image-size="64" /></div><p class="list-help">每次可预约连续1至8格，<br>每天跨设备合计最多4小时。</p></section>
    <section class="panel schedule-panel" v-loading="loading"><div class="panel-heading"><div><h2>{{ device?.name || '设备时间表' }}</h2><p class="muted">{{ device?.location || '先选择一台设备' }}</p></div><el-tag v-if="device" :type="device.status === 'ACTIVE' ? 'success' : 'info'">{{ statusName[device.status] }}</el-tag></div>
      <div class="date-strip" aria-label="预约日期"><button v-for="(day, index) in dates" :key="day" :class="{ active: day === date }" @click="date = day"><span>{{ index === 0 ? '今天' : index === 1 ? '明天' : new Intl.DateTimeFormat('zh-CN', { weekday: 'short' }).format(new Date(day + 'T12:00:00+08:00')) }}</span><strong>{{ day.slice(5).replace('-', '/') }}</strong></button></div>
      <template v-if="schedule"><div class="legend"><span><i class="free"></i>可预约</span><span><i class="booked"></i>已占用，可候补</span><span><i class="selected"></i>已选择</span><span><i class="closed"></i>未开放 / 已开始</span></div><div class="slot-grid"><button v-for="slot in schedule.slots" :key="slot.slotIndex" :aria-pressed="slot.slotIndex >= start && start !== null && slot.slotIndex < end" :disabled="!slot.canBook && !slot.canWaitlist" :class="['slot', slot.state.toLowerCase(), { chosen: start !== null && slot.slotIndex >= start && slot.slotIndex < end, mine: slot.mine }]" @click="choose(slot)"><strong>{{ slotTime(slot.slotIndex) }}</strong><span>{{ slot.mine ? '我的预约' : stateNames[slot.state] }}</span></button></div></template><el-empty v-else-if="!loading" description="暂无当日日历，请选择其他设备或稍后重试" />
      <div class="booking-summary"><div><strong>{{ start === null ? '点击开始时间，再点击结束的时间格' : `${date}　${slotTime(start)} — ${slotTime(end)}` }}</strong><p>{{ start === null ? '每格30分钟，预约须提前30分钟提交。' : `共${chosen.length / 2}小时${waitlist ? '，所选区间有占用，将申请候补。' : '，所选时段可预约。'}` }}</p></div><div class="booking-actions"><el-select v-model="purpose" aria-label="预约用途"><el-option label="学习使用" value="STUDY" /><el-option label="实验研究" value="EXPERIMENT" /><el-option label="设备测试" value="DEVICE_TEST" /></el-select><el-button type="primary" :loading="busy" :disabled="!chosen.length || loading" @click="submit">{{ waitlist ? '加入候补' : '确认预约' }}</el-button></div></div>
    </section>
  </div><p class="page-note">取消等操作产生空位后自动触发候补撮合；签到窗口为开始前10分钟至开始后15分钟，请提前安排。</p>
</template>
