<script setup>
import { onMounted, reactive, ref } from 'vue'
import { http, showError } from '../api.js'
import { ElMessage } from 'element-plus'
import { signOut, session } from '../session.js'
import { useRouter } from 'vue-router'
const router = useRouter(), saving = ref(false)
const password = reactive({ currentPassword: '', newPassword: '', confirmation: '' })
async function changePassword() {
  if (saving.value) return
  if (!password.currentPassword || password.newPassword.length < 8 || new TextEncoder().encode(password.newPassword).length > 72) { ElMessage.warning('请填写原密码，新密码至少 8 个字符且不超过 72 个 UTF-8 字节'); return }
  if (password.newPassword !== password.confirmation) { ElMessage.warning('两次新密码不一致'); return }
  saving.value = true
  try {
    await http.post('/me/password', { currentPassword: password.currentPassword, newPassword: password.newPassword })
    Object.assign(password, { currentPassword: '', newPassword: '', confirmation: '' })
    signOut(); ElMessage.success('密码已修改，请重新登录'); router.push('/login')
  } catch (error) { showError(error) }
  finally { saving.value = false }
}
import { dateTime } from '../format.js'

const profile = ref(null), loading = ref(false), error = ref('')
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try { profile.value = await http.get('/me/profile') }
  catch (cause) { profile.value = null; error.value = cause.response?.data?.msg || '账号状态读取失败，请重试' }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-heading">
    <div><h1>{{ session.user?.role === 'ADMIN' ? '账号与密码' : '我的账号' }}</h1><p>{{ session.user?.role === 'ADMIN' ? '管理员账号仅用于管理，可在此修改登录密码。' : '查看当前信用分和预约限制，按时使用每一次预约。' }}</p></div>
    <el-button :loading="loading" @click="load">刷新状态</el-button>
  </div>
  <section class="panel" v-loading="loading" :aria-busy="loading" aria-label="账号状态">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="profile">
      <el-alert v-if="profile.role !== 'ADMIN'" :type="profile.restricted ? 'warning' : 'success'" :closable="false" show-icon
        :title="profile.restricted ? '账号当前处于预约限制期' : '账号当前未受预约限制'"
        :description="profile.restricted ? `限制至 ${dateTime(profile.restrictionUntil)}，到期后可重新申请。已有预约请继续按时签到。` : '预约仍需满足设备开放、申请截止时间和时段冲突等条件。'" />
      <el-descriptions :column="1" border>
        <el-descriptions-item label="账号 ID">{{ profile.id }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ profile.username }}</el-descriptions-item>
        <el-descriptions-item label="当前角色">{{ profile.role === 'ADMIN' ? '管理员' : '使用者' }}</el-descriptions-item>
        <el-descriptions-item v-if="profile.role !== 'ADMIN'" label="信用分">{{ profile.creditScore }} / 100</el-descriptions-item>
        <el-descriptions-item v-if="profile.role !== 'ADMIN'" label="限制截止">{{ dateTime(profile.restrictionUntil) }}{{ profile.restrictionUntil && !profile.restricted ? '（已到期）' : '' }}</el-descriptions-item>
        <el-descriptions-item label="状态更新时间">{{ dateTime(profile.serverTime) }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="profile.role !== 'ADMIN'" class="spaced-top toolbar"><RouterLink to="/">去预约设备</RouterLink><RouterLink to="/bookings">查看我的预约</RouterLink></div>
    </template>
  </section>
  <section class="panel spaced-top" aria-label="修改密码">
    <h2>修改密码</h2><p>修改成功后所有旧登录凭据失效，需要重新登录。</p>
    <el-form label-position="top" @submit.prevent="changePassword">
      <el-form-item label="原密码"><el-input v-model="password.currentPassword" type="password" show-password autocomplete="current-password" maxlength="72" /></el-form-item>
      <el-form-item label="新密码"><el-input v-model="password.newPassword" type="password" show-password autocomplete="new-password" maxlength="72" /><small>至少 8 个字符，最多 72 个 UTF-8 字节。</small></el-form-item>
      <el-form-item label="确认新密码"><el-input v-model="password.confirmation" type="password" show-password autocomplete="new-password" maxlength="72" /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="saving">修改密码并重新登录</el-button>
    </el-form>
  </section>
  <section v-if="session.user?.role !== 'ADMIN'" class="panel spaced-top" aria-label="使用规则">
    <h2>预约与签到规则</h2>
    <p>可申请今天起 7 天内的时段，每格 30 分钟，一次连续 1 至 8 格，最长 4 小时；同一用户每天跨设备合计最多 8 格（4 小时）。取消或提前结束只返还尚未开始的时间格。请在开始前至少 30 分钟完成申请。</p>
    <p>签到窗口为预约开始前 10 分钟至开始后 15 分钟，使用设备现场的有效签到码。逾期未签到会记为爽约并影响信用分。</p>
    <p>无法使用时，请及时取消预约；已经签到的预约可提前结束。取消等操作产生空位后会自动触发候补撮合，转正结果可在“我的候补”和消息通知中查看。</p>
  </section>
</template>
